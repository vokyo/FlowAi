package com.vokyo.backend.me;

import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.me.dto.MeResponse;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class MeService {

    private static final String MEMBERSHIP_ID_CLAIM = "membershipId";

    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public MeService(
            UserRepository userRepository,
            WorkspaceMembershipRepository membershipRepository
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(readOnly = true)
    public MeResponse getCurrentSession(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current user not found"));

        UUID membershipId = UUID.fromString(jwt.getClaimAsString(MEMBERSHIP_ID_CLAIM));
        WorkspaceMembership membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Current workspace membership not found"));

        if (!membership.getUser().getId().equals(userId) || membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current workspace membership is not active");
        }

        Workspace workspace = membership.getWorkspace();

        return new MeResponse(
                new UserResponse(user.getId(), user.getEmail(), user.getDisplayName()),
                new WorkspaceResponse(
                        workspace.getId(),
                        workspace.getName(),
                        workspace.getSlug(),
                        membership.getRole().name()
                )
        );
    }
}
