package com.vokyo.backend.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.activity.dto.ActivityEventResponse;
import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.issue.dto.BoardColumnResponse;
import com.vokyo.backend.issue.dto.CreateCommentRequest;
import com.vokyo.backend.issue.dto.CreateIssueRequest;
import com.vokyo.backend.issue.dto.IssueCommentResponse;
import com.vokyo.backend.issue.dto.IssueDetailResponse;
import com.vokyo.backend.issue.dto.IssueSummaryResponse;
import com.vokyo.backend.issue.dto.MoveIssueStateRequest;
import com.vokyo.backend.issue.dto.ProjectBoardResponse;
import com.vokyo.backend.issue.dto.ReorderIssuesRequest;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IssueService {

    private static final int MAX_TITLE_LENGTH = 240;
    private static final int MAX_DESCRIPTION_LENGTH = 10000;
    private static final long BOARD_POSITION_STEP = 10_000L;

    private final IssueRepository issueRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActivityService activityService;

    public IssueService(
            IssueRepository issueRepository,
            IssueCommentRepository issueCommentRepository,
            ProjectLabelRepository projectLabelRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            ActivityService activityService
    ) {
        this.issueRepository = issueRepository;
        this.issueCommentRepository = issueCommentRepository;
        this.projectLabelRepository = projectLabelRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.projectAccessService = projectAccessService;
        this.workspaceAccessService = workspaceAccessService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public List<IssueSummaryResponse> listIssues(
            Jwt jwt,
            UUID projectId,
            IssueStatus status,
            UUID workflowStateId,
            IssuePriority priority,
            UUID assigneeUserId,
            UUID labelId,
            String query
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        projectAccessService.requireAccessibleProject(projectId, context);
        String normalizedQuery = normalizeOptionalText(query);

        List<Issue> issues = issueRepository.findAll(
                issueListSpecification(
                        context.workspace().getId(),
                        projectId,
                        status,
                        workflowStateId,
                        priority,
                        assigneeUserId,
                        labelId,
                        normalizedQuery
                ),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Map<UUID, Long> commentCounts = loadCommentCounts(issues);

        return issues
                .stream()
                .map(issue -> toSummaryResponse(issue, commentCounts.getOrDefault(issue.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectBoardResponse getBoard(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        return loadBoard(project);
    }

    @Transactional
    public IssueSummaryResponse createIssue(Jwt jwt, CreateIssueRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProjectForUpdate(request.projectId(), context);
        if (request.status() == IssueStatus.ARCHIVED) {
            throw badRequest("Issue cannot be created as archived");
        }
        User assignee = resolveAssignee(project, request.assigneeUserId());
        List<ProjectLabel> labels = resolveProjectLabels(project, request.labelIds());
        ProjectWorkflowState workflowState = resolveWorkflowStateForCreate(project, request.workflowStateId(), request.status());
        long boardPosition = nextBoardPosition(project, workflowState);

        Issue issue = issueRepository.save(new Issue(
                context.workspace(),
                project,
                context.user(),
                request.title().trim(),
                normalizeOptionalText(request.description()),
                assignee,
                workflowState,
                request.priority(),
                request.dueDate(),
                boardPosition
        ));
        issue.replaceLabels(labels);

        activityService.recordIssueCreated(issue, context.user());
        return toSummaryResponse(issue, 0L);
    }

    @Transactional
    public IssueSummaryResponse moveIssueState(Jwt jwt, UUID issueId, MoveIssueStateRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = requireIssueProjectForUpdate(issueId, context);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        if (issue.getArchivedAt() != null) {
            throw conflict("Archived issue cannot be moved");
        }

        ProjectWorkflowState targetWorkflowState = requireProjectWorkflowState(project, request.workflowStateId());
        ProjectWorkflowState previousWorkflowState = issue.getWorkflowState();
        if (previousWorkflowState.getId().equals(targetWorkflowState.getId())) {
            return toSummaryResponse(issue, loadCommentCount(issue));
        }

        String previousStatus = displayStatus(issue);
        long boardPosition = nextBoardPosition(project, targetWorkflowState);
        issue.changeWorkflowState(targetWorkflowState);
        issue.moveOnBoard(boardPosition);
        activityService.recordIssueStatusChanged(
                issue,
                context.user(),
                previousStatus,
                displayStatus(issue),
                previousWorkflowState.getId(),
                targetWorkflowState.getId()
        );

        return toSummaryResponse(issue, loadCommentCount(issue));
    }

    @Transactional
    public ProjectBoardResponse reorderIssues(Jwt jwt, ReorderIssuesRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = requireIssueProjectForUpdate(request.issueId(), context);
        Issue movedIssue = requireIssue(request.issueId(), context.workspace().getId());
        if (movedIssue.getArchivedAt() != null) {
            throw conflict("Archived issue cannot be reordered");
        }

        ProjectWorkflowState targetWorkflowState = requireProjectWorkflowState(project, request.workflowStateId());
        List<UUID> orderedIssueIds = request.orderedIssueIds();
        LinkedHashSet<UUID> requestedIssueIds = new LinkedHashSet<>(orderedIssueIds);
        if (requestedIssueIds.size() != orderedIssueIds.size()) {
            throw badRequest("Issue order cannot contain duplicates");
        }

        List<Issue> requestedIssues = issueRepository.findByWorkspace_IdAndProject_IdAndIdIn(
                project.getWorkspace().getId(),
                project.getId(),
                requestedIssueIds
        );
        if (requestedIssues.size() != requestedIssueIds.size()) {
            throw notFound("Issue not found");
        }
        if (requestedIssues.stream().anyMatch(issue -> issue.getArchivedAt() != null)) {
            throw badRequest("Archived issues cannot be reordered");
        }

        LinkedHashSet<UUID> expectedIssueIds = issueRepository.findActiveIssuesInWorkflowState(
                        project.getWorkspace().getId(),
                        project.getId(),
                        targetWorkflowState.getId()
                )
                .stream()
                .map(Issue::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        expectedIssueIds.add(movedIssue.getId());
        if (!requestedIssueIds.equals(expectedIssueIds)) {
            throw badRequest("Issue order must include every issue in the target workflow state");
        }

        Map<UUID, Issue> requestedIssuesById = requestedIssues
                .stream()
                .collect(Collectors.toMap(Issue::getId, issue -> issue));
        ProjectWorkflowState previousWorkflowState = movedIssue.getWorkflowState();
        String previousStatus = displayStatus(movedIssue);
        boolean workflowStateChanged = !previousWorkflowState.getId().equals(targetWorkflowState.getId());
        if (workflowStateChanged) {
            movedIssue.changeWorkflowState(targetWorkflowState);
        }

        List<Issue> orderedIssues = new ArrayList<>(orderedIssueIds.size());
        for (int index = 0; index < orderedIssueIds.size(); index++) {
            Issue issue = requestedIssuesById.get(orderedIssueIds.get(index));
            issue.moveOnBoard((index + 1L) * BOARD_POSITION_STEP);
            orderedIssues.add(issue);
        }
        issueRepository.saveAll(orderedIssues);

        if (workflowStateChanged) {
            activityService.recordIssueStatusChanged(
                    movedIssue,
                    context.user(),
                    previousStatus,
                    displayStatus(movedIssue),
                    previousWorkflowState.getId(),
                    targetWorkflowState.getId()
            );
        }

        return loadBoard(project);
    }

    @Transactional(readOnly = true)
    public IssueDetailResponse getIssue(Jwt jwt, UUID issueId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        List<IssueCommentResponse> comments = issueCommentRepository.findByIssue_IdOrderByCreatedAtAsc(issue.getId())
                .stream()
                .map(this::toCommentResponse)
                .toList();

        return toDetailResponse(issue, comments);
    }

    @Transactional
    public IssueCommentResponse createComment(Jwt jwt, UUID issueId, CreateCommentRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        IssueComment comment = issueCommentRepository.save(new IssueComment(
                context.workspace(),
                issue.getProject(),
                issue,
                context.user(),
                request.body().trim()
        ));

        activityService.recordCommentCreated(comment, context.user());
        return toCommentResponse(comment);
    }

    @Transactional(readOnly = true)
    public List<ActivityEventResponse> listIssueActivities(Jwt jwt, UUID issueId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        return activityService.listIssueActivities(issue.getId(), context.workspace().getId());
    }

    private Issue requireIssue(UUID issueId, UUID workspaceId) {
        return issueRepository.findByIdAndWorkspace_Id(issueId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found"));
    }

    private Project requireIssueProjectForUpdate(UUID issueId, CurrentWorkspaceContext context) {
        UUID projectId = issueRepository.findProjectIdByIdAndWorkspaceId(
                        issueId,
                        context.workspace().getId()
                )
                .orElseThrow(() -> notFound("Issue not found"));
        return projectAccessService.requireAccessibleProjectForUpdate(projectId, context);
    }

    private Specification<Issue> issueListSpecification(
            UUID workspaceId,
            UUID projectId,
            IssueStatus status,
            UUID workflowStateId,
            IssuePriority priority,
            UUID assigneeUserId,
            UUID labelId,
            String query
    ) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("workspace").get("id"), workspaceId));
            predicates.add(criteriaBuilder.equal(root.get("project").get("id"), projectId));

            if (status == null) {
                predicates.add(criteriaBuilder.isNull(root.get("archivedAt")));
            } else if (status == IssueStatus.ARCHIVED) {
                predicates.add(criteriaBuilder.isNotNull(root.get("archivedAt")));
            } else {
                predicates.add(criteriaBuilder.isNull(root.get("archivedAt")));
                predicates.add(criteriaBuilder.equal(root.get("workflowState").get("category"), categoryFromStatus(status)));
            }

            if (workflowStateId != null) {
                predicates.add(criteriaBuilder.equal(root.get("workflowState").get("id"), workflowStateId));
            }

            if (priority != null) {
                predicates.add(criteriaBuilder.equal(root.get("priority"), priority));
            }

            if (assigneeUserId != null) {
                predicates.add(criteriaBuilder.equal(root.get("assigneeUser").get("id"), assigneeUserId));
            }

            if (labelId != null) {
                predicates.add(criteriaBuilder.equal(root.join("labels").get("id"), labelId));
            }

            if (query != null) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("description")), pattern)
                ));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Map<UUID, Long> loadCommentCounts(List<Issue> issues) {
        if (issues.isEmpty()) {
            return Map.of();
        }

        List<UUID> issueIds = issues.stream()
                .map(Issue::getId)
                .toList();

        return issueCommentRepository.countByIssueIds(issueIds)
                .stream()
                .collect(Collectors.toMap(
                        IssueCommentRepository.IssueCommentCount::getIssueId,
                        IssueCommentRepository.IssueCommentCount::getCommentCount,
                        Long::sum
                ));
    }

    private long loadCommentCount(Issue issue) {
        return loadCommentCounts(List.of(issue)).getOrDefault(issue.getId(), 0L);
    }

    private ProjectBoardResponse loadBoard(Project project) {
        List<ProjectWorkflowState> workflowStates = projectWorkflowStateRepository
                .findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc(
                        project.getWorkspace().getId(),
                        project.getId()
                );
        List<Issue> issues = issueRepository.findActiveBoardIssues(
                project.getWorkspace().getId(),
                project.getId()
        );
        Map<UUID, Long> commentCounts = loadCommentCounts(issues);
        Map<UUID, List<Issue>> issuesByWorkflowState = new LinkedHashMap<>();
        for (ProjectWorkflowState workflowState : workflowStates) {
            issuesByWorkflowState.put(workflowState.getId(), new ArrayList<>());
        }
        for (Issue issue : issues) {
            issuesByWorkflowState.computeIfAbsent(
                    issue.getWorkflowState().getId(),
                    ignored -> new ArrayList<>()
            ).add(issue);
        }

        List<BoardColumnResponse> columns = workflowStates
                .stream()
                .map(workflowState -> new BoardColumnResponse(
                        toWorkflowStateResponse(workflowState),
                        issuesByWorkflowState.getOrDefault(workflowState.getId(), List.of())
                                .stream()
                                .map(issue -> toSummaryResponse(
                                        issue,
                                        commentCounts.getOrDefault(issue.getId(), 0L)
                                ))
                                .toList()
                ))
                .toList();
        return new ProjectBoardResponse(project.getId(), columns);
    }

    private long nextBoardPosition(Project project, ProjectWorkflowState workflowState) {
        return issueRepository.findMaxActiveBoardPosition(
                project.getWorkspace().getId(),
                project.getId(),
                workflowState.getId()
        ) + BOARD_POSITION_STEP;
    }

    private long nextBoardPositionExcludingIssue(
            Project project,
            ProjectWorkflowState workflowState,
            UUID issueId
    ) {
        return issueRepository.findMaxActiveBoardPositionExcludingIssue(
                project.getWorkspace().getId(),
                project.getId(),
                workflowState.getId(),
                issueId
        ) + BOARD_POSITION_STEP;
    }

    private IssueSummaryResponse toSummaryResponse(Issue issue, long commentCount) {
        return new IssueSummaryResponse(
                issue.getId(),
                issue.getProject().getId(),
                issue.getTitle(),
                issue.getDescription(),
                responseStatus(issue),
                toWorkflowStateResponse(issue.getWorkflowState()),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                toLabelResponses(issue),
                toUserResponse(issue.getCreatedByUser()),
                issue.getAssigneeUser() == null ? null : toUserResponse(issue.getAssigneeUser()),
                issue.getDueDate(),
                issue.getArchivedAt(),
                issue.getBoardPosition(),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                commentCount
        );
    }

    private IssueDetailResponse toDetailResponse(Issue issue, List<IssueCommentResponse> comments) {
        return new IssueDetailResponse(
                issue.getId(),
                issue.getProject().getId(),
                issue.getTitle(),
                issue.getDescription(),
                responseStatus(issue),
                toWorkflowStateResponse(issue.getWorkflowState()),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                toLabelResponses(issue),
                toUserResponse(issue.getCreatedByUser()),
                issue.getAssigneeUser() == null ? null : toUserResponse(issue.getAssigneeUser()),
                issue.getDueDate(),
                issue.getArchivedAt(),
                issue.getBoardPosition(),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                comments
        );
    }

    private IssueCommentResponse toCommentResponse(IssueComment comment) {
        User author = comment.getAuthorUser();
        return new IssueCommentResponse(
                comment.getId(),
                comment.getIssue().getId(),
                new UserResponse(author.getId(), author.getEmail(), author.getDisplayName()),
                comment.getBody(),
                comment.getCreatedAt()
        );
    }

    private List<ProjectLabelResponse> toLabelResponses(Issue issue) {
        return issue.getLabels()
                .stream()
                .map(this::toLabelResponse)
                .toList();
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

    private UserResponse toUserResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private User resolveAssignee(Project project, UUID assigneeUserId) {
        if (assigneeUserId == null) {
            return null;
        }

        return projectAccessService.requireActiveProjectMemberUser(project, assigneeUserId);
    }

    private List<ProjectLabel> resolveProjectLabels(Project project, List<UUID> labelIds) {
        if (labelIds == null || labelIds.isEmpty()) {
            return List.of();
        }

        Set<UUID> uniqueLabelIds = Set.copyOf(labelIds);
        List<ProjectLabel> labels = projectLabelRepository.findByWorkspace_IdAndProject_IdAndIdIn(
                project.getWorkspace().getId(),
                project.getId(),
                uniqueLabelIds
        );

        if (labels.size() != uniqueLabelIds.size()) {
            throw notFound("Project label not found");
        }

        return labels;
    }

    private ProjectWorkflowState resolveWorkflowStateForCreate(
            Project project,
            UUID workflowStateId,
            IssueStatus status
    ) {
        if (workflowStateId != null) {
            return requireProjectWorkflowState(project, workflowStateId);
        }

        if (status != null) {
            return requireDefaultWorkflowState(project, categoryFromStatus(status));
        }

        return requireDefaultWorkflowState(project, WorkflowStateCategory.TODO);
    }

    private ProjectWorkflowState requireProjectWorkflowState(Project project, UUID workflowStateId) {
        return projectWorkflowStateRepository.findByWorkspace_IdAndProject_IdAndId(
                        project.getWorkspace().getId(),
                        project.getId(),
                        workflowStateId
                )
                .orElseThrow(() -> notFound("Project workflow state not found"));
    }

    private ProjectWorkflowState requireDefaultWorkflowState(Project project, WorkflowStateCategory category) {
        return projectWorkflowStateRepository
                .findFirstByWorkspace_IdAndProject_IdAndCategoryOrderByPositionAscNameAsc(
                        project.getWorkspace().getId(),
                        project.getId(),
                        category
                )
                .orElseThrow(() -> notFound("Project workflow state not found"));
    }

    private WorkflowStateCategory categoryFromStatus(IssueStatus status) {
        return switch (status) {
            case TODO -> WorkflowStateCategory.TODO;
            case IN_PROGRESS -> WorkflowStateCategory.IN_PROGRESS;
            case DONE -> WorkflowStateCategory.DONE;
            case ARCHIVED -> throw badRequest("Archived is not a workflow state");
        };
    }

    @Transactional
    public IssueDetailResponse updateIssue(Jwt jwt, UUID issueId, JsonNode request) {
        if (request == null || !request.isObject()) {
            throw badRequest("Patch body must be a JSON object");
        }

        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = requireIssueProjectForUpdate(issueId, context);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        String previousTitle = issue.getTitle();
        ProjectWorkflowState previousWorkflowState = issue.getWorkflowState();
        String previousStatus = displayStatus(issue);
        UUID previousWorkflowStateId = issue.getWorkflowState().getId();
        boolean wasArchived = issue.getArchivedAt() != null;
        IssuePriority previousPriority = issue.getPriority();
        User previousAssignee = issue.getAssigneeUser();
        LocalDate previousDueDate = issue.getDueDate();

        if (request.has("title")) {
            String title = requiredText(request, "title", "Title is required").trim();
            if (title.isBlank()) {
                throw badRequest("Title is required");
            }
            if (title.length() > MAX_TITLE_LENGTH) {
                throw badRequest("Title must be at most 240 characters");
            }
            issue.rename(title);
        }

        if (request.has("description")) {
            JsonNode description = request.get("description");
            if (description.isNull()) {
                issue.changeDescription(null);
            } else {
                String descriptionValue = requiredText(request, "description", "Description must be text");
                if (descriptionValue.length() > MAX_DESCRIPTION_LENGTH) {
                    throw badRequest("Description must be at most 10000 characters");
                }
                issue.changeDescription(normalizeOptionalText(descriptionValue));
            }
        }

        if (request.has("labelIds")) {
            issue.replaceLabels(resolveProjectLabels(issue.getProject(), requiredUuidList(
                    request,
                    "labelIds",
                    "Labels are invalid"
            )));
        }

        if (request.has("assigneeUserId")) {
            JsonNode assigneeUserId = request.get("assigneeUserId");
            if (assigneeUserId.isNull()) {
                issue.assignTo(null);
            } else {
                issue.assignTo(resolveAssignee(issue.getProject(), requiredUuid(
                        request,
                        "assigneeUserId",
                        "Assignee is invalid"
                )));
            }
        }

        if (request.has("workflowStateId")) {
            issue.changeWorkflowState(requireProjectWorkflowState(issue.getProject(), requiredUuid(
                    request,
                    "workflowStateId",
                    "Workflow state is invalid"
            )));
        }

        if (request.has("status")) {
            IssueStatus requestedStatus = requiredEnum(request, "status", IssueStatus.class, "Status is required");
            if (requestedStatus == IssueStatus.ARCHIVED) {
                issue.archive();
            } else {
                issue.changeWorkflowState(requireDefaultWorkflowState(
                        issue.getProject(),
                        categoryFromStatus(requestedStatus)
                ));
                issue.unarchive();
            }
        }

        if (request.has("priority")) {
            JsonNode priority = request.get("priority");
            if (priority.isNull()) {
                issue.changePriority(null);
            } else {
                issue.changePriority(requiredEnum(request, "priority", IssuePriority.class, "Priority is invalid"));
            }
        }

        if (request.has("dueDate")) {
            JsonNode dueDate = request.get("dueDate");
            if (dueDate.isNull()) {
                issue.changeDueDate(null);
            } else {
                issue.changeDueDate(requiredLocalDate(request, "dueDate", "Due date is invalid"));
            }
        }

        UUID currentWorkflowStateId = issue.getWorkflowState().getId();
        boolean workflowStateChanged = !Objects.equals(previousWorkflowStateId, currentWorkflowStateId);
        if (issue.getArchivedAt() == null && (workflowStateChanged || wasArchived)) {
            issue.moveOnBoard(nextBoardPositionExcludingIssue(
                    project,
                    issue.getWorkflowState(),
                    issue.getId()
            ));
        }

        if (!Objects.equals(previousTitle, issue.getTitle())) {
            activityService.recordIssueTitleChanged(
                    issue,
                    context.user(),
                    previousTitle,
                    issue.getTitle()
            );
        }

        String currentStatus = displayStatus(issue);
        if (!Objects.equals(previousStatus, currentStatus)
                || workflowStateChanged) {
            activityService.recordIssueStatusChanged(
                    issue,
                    context.user(),
                    previousStatus,
                    currentStatus,
                    previousWorkflowState.getId(),
                    currentWorkflowStateId
            );
        }

        if (!Objects.equals(previousPriority, issue.getPriority())) {
            activityService.recordIssuePriorityChanged(
                    issue,
                    context.user(),
                    previousPriority,
                    issue.getPriority()
            );
        }

        if (!Objects.equals(userId(previousAssignee), userId(issue.getAssigneeUser()))) {
            activityService.recordIssueAssigneeChanged(
                    issue,
                    context.user(),
                    previousAssignee,
                    issue.getAssigneeUser()
            );
        }

        if (!Objects.equals(previousDueDate, issue.getDueDate())) {
            activityService.recordIssueDueDateChanged(
                    issue,
                    context.user(),
                    previousDueDate,
                    issue.getDueDate()
            );
        }

        List<IssueCommentResponse> comments = issueCommentRepository.findByIssue_IdOrderByCreatedAtAsc(issue.getId())
                .stream()
                .map(this::toCommentResponse)
                .toList();

        return toDetailResponse(issue, comments);
    }

    private String requiredText(JsonNode request, String fieldName, String message) {
        JsonNode value = request.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw badRequest(message);
        }

        return value.asText();
    }

    private UUID requiredUuid(JsonNode request, String fieldName, String message) {
        String value = requiredText(request, fieldName, message);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest(message);
        }
    }

    private List<UUID> requiredUuidList(JsonNode request, String fieldName, String message) {
        JsonNode value = request.get(fieldName);
        if (value == null || value.isNull() || !value.isArray()) {
            throw badRequest(message);
        }

        List<UUID> ids = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw badRequest(message);
            }

            try {
                ids.add(UUID.fromString(item.asText()));
            } catch (IllegalArgumentException exception) {
                throw badRequest(message);
            }
        }

        return ids;
    }

    private LocalDate requiredLocalDate(JsonNode request, String fieldName, String message) {
        String value = requiredText(request, fieldName, message);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw badRequest(message);
        }
    }

    private UUID userId(User user) {
        return user == null ? null : user.getId();
    }

    private String displayStatus(Issue issue) {
        if (issue.getArchivedAt() != null) {
            return "Archived";
        }

        return issue.getWorkflowState().getName();
    }

    private String responseStatus(Issue issue) {
        if (issue.getArchivedAt() != null) {
            return IssueStatus.ARCHIVED.name();
        }

        return issue.getWorkflowState().getCategory().toIssueStatus().name();
    }

    private <E extends Enum<E>> E requiredEnum(
            JsonNode request,
            String fieldName,
            Class<E> enumType,
            String message
    ) {
        JsonNode value = request.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw badRequest(message);
        }

        try {
            return Enum.valueOf(enumType, value.asText());
        } catch (IllegalArgumentException exception) {
            throw badRequest(message);
        }
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
