package com.vokyo.backend.agent;

import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AgentAccessService {
    private static final String WORKSPACE_ID_CLAIM = "workspaceId";
    private static final String PROJECT_ID_CLAIM = "projectId";

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;

    public AgentAccessService(WorkspaceAccessService workspaceAccessService, ProjectAccessService projectAccessService) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public Project requireAccessibleProject(Jwt agentJwt) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(agentJwt);

        UUID workspaceId = UUID.fromString(agentJwt.getClaimAsString(WORKSPACE_ID_CLAIM));
        if (!context.workspace().getId().equals(workspaceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent token workspace does not match the membership");
        }

        UUID projectId = UUID.fromString(agentJwt.getClaimAsString(PROJECT_ID_CLAIM));
        return projectAccessService.requireAccessibleProject(projectId, context);
    }
}
