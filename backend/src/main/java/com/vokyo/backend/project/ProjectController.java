package com.vokyo.backend.project;

import com.vokyo.backend.project.dto.CreateProjectRequest;
import com.vokyo.backend.project.dto.AddProjectMemberRequest;
import com.vokyo.backend.project.dto.CreateProjectLabelRequest;
import com.vokyo.backend.project.dto.CreateProjectWorkflowStateRequest;
import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectMemberResponse;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;
import com.vokyo.backend.project.dto.ReorderProjectWorkflowStatesRequest;
import com.vokyo.backend.project.dto.UpdateProjectWorkflowStateRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<ProjectResponse> listProjects(@AuthenticationPrincipal Jwt jwt) {
        return projectService.listProjects(jwt);
    }

    @PostMapping
    public ProjectResponse createProject(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        return projectService.createProject(jwt, request);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectService.getProject(jwt, projectId);
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> listProjectMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectService.listProjectMembers(jwt, projectId);
    }

    @PostMapping("/{projectId}/members")
    public ProjectMemberResponse addProjectMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody AddProjectMemberRequest request
    ) {
        return projectService.addProjectMember(jwt, projectId, request);
    }

    @GetMapping("/{projectId}/labels")
    public List<ProjectLabelResponse> listProjectLabels(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectService.listProjectLabels(jwt, projectId);
    }

    @PostMapping("/{projectId}/labels")
    public ProjectLabelResponse createProjectLabel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectLabelRequest request
    ) {
        return projectService.createProjectLabel(jwt, projectId, request);
    }

    @GetMapping("/{projectId}/workflow-states")
    public List<ProjectWorkflowStateResponse> listProjectWorkflowStates(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectService.listProjectWorkflowStates(jwt, projectId);
    }

    @PostMapping("/{projectId}/workflow-states")
    public ProjectWorkflowStateResponse createProjectWorkflowState(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectWorkflowStateRequest request
    ) {
        return projectService.createProjectWorkflowState(jwt, projectId, request);
    }

    @PatchMapping("/{projectId}/workflow-states/{workflowStateId}")
    public ProjectWorkflowStateResponse updateProjectWorkflowState(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID workflowStateId,
            @Valid @RequestBody UpdateProjectWorkflowStateRequest request
    ) {
        return projectService.updateProjectWorkflowState(jwt, projectId, workflowStateId, request);
    }

    @PatchMapping("/{projectId}/workflow-states/order")
    public List<ProjectWorkflowStateResponse> reorderProjectWorkflowStates(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody ReorderProjectWorkflowStatesRequest request
    ) {
        return projectService.reorderProjectWorkflowStates(jwt, projectId, request);
    }
}
