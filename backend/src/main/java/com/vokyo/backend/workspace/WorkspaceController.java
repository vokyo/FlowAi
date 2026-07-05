package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.workspace.dto.WorkspaceMemberResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceQueryService workspaceQueryService;

    public WorkspaceController(WorkspaceQueryService workspaceQueryService) {
        this.workspaceQueryService = workspaceQueryService;
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
