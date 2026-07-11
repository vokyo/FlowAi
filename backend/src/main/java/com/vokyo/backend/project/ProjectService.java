package com.vokyo.backend.project;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.dto.AddProjectMemberRequest;
import com.vokyo.backend.project.dto.CreateProjectLabelRequest;
import com.vokyo.backend.project.dto.CreateProjectRequest;
import com.vokyo.backend.project.dto.CreateProjectWorkflowStateRequest;
import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectMemberResponse;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;
import com.vokyo.backend.project.dto.ReorderProjectWorkflowStatesRequest;
import com.vokyo.backend.project.dto.UpdateProjectWorkflowStateRequest;
import com.vokyo.backend.project.dto.UpdateProjectMemberRequest;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectService {

    private static final String DEFAULT_LABEL_COLOR = "#64748b";
    private static final int TODO_POSITION = 10_000;
    private static final int IN_PROGRESS_POSITION = 20_000;
    private static final int DONE_POSITION = 30_000;

    private final ProjectRepository projectRepository;
    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final IssueRepository issueRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final ActivityService activityService;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectLabelRepository projectLabelRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            IssueRepository issueRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            WorkspaceMembershipRepository workspaceMembershipRepository,
            ActivityService activityService
    ) {
        this.projectRepository = projectRepository;
        this.projectLabelRepository = projectLabelRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.issueRepository = issueRepository;
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

    @Transactional(readOnly = true)
    public List<ProjectLabelResponse> listProjectLabels(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);

        return projectLabelRepository.findByWorkspace_IdAndProject_IdOrderByNameAsc(
                        project.getWorkspace().getId(),
                        project.getId()
                )
                .stream()
                .map(this::toLabelResponse)
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
                .map(this::toWorkflowStateResponse)
                .toList();
    }

    @Transactional
    public ProjectLabelResponse createProjectLabel(Jwt jwt, UUID projectId, CreateProjectLabelRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        String name = request.name().trim();

        if (projectLabelRepository.existsByWorkspace_IdAndProject_IdAndNameIgnoreCase(
                project.getWorkspace().getId(),
                project.getId(),
                name
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project label already exists");
        }

        ProjectLabel label = projectLabelRepository.save(new ProjectLabel(
                project.getWorkspace(),
                project,
                name,
                normalizeLabelColor(request.color())
        ));

        return toLabelResponse(label);
    }

    @Transactional
    public ProjectWorkflowStateResponse createProjectWorkflowState(
            Jwt jwt,
            UUID projectId,
            CreateProjectWorkflowStateRequest request
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProject(projectId, context);
        String name = request.name().trim();

        if (projectWorkflowStateRepository.existsByWorkspace_IdAndProject_IdAndNameIgnoreCase(
                project.getWorkspace().getId(),
                project.getId(),
                name
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project workflow state already exists");
        }

        ProjectWorkflowState workflowState = projectWorkflowStateRepository.save(new ProjectWorkflowState(
                project.getWorkspace(),
                project,
                name,
                request.category(),
                nextWorkflowStatePosition(project)
        ));

        return toWorkflowStateResponse(workflowState);
    }

    @Transactional
    public ProjectWorkflowStateResponse updateProjectWorkflowState(
            Jwt jwt,
            UUID projectId,
            UUID workflowStateId,
            UpdateProjectWorkflowStateRequest request
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProjectForUpdate(projectId, context);
        ProjectWorkflowState workflowState = requireProjectWorkflowState(project, workflowStateId);
        String name = request.name().trim();
        WorkflowStateCategory previousCategory = workflowState.getCategory();

        if (projectWorkflowStateRepository.existsByWorkspace_IdAndProject_IdAndNameIgnoreCaseAndIdNot(
                project.getWorkspace().getId(),
                project.getId(),
                name,
                workflowState.getId()
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project workflow state already exists");
        }

        workflowState.rename(name);
        workflowState.changeCategory(request.category());

        if (previousCategory != request.category()) {
            synchronizeWorkflowStateIssueCompletion(project, workflowState, previousCategory);
        }
        return toWorkflowStateResponse(workflowState);
    }

    @Transactional
    public List<ProjectWorkflowStateResponse> reorderProjectWorkflowStates(
            Jwt jwt,
            UUID projectId,
            ReorderProjectWorkflowStatesRequest request
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProject(projectId, context);
        List<UUID> requestedIds = request.workflowStateIds();
        if (requestedIds == null || requestedIds.isEmpty()) {
            throw badRequest("Workflow state order is required");
        }

        Set<UUID> uniqueRequestedIds = new HashSet<>(requestedIds);
        if (uniqueRequestedIds.size() != requestedIds.size()) {
            throw badRequest("Workflow state order cannot contain duplicates");
        }

        List<ProjectWorkflowState> workflowStates =
                projectWorkflowStateRepository.findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc(
                        project.getWorkspace().getId(),
                        project.getId()
                );
        Map<UUID, ProjectWorkflowState> workflowStatesById = new LinkedHashMap<>();
        for (ProjectWorkflowState workflowState : workflowStates) {
            workflowStatesById.put(workflowState.getId(), workflowState);
        }

        for (UUID requestedId : requestedIds) {
            if (!workflowStatesById.containsKey(requestedId)) {
                throw notFound("Project workflow state not found");
            }
        }

        if (requestedIds.size() != workflowStates.size()) {
            throw badRequest("Workflow state order must include every state");
        }

        for (int index = 0; index < requestedIds.size(); index++) {
            workflowStatesById.get(requestedIds.get(index)).moveTo((index + 1) * 10_000);
        }

        projectWorkflowStateRepository.saveAll(workflowStates);
        return requestedIds
                .stream()
                .map(workflowStatesById::get)
                .map(this::toWorkflowStateResponse)
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

        var existingMembership = projectAccessService.findProjectMembership(project, targetMembership.getUser());
        if (existingMembership.isPresent()) {
            ProjectMember member = existingMembership.get();
            if (member.getStatus() != MembershipStatus.DISABLED) {
                throw conflict("Project member already exists");
            }

            member.reactivate(ProjectRole.MEMBER);
            return toMemberResponse(member);
        }

        ProjectMember member = projectAccessService.createMemberMembership(project, targetMembership.getUser());
        return toMemberResponse(member);
    }

    @Transactional
    public ProjectMemberResponse updateProjectMember(
            Jwt jwt,
            UUID projectId,
            UUID memberId,
            UpdateProjectMemberRequest request
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProjectForUpdate(projectId, context);
        ProjectMember member = projectAccessService.requireActiveProjectMember(project, memberId);

        if (member.getRole() == request.role()) {
            return toMemberResponse(member);
        }

        if (member.getRole() == ProjectRole.OWNER
                && request.role() == ProjectRole.MEMBER
                && projectAccessService.countActiveProjectOwners(project) <= 1) {
            throw conflict("A project must have at least one active owner");
        }

        member.changeRole(request.role());
        return toMemberResponse(member);
    }

    @Transactional
    public void removeProjectMember(Jwt jwt, UUID projectId, UUID memberId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireOwnedProjectForUpdate(projectId, context);
        ProjectMember member = projectAccessService.requireProjectMember(project, memberId);

        if (member.getStatus() == MembershipStatus.DISABLED) {
            return;
        }

        if (member.getRole() == ProjectRole.OWNER
                && projectAccessService.countActiveProjectOwners(project) <= 1) {
            throw conflict("A project must have at least one active owner");
        }

        member.disable();
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

    private ProjectLabelResponse toLabelResponse(ProjectLabel label) {
        return new ProjectLabelResponse(
                label.getId(),
                label.getProject().getId(),
                label.getName(),
                label.getColor(),
                label.getCreatedAt(),
                label.getUpdatedAt()
        );
    }

    private ProjectWorkflowStateResponse toWorkflowStateResponse(ProjectWorkflowState workflowState) {
        return new ProjectWorkflowStateResponse(
                workflowState.getId(),
                workflowState.getProject().getId(),
                workflowState.getName(),
                workflowState.getCategory().name(),
                workflowState.getPosition(),
                workflowState.getCreatedAt(),
                workflowState.getUpdatedAt()
        );
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

    private int nextWorkflowStatePosition(Project project) {
        List<ProjectWorkflowState> workflowStates =
                projectWorkflowStateRepository.findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc(
                        project.getWorkspace().getId(),
                        project.getId()
                );
        int donePosition = workflowStates
                .stream()
                .filter(workflowState -> workflowState.getCategory() == WorkflowStateCategory.DONE)
                .mapToInt(ProjectWorkflowState::getPosition)
                .findFirst()
                .orElse(DONE_POSITION);
        int maxBeforeDone = workflowStates
                .stream()
                .filter(workflowState -> workflowState.getPosition() < donePosition)
                .mapToInt(ProjectWorkflowState::getPosition)
                .max()
                .orElse(IN_PROGRESS_POSITION);

        return Math.min(maxBeforeDone + 1_000, donePosition - 1);
    }

    private ProjectWorkflowState requireProjectWorkflowState(Project project, UUID workflowStateId) {
        return projectWorkflowStateRepository.findByWorkspace_IdAndProject_IdAndId(
                        project.getWorkspace().getId(),
                        project.getId(),
                        workflowStateId
                )
                .orElseThrow(() -> notFound("Project workflow state not found"));
    }

    private void synchronizeWorkflowStateIssueCompletion(
            Project project,
            ProjectWorkflowState workflowState,
            WorkflowStateCategory previousCategory
    ) {
        if (workflowState.getCategory() == WorkflowStateCategory.DONE) {
            issueRepository.markActiveWorkflowStateIssuesCompleted(
                    project.getWorkspace().getId(),
                    project.getId(),
                    workflowState.getId(),
                    Instant.now()
            );
            return;
        }

        if (previousCategory == WorkflowStateCategory.DONE) {
            issueRepository.clearWorkflowStateIssueCompletion(
                    project.getWorkspace().getId(),
                    project.getId(),
                    workflowState.getId()
            );
        }
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String normalizeLabelColor(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LABEL_COLOR;
        }

        return value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
