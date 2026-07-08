package com.vokyo.backend.project;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.project.dto.CreateProjectRequest;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActivityService activityService;

    public ProjectService(
            ProjectRepository projectRepository,
            WorkspaceAccessService workspaceAccessService,
            ActivityService activityService
    ) {
        this.projectRepository = projectRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects(Jwt jwt) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        return projectRepository.findByWorkspace_IdOrderByCreatedAtAsc(context.workspace().getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse createProject(Jwt jwt, CreateProjectRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectRepository.save(new Project(
                context.workspace(),
                context.user(),
                request.name().trim(),
                normalizeOptionalText(request.description())
        ));

        activityService.recordProjectCreated(project, context.user());
        return toResponse(project);
    }

    private ProjectResponse toResponse(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
