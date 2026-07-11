package com.vokyo.backend.workspace;

import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class WorkspaceAccessService {

    private static final String MEMBERSHIP_ID_CLAIM = "membershipId";

    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public WorkspaceAccessService(
            UserRepository userRepository,
            WorkspaceMembershipRepository membershipRepository
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(readOnly = true)
    public CurrentWorkspaceContext requireCurrentContext(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current user not found"));

        UUID membershipId = UUID.fromString(jwt.getClaimAsString(MEMBERSHIP_ID_CLAIM));
        WorkspaceMembership membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Current workspace membership not found"));

        if (!membership.getUser().getId().equals(userId) || membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current workspace membership is not active");
        }

        return new CurrentWorkspaceContext(user, membership.getWorkspace(), membership);
    }

    public void requireCanCreateProject(CurrentWorkspaceContext context) {
        if (context.membership().getRole() == WorkspaceRole.GUEST) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace role cannot create projects");
        }
    }

    public void requireCanManageInvitations(CurrentWorkspaceContext context) {
        WorkspaceRole role = context.membership().getRole();
        if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace invitation permission is required");
        }
    }

    public void requireCanManageInvitationRole(CurrentWorkspaceContext context, WorkspaceRole targetRole) {
        requireCanManageInvitations(context);
        WorkspaceRole actorRole = context.membership().getRole();

        if (targetRole == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OWNER role cannot be invited");
        }

        if (actorRole == WorkspaceRole.ADMIN && targetRole == WorkspaceRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a workspace owner can invite admins");
        }
    }
}
