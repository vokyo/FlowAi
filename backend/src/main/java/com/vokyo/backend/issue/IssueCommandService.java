package com.vokyo.backend.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.issue.dto.CreateCommentRequest;
import com.vokyo.backend.issue.dto.CreateIssueRequest;
import com.vokyo.backend.issue.dto.IssueCommentResponse;
import com.vokyo.backend.issue.dto.IssueDetailResponse;
import com.vokyo.backend.issue.dto.IssueSummaryResponse;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IssueCommandService {

    private static final int MAX_TITLE_LENGTH = 240;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final long BOARD_POSITION_STEP = 10_000L;

    private final IssueRepository issueRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActivityService activityService;
    private final IssueMapper issueMapper;

    public IssueCommandService(
            IssueRepository issueRepository,
            IssueCommentRepository issueCommentRepository,
            ProjectLabelRepository projectLabelRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            ActivityService activityService,
            IssueMapper issueMapper
    ) {
        this.issueRepository = issueRepository;
        this.issueCommentRepository = issueCommentRepository;
        this.projectLabelRepository = projectLabelRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.projectAccessService = projectAccessService;
        this.workspaceAccessService = workspaceAccessService;
        this.activityService = activityService;
        this.issueMapper = issueMapper;
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
        ProjectWorkflowState workflowState = resolveWorkflowStateForCreate(
                project,
                request.workflowStateId(),
                request.status()
        );

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
                nextBoardPosition(project, workflowState)
        ));
        issue.replaceLabels(labels);

        activityService.recordIssueCreated(issue, context.user());
        return issueMapper.toSummaryResponse(issue, 0L);
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
        return issueMapper.toCommentResponse(comment);
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
        UUID previousWorkflowStateId = previousWorkflowState.getId();
        boolean wasArchived = issue.getArchivedAt() != null;
        IssuePriority previousPriority = issue.getPriority();
        User previousAssignee = issue.getAssigneeUser();
        LocalDate previousDueDate = issue.getDueDate();

        applyIssuePatch(issue, request);

        UUID currentWorkflowStateId = issue.getWorkflowState().getId();
        boolean workflowStateChanged = !Objects.equals(previousWorkflowStateId, currentWorkflowStateId);
        if (issue.getArchivedAt() == null && (workflowStateChanged || wasArchived)) {
            issue.moveOnBoard(nextBoardPositionExcludingIssue(
                    project,
                    issue.getWorkflowState(),
                    issue.getId()
            ));
        }

        recordIssueChanges(
                issue,
                context,
                previousTitle,
                previousWorkflowState,
                previousStatus,
                previousPriority,
                previousAssignee,
                previousDueDate,
                workflowStateChanged
        );
        return issueMapper.toDetailResponse(issue);
    }

    private void applyIssuePatch(Issue issue, JsonNode request) {
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
                String descriptionValue = requiredText(
                        request,
                        "description",
                        "Description must be text"
                );
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
            issue.assignTo(assigneeUserId.isNull()
                    ? null
                    : resolveAssignee(issue.getProject(), requiredUuid(
                            request,
                            "assigneeUserId",
                            "Assignee is invalid"
                    )));
        }
        if (request.has("workflowStateId")) {
            issue.changeWorkflowState(requireProjectWorkflowState(issue.getProject(), requiredUuid(
                    request,
                    "workflowStateId",
                    "Workflow state is invalid"
            )));
        }
        if (request.has("status")) {
            IssueStatus requestedStatus = requiredEnum(
                    request,
                    "status",
                    IssueStatus.class,
                    "Status is required"
            );
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
            issue.changePriority(priority.isNull()
                    ? null
                    : requiredEnum(request, "priority", IssuePriority.class, "Priority is invalid"));
        }
        if (request.has("dueDate")) {
            JsonNode dueDate = request.get("dueDate");
            issue.changeDueDate(dueDate.isNull()
                    ? null
                    : requiredLocalDate(request, "dueDate", "Due date is invalid"));
        }
    }

    private void recordIssueChanges(
            Issue issue,
            CurrentWorkspaceContext context,
            String previousTitle,
            ProjectWorkflowState previousWorkflowState,
            String previousStatus,
            IssuePriority previousPriority,
            User previousAssignee,
            LocalDate previousDueDate,
            boolean workflowStateChanged
    ) {
        if (!Objects.equals(previousTitle, issue.getTitle())) {
            activityService.recordIssueTitleChanged(
                    issue,
                    context.user(),
                    previousTitle,
                    issue.getTitle()
            );
        }

        String currentStatus = displayStatus(issue);
        if (!Objects.equals(previousStatus, currentStatus) || workflowStateChanged) {
            activityService.recordIssueStatusChanged(
                    issue,
                    context.user(),
                    previousStatus,
                    currentStatus,
                    previousWorkflowState.getId(),
                    issue.getWorkflowState().getId()
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
    }

    private Project requireIssueProjectForUpdate(UUID issueId, CurrentWorkspaceContext context) {
        UUID projectId = issueRepository.findProjectIdByIdAndWorkspaceId(
                        issueId,
                        context.workspace().getId()
                )
                .orElseThrow(() -> notFound("Issue not found"));
        return projectAccessService.requireAccessibleProjectForUpdate(projectId, context);
    }

    private Issue requireIssue(UUID issueId, UUID workspaceId) {
        return issueRepository.findByIdAndWorkspace_Id(issueId, workspaceId)
                .orElseThrow(() -> notFound("Issue not found"));
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

        Map<UUID, ProjectLabel> labelsById = labels.stream()
                .collect(Collectors.toMap(ProjectLabel::getId, label -> label));
        return labelIds.stream().distinct().map(labelsById::get).toList();
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

    private ProjectWorkflowState requireDefaultWorkflowState(
            Project project,
            WorkflowStateCategory category
    ) {
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

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private UUID userId(User user) {
        return user == null ? null : user.getId();
    }

    private String displayStatus(Issue issue) {
        return issue.getArchivedAt() == null ? issue.getWorkflowState().getName() : "Archived";
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
