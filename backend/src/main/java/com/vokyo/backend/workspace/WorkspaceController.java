package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.workspace.dto.CreateWorkspaceRequest;
import com.vokyo.backend.workspace.dto.SwitchWorkspaceRequest;
import com.vokyo.backend.workspace.dto.WorkspaceMemberResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceQueryService workspaceQueryService;
    private final WorkspaceService workspaceService;

    public WorkspaceController(
            WorkspaceQueryService workspaceQueryService,
            WorkspaceService workspaceService
    ) {
        this.workspaceQueryService = workspaceQueryService;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<WorkspaceResponse> listWorkspaces(@AuthenticationPrincipal Jwt jwt) {
        return workspaceService.listWorkspaces(jwt);
    }

    @PostMapping
    public WorkspaceResponse createWorkspace(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        return workspaceService.createWorkspace(jwt, request);
    }

    @PostMapping("/{workspaceId}/switch")
    public AuthResponse switchWorkspace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody SwitchWorkspaceRequest request
    ) {
        return workspaceService.switchWorkspace(jwt, workspaceId, request);
    }

    @GetMapping("/current")
    public WorkspaceResponse currentWorkspace(@AuthenticationPrincipal Jwt jwt) {
        return workspaceQueryService.getCurrentWorkspace(jwt);
    }

    @GetMapping("/current/members")
    public List<WorkspaceMemberResponse> currentWorkspaceMembers(@AuthenticationPrincipal Jwt jwt) {
        return workspaceQueryService.getCurrentWorkspaceMembers(jwt);
    }
}
