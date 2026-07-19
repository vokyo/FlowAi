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
import com.vokyo.backend.project.dto.UpdateProjectMemberRequest;
import com.vokyo.backend.project.dto.UpdateProjectRequest;
import com.vokyo.backend.project.dto.UpdateProjectLabelRequest;
import com.vokyo.backend.project.dto.DeleteProjectWorkflowStateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectQueryService projectQueryService;
    private final ProjectCommandService projectCommandService;
    private final ProjectConfigurationService projectConfigurationService;

    public ProjectController(
            ProjectQueryService projectQueryService,
            ProjectCommandService projectCommandService,
            ProjectConfigurationService projectConfigurationService
    ) {
        this.projectQueryService = projectQueryService;
        this.projectCommandService = projectCommandService;
        this.projectConfigurationService = projectConfigurationService;
    }

    @GetMapping
    public List<ProjectResponse> listProjects(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean includeArchived
    ) {
        return projectQueryService.listProjects(jwt, includeArchived);
    }

    @PostMapping
    public ProjectResponse createProject(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        return projectCommandService.createProject(jwt, request);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectQueryService.getProject(jwt, projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse updateProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        return projectCommandService.updateProject(jwt, projectId, request);
    }

    @PostMapping("/{projectId}/archive")
    public ProjectResponse archiveProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectCommandService.archiveProject(jwt, projectId);
    }

    @PostMapping("/{projectId}/restore")
    public ProjectResponse restoreProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectCommandService.restoreProject(jwt, projectId);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        projectCommandService.deleteProject(jwt, projectId);
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> listProjectMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectQueryService.listProjectMembers(jwt, projectId);
    }

    @PostMapping("/{projectId}/members")
    public ProjectMemberResponse addProjectMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody AddProjectMemberRequest request
    ) {
        return projectConfigurationService.addProjectMember(jwt, projectId, request);
    }

    @PatchMapping("/{projectId}/members/{memberId}")
    public ProjectMemberResponse updateProjectMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateProjectMemberRequest request
    ) {
        return projectConfigurationService.updateProjectMember(jwt, projectId, memberId, request);
    }

    @DeleteMapping("/{projectId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeProjectMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID memberId
    ) {
        projectConfigurationService.removeProjectMember(jwt, projectId, memberId);
    }

    @GetMapping("/{projectId}/labels")
    public List<ProjectLabelResponse> listProjectLabels(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectQueryService.listProjectLabels(jwt, projectId);
    }

    @PostMapping("/{projectId}/labels")
    public ProjectLabelResponse createProjectLabel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectLabelRequest request
    ) {
        return projectConfigurationService.createProjectLabel(jwt, projectId, request);
    }

    @PatchMapping("/{projectId}/labels/{labelId}")
    public ProjectLabelResponse updateProjectLabel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID labelId,
            @Valid @RequestBody UpdateProjectLabelRequest request
    ) {
        return projectConfigurationService.updateProjectLabel(jwt, projectId, labelId, request);
    }

    @DeleteMapping("/{projectId}/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProjectLabel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID labelId
    ) {
        projectConfigurationService.deleteProjectLabel(jwt, projectId, labelId);
    }

    @GetMapping("/{projectId}/workflow-states")
    public List<ProjectWorkflowStateResponse> listProjectWorkflowStates(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId
    ) {
        return projectQueryService.listProjectWorkflowStates(jwt, projectId);
    }

    @PostMapping("/{projectId}/workflow-states")
    public ProjectWorkflowStateResponse createProjectWorkflowState(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectWorkflowStateRequest request
    ) {
        return projectConfigurationService.createProjectWorkflowState(jwt, projectId, request);
    }

    @PatchMapping("/{projectId}/workflow-states/{workflowStateId}")
    public ProjectWorkflowStateResponse updateProjectWorkflowState(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID workflowStateId,
            @Valid @RequestBody UpdateProjectWorkflowStateRequest request
    ) {
        return projectConfigurationService.updateProjectWorkflowState(
                jwt,
                projectId,
                workflowStateId,
                request
        );
    }

    @PatchMapping("/{projectId}/workflow-states/order")
    public List<ProjectWorkflowStateResponse> reorderProjectWorkflowStates(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody ReorderProjectWorkflowStatesRequest request
    ) {
        return projectConfigurationService.reorderProjectWorkflowStates(jwt, projectId, request);
    }

    @DeleteMapping("/{projectId}/workflow-states/{workflowStateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProjectWorkflowState(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID workflowStateId,
            @Valid @RequestBody DeleteProjectWorkflowStateRequest request
    ) {
        projectConfigurationService.deleteProjectWorkflowState(
                jwt,
                projectId,
                workflowStateId,
                request
        );
    }
}
