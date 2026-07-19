package com.vokyo.backend.project;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.project.dto.CreateProjectRequest;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.project.dto.UpdateProjectRequest;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import jakarta.persistence.EntityManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectCommandService {

    private static final int TODO_POSITION = 10_000;
    private static final int IN_PROGRESS_POSITION = 20_000;
    private static final int DONE_POSITION = 30_000;

    private final ProjectRepository projectRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActivityService activityService;
    private final EntityManager entityManager;
    private final ProjectMapper projectMapper;

    public ProjectCommandService(
            ProjectRepository projectRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            ActivityService activityService,
            EntityManager entityManager,
            ProjectMapper projectMapper
    ) {
        this.projectRepository = projectRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.projectAccessService = projectAccessService;
        this.workspaceAccessService = workspaceAccessService;
        this.activityService = activityService;
        this.entityManager = entityManager;
        this.projectMapper = projectMapper;
    }

    @Transactional
    public ProjectResponse createProject(Jwt jwt, CreateProjectRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        workspaceAccessService.requireCanCreateProject(context);
        Project project = projectRepository.save(new Project(
                context.workspace(),
                context.user(),
                request.name().trim(),
                normalizeOptionalText(request.description())
        ));

        projectAccessService.createOwnerMembership(project, context.user());
        createDefaultWorkflowStates(project);
        activityService.recordProjectCreated(project, context.user());
        return projectMapper.toResponse(project);
    }

    @Transactional
    public ProjectResponse updateProject(Jwt jwt, UUID projectId, UpdateProjectRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProjectForUpdate(projectId, context);
        project.rename(request.name().trim());
        project.changeDescription(normalizeOptionalText(request.description()));
        return projectMapper.toResponse(project);
    }

    @Transactional
    public ProjectResponse archiveProject(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProjectForUpdate(projectId, context);
        project.archive();
        return projectMapper.toResponse(project);
    }

    @Transactional
    public ProjectResponse restoreProject(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProjectForUpdate(projectId, context);
        project.restore();
        return projectMapper.toResponse(project);
    }

    @Transactional
    public void deleteProject(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        projectAccessService.requireOwnedProjectForUpdate(projectId, context);
        entityManager.flush();
        entityManager.clear();
        entityManager.createNativeQuery(
                        "delete from projects where id = :projectId and workspace_id = :workspaceId"
                )
                .setParameter("projectId", projectId)
                .setParameter("workspaceId", context.workspace().getId())
                .executeUpdate();
    }

    private void createDefaultWorkflowStates(Project project) {
        projectWorkflowStateRepository.saveAll(List.of(
                new ProjectWorkflowState(
                        project.getWorkspace(),
                        project,
                        "Todo",
                        WorkflowStateCategory.TODO,
                        TODO_POSITION
                ),
                new ProjectWorkflowState(
                        project.getWorkspace(),
                        project,
                        "In progress",
                        WorkflowStateCategory.IN_PROGRESS,
                        IN_PROGRESS_POSITION
                ),
                new ProjectWorkflowState(
                        project.getWorkspace(),
                        project,
                        "Done",
                        WorkflowStateCategory.DONE,
                        DONE_POSITION
                )
        ));
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
