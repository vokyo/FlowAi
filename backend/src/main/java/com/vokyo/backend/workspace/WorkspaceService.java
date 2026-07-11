package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.AuthSessionService;
import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.dto.CreateWorkspaceRequest;
import com.vokyo.backend.workspace.dto.SwitchWorkspaceRequest;
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

    public WorkspaceService(
            UserRepository userRepository,
            WorkspaceMembershipRepository membershipRepository,
            WorkspaceProvisioningService workspaceProvisioningService,
            AuthSessionService authSessionService
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceProvisioningService = workspaceProvisioningService;
        this.authSessionService = authSessionService;
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
}
