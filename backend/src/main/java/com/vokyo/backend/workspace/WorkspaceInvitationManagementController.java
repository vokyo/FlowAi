package com.vokyo.backend.workspace;

import com.vokyo.backend.workspace.dto.CreateWorkspaceInvitationRequest;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationCreatedResponse;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/current/invitations")
public class WorkspaceInvitationManagementController {

    private final WorkspaceInvitationService invitationService;

    public WorkspaceInvitationManagementController(WorkspaceInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping
    public List<WorkspaceInvitationResponse> listInvitations(@AuthenticationPrincipal Jwt jwt) {
        return invitationService.listInvitations(jwt);
    }

    @PostMapping
    public WorkspaceInvitationCreatedResponse createInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateWorkspaceInvitationRequest request
    ) {
        return invitationService.createInvitation(jwt, request);
    }

    @PostMapping("/{invitationId}/reissue")
    public WorkspaceInvitationCreatedResponse reissueInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID invitationId
    ) {
        return invitationService.reissueInvitation(jwt, invitationId);
    }

    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID invitationId
    ) {
        invitationService.revokeInvitation(jwt, invitationId);
    }
}
