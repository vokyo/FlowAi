package com.vokyo.backend.project;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.project.dto.AddProjectMemberRequest;
import com.vokyo.backend.project.dto.CreateProjectRequest;
import com.vokyo.backend.project.dto.ProjectMemberResponse;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final ActivityService activityService;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            WorkspaceMembershipRepository workspaceMembershipRepository,
            ActivityService activityService
    ) {
        this.projectRepository = projectRepository;
        this.projectAccessService = projectAccessService;
        this.workspaceAccessService = workspaceAccessService;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects(Jwt jwt) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        return projectAccessService.listAccessibleProjects(context)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        return toResponse(projectAccessService.requireAccessibleProject(projectId, context));
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

        projectAccessService.createOwnerMembership(project, context.user());
        activityService.recordProjectCreated(project, context.user());
        return toResponse(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listProjectMembers(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        return projectAccessService.listProjectMembers(project)
                .stream()
                .map(this::toMemberResponse)
                .toList();
    }

    @Transactional
    public ProjectMemberResponse addProjectMember(Jwt jwt, UUID projectId, AddProjectMemberRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProject(projectId, context);

        if (request.role() != ProjectRole.MEMBER) {
            throw badRequest("Only MEMBER role can be added");
        }

        WorkspaceMembership targetMembership = workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(context.workspace().getId(), request.userId())
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> notFound("Workspace member not found"));

        if (projectAccessService.hasProjectMembership(project, targetMembership.getUser())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project member already exists");
        }

        ProjectMember member = projectAccessService.createMemberMembership(project, targetMembership.getUser());
        return toMemberResponse(member);
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

    private ProjectMemberResponse toMemberResponse(ProjectMember member) {
        return new ProjectMemberResponse(
                member.getId(),
                member.getUser().getId(),
                member.getUser().getEmail(),
                member.getUser().getDisplayName(),
                member.getRole().name(),
                member.getStatus().name(),
                member.getJoinedAt()
        );
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
