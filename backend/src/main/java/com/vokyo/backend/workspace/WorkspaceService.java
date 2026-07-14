package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.AuthSessionService;
import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.dto.CreateWorkspaceRequest;
import com.vokyo.backend.workspace.dto.SwitchWorkspaceRequest;
import com.vokyo.backend.workspace.dto.UpdateWorkspaceMemberRequest;
import com.vokyo.backend.workspace.dto.WorkspaceMemberResponse;
import com.vokyo.backend.auth.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceProvisioningService workspaceProvisioningService;
    private final AuthSessionService authSessionService;
    private final WorkspaceAccessService workspaceAccessService;
    private final RefreshTokenService refreshTokenService;

    public WorkspaceService(
            UserRepository userRepository,
            WorkspaceMembershipRepository membershipRepository,
            WorkspaceProvisioningService workspaceProvisioningService,
            AuthSessionService authSessionService,
            WorkspaceAccessService workspaceAccessService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceProvisioningService = workspaceProvisioningService;
        this.authSessionService = authSessionService;
        this.workspaceAccessService = workspaceAccessService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> listWorkspaces(Jwt jwt) {
        User user = requireUser(jwt);
        return membershipRepository.findByUser_IdAndStatusOrderByLastAccessedAtDescJoinedAtAsc(
                        user.getId(),
                        MembershipStatus.ACTIVE
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public WorkspaceResponse createWorkspace(Jwt jwt, CreateWorkspaceRequest request) {
        User user = requireUser(jwt);
        WorkspaceMembership membership = workspaceProvisioningService.createOwnedWorkspace(user, request.name());
        return toResponse(membership);
    }

    @Transactional
    public AuthResponse switchWorkspace(Jwt jwt, UUID workspaceId, SwitchWorkspaceRequest request) {
        User user = requireUser(jwt);
        WorkspaceMembership membership = membershipRepository.findByWorkspace_IdAndUser_IdAndStatus(
                        workspaceId,
                        user.getId(),
                        MembershipStatus.ACTIVE
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

        return authSessionService.rotateTo(request.refreshToken(), user, membership);
    }

    @Transactional
    public WorkspaceMemberResponse updateMember(
            Jwt jwt,
            UUID memberId,
            UpdateWorkspaceMemberRequest request
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        requireCanManageMembers(context);
        WorkspaceMembership target = requireWorkspaceMember(context, memberId);
        requireCanManageTarget(context, target);

        if (request.role() == null && request.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role or status is required");
        }
        if (request.role() == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workspace ownership transfer is not supported");
        }
        if (request.status() == MembershipStatus.INVITED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVITED is not a member status");
        }
        if (context.membership().getRole() == WorkspaceRole.ADMIN
                && request.role() == WorkspaceRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an owner can assign admins");
        }

        WorkspaceRole nextRole = request.role() == null ? target.getRole() : request.role();
        if (request.status() == MembershipStatus.DISABLED) {
            target.disable();
            refreshTokenService.revokeMembershipSessions(target.getId());
        } else if (request.status() == MembershipStatus.ACTIVE
                && target.getStatus() != MembershipStatus.ACTIVE) {
            target.activate(nextRole);
        } else if (request.role() != null) {
            target.changeRole(nextRole);
        }
        return toMemberResponse(target);
    }

    @Transactional
    public void removeMember(Jwt jwt, UUID memberId) {
        updateMember(jwt, memberId, new UpdateWorkspaceMemberRequest(null, MembershipStatus.DISABLED));
    }

    private void requireCanManageMembers(CurrentWorkspaceContext context) {
        WorkspaceRole role = context.membership().getRole();
        if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace member permission is required");
        }
    }

    private WorkspaceMembership requireWorkspaceMember(CurrentWorkspaceContext context, UUID memberId) {
        WorkspaceMembership target = membershipRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace member not found"));
        if (!target.getWorkspace().getId().equals(context.workspace().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace member not found");
        }
        return target;
    }

    private void requireCanManageTarget(CurrentWorkspaceContext context, WorkspaceMembership target) {
        if (target.getId().equals(context.membership().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot disable or change your own membership");
        }
        if (target.getUser().getId().equals(context.workspace().getOwner().getId())
                || target.getRole() == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace owners cannot be changed or removed");
        }
        if (context.membership().getRole() == WorkspaceRole.ADMIN
                && target.getRole() == WorkspaceRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins cannot manage other admins");
        }
    }

    private User requireUser(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current user not found"));
    }

    private WorkspaceResponse toResponse(WorkspaceMembership membership) {
        Workspace workspace = membership.getWorkspace();
        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getSlug(),
                membership.getRole().name()
        );
    }

    private WorkspaceMemberResponse toMemberResponse(WorkspaceMembership membership) {
        return new WorkspaceMemberResponse(
                membership.getId(),
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                membership.getRole().name(),
                membership.getStatus().name(),
                membership.getJoinedAt()
        );
    }
}
