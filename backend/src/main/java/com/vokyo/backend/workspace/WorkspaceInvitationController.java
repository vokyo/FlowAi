package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.workspace.dto.AcceptWorkspaceInvitationRequest;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationPreviewResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace-invitations")
public class WorkspaceInvitationController {

    private final WorkspaceInvitationService invitationService;

    public WorkspaceInvitationController(WorkspaceInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping("/{token}")
    public WorkspaceInvitationPreviewResponse previewInvitation(@PathVariable String token) {
        return invitationService.previewInvitation(token);
    }

    @PostMapping("/{token}/accept")
    public AuthResponse acceptInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String token,
            @Valid @RequestBody AcceptWorkspaceInvitationRequest request
    ) {
        return invitationService.acceptInvitation(jwt, token, request);
    }
}
