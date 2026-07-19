package com.vokyo.backend.project;

import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectMemberResponse;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectQueryService {

    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectMapper projectMapper;

    public ProjectQueryService(
            ProjectLabelRepository projectLabelRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            ProjectMapper projectMapper
    ) {
        this.projectLabelRepository = projectLabelRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.projectAccessService = projectAccessService;
        this.workspaceAccessService = workspaceAccessService;
        this.projectMapper = projectMapper;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects(Jwt jwt, boolean includeArchived) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        return projectAccessService.listAccessibleProjects(context)
                .stream()
                .filter(project -> includeArchived || project.getArchivedAt() == null)
                .map(projectMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        return projectMapper.toResponse(
                projectAccessService.requireAccessibleProject(projectId, context)
        );
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listProjectMembers(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        return projectAccessService.listProjectMembers(project)
                .stream()
                .map(projectMapper::toMemberResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectLabelResponse> listProjectLabels(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        return projectLabelRepository.findByWorkspace_IdAndProject_IdOrderByNameAsc(
                        project.getWorkspace().getId(),
                        project.getId()
                )
                .stream()
                .map(projectMapper::toLabelResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectWorkflowStateResponse> listProjectWorkflowStates(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        return projectWorkflowStateRepository.findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc(
                        project.getWorkspace().getId(),
                        project.getId()
                )
                .stream()
                .map(projectMapper::toWorkflowStateResponse)
                .toList();
    }
}
