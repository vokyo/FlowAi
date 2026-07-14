package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.AuthSessionResult;
import com.vokyo.backend.auth.RefreshTokenCookieService;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationPreviewResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace-invitations")
public class WorkspaceInvitationController {

    private final WorkspaceInvitationService invitationService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    public WorkspaceInvitationController(
            WorkspaceInvitationService invitationService,
            RefreshTokenCookieService refreshTokenCookieService
    ) {
        this.invitationService = invitationService;
        this.refreshTokenCookieService = refreshTokenCookieService;
    }

    @GetMapping("/{token}")
    public WorkspaceInvitationPreviewResponse previewInvitation(@PathVariable String token) {
        return invitationService.previewInvitation(token);
    }

    @PostMapping("/{token}/accept")
    public AuthResponse acceptInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String token,
            @CookieValue(name = RefreshTokenCookieService.COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        AuthSessionResult session = invitationService.acceptInvitation(
                jwt,
                token,
                refreshTokenCookieService.require(refreshToken)
        );
        refreshTokenCookieService.write(response, session.refreshToken());
        return session.response();
    }
}
