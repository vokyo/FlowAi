package com.vokyo.backend.auth;

import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthSessionService {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthSessionService(JwtService jwtService, RefreshTokenService refreshTokenService) {
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthSessionResult issue(User user, WorkspaceMembership membership) {
        requireActiveMembership(user, membership);
        membership.markAccessed();
        return issueTokens(user, membership);
    }

    @Transactional
    public AuthSessionResult refresh(String plainRefreshToken) {
        RefreshToken refreshToken = refreshTokenService.requireActiveTokenForUpdate(plainRefreshToken);
        User user = refreshToken.getUser();
        WorkspaceMembership membership = refreshToken.getWorkspaceMembership();
        requireActiveMembership(user, membership);

        refreshToken.revoke();
        membership.markAccessed();
        return issueTokens(user, membership);
    }

    @Transactional
    public AuthSessionResult rotateTo(
            String plainRefreshToken,
            User expectedUser,
            WorkspaceMembership targetMembership
    ) {
        RefreshToken refreshToken = refreshTokenService.requireActiveTokenForUpdate(plainRefreshToken);
        if (!refreshToken.getUser().getId().equals(expectedUser.getId())) {
            throw unauthorized("Invalid refresh token");
        }

        requireActiveMembership(expectedUser, targetMembership);
        refreshToken.revoke();
        targetMembership.markAccessed();
        return issueTokens(expectedUser, targetMembership);
    }

    private AuthSessionResult issueTokens(User user, WorkspaceMembership membership) {
        String accessToken = jwtService.generateAccessToken(user, membership);
        String refreshToken = refreshTokenService.createRefreshToken(user, membership);
        Workspace workspace = membership.getWorkspace();

        return new AuthSessionResult(
                new AuthResponse(
                        accessToken,
                        new UserResponse(user.getId(), user.getEmail(), user.getDisplayName()),
                        new WorkspaceResponse(
                                workspace.getId(),
                                workspace.getName(),
                                workspace.getSlug(),
                                membership.getRole().name()
                        )
                ),
                refreshToken
        );
    }

    private void requireActiveMembership(User user, WorkspaceMembership membership) {
        if (!membership.getUser().getId().equals(user.getId())
                || membership.getStatus() != MembershipStatus.ACTIVE) {
            throw unauthorized("Workspace membership is not active");
        }
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
