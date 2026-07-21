package com.vokyo.backend.ai.breakdown;

import com.vokyo.backend.activity.ActivityEvent;
import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownRequest;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueComment;
import com.vokyo.backend.issue.IssueCommentRepository;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Component
public class IssueBreakdownContextBuilder {

    private static final int DEFAULT_MAX_ITEMS = 5;
    private static final int MIN_MAX_ITEMS = 2;
    private static final int ABSOLUTE_MAX_ITEMS = 8;
    private static final int MAX_INSTRUCTION_LENGTH = 2_000;

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;
    private final IssueRepository issueRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final ActivityEventRepository activityEventRepository;
    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final AiProperties aiProperties;

    public IssueBreakdownContextBuilder(
            WorkspaceAccessService workspaceAccessService,
            ProjectAccessService projectAccessService,
            IssueRepository issueRepository,
            IssueCommentRepository issueCommentRepository,
            ActivityEventRepository activityEventRepository,
            ProjectLabelRepository projectLabelRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            AiProperties aiProperties
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
        this.issueRepository = issueRepository;
        this.issueCommentRepository = issueCommentRepository;
        this.activityEventRepository = activityEventRepository;
        this.projectLabelRepository = projectLabelRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.aiProperties = aiProperties;
    }

    @Transactional(readOnly = true)
    public BuiltIssueBreakdownContext build(
            Jwt jwt,
            UUID issueId,
            IssueBreakdownRequest request
    ) {
        Objects.requireNonNull(jwt, "jwt is required");
        Objects.requireNonNull(issueId, "issueId is required");
        Objects.requireNonNull(request, "request is required");

        CurrentWorkspaceContext currentContext = workspaceAccessService.requireCurrentContext(jwt);
        UUID workspaceId = currentContext.workspace().getId();
        Issue sourceIssue = issueRepository.findByIdAndWorkspace_Id(issueId, workspaceId)
                .orElseThrow(() -> notFound("Issue not found"));
        projectAccessService.requireIssueProjectAccess(sourceIssue, currentContext);

        Project project = sourceIssue.getProject();
        requireActiveSource(project, sourceIssue);

        String instruction = normalizeInstruction(request.instruction());
        int maxItems = resolveMaxItems(request.maxItems());
        UUID projectId = project.getId();

        BoundedResult<IssueComment> comments = request.includeComments()
                ? loadComments(workspaceId, projectId, issueId)
                : BoundedResult.empty();
        BoundedResult<ActivityEvent> activities = request.includeActivity()
                ? loadActivities(workspaceId, projectId, issueId)
                : BoundedResult.empty();

        IssueBreakdownContext.AllowedCandidates candidates = loadAllowedCandidates(workspaceId, projectId);
        IssueBreakdownContext modelContext = new IssueBreakdownContext(
                new IssueBreakdownContext.ProjectContext(project.getName(), project.getDescription()),
                toSourceIssueContext(sourceIssue),
                instruction,
                maxItems,
                map(comments.items(), this::toCommentContext),
                map(activities.items(), this::toActivityContext),
                candidates,
                new IssueBreakdownContext.SourceStats(
                        comments.items().size(),
                        activities.items().size(),
                        comments.truncated(),
                        activities.truncated(),
                        comments.truncated() || activities.truncated()
                )
        );

        return new BuiltIssueBreakdownContext(currentContext, project, sourceIssue, modelContext);
    }

    private BoundedResult<IssueComment> loadComments(UUID workspaceId, UUID projectId, UUID issueId) {
        int limit = aiProperties.includeCommentsLimit();
        List<IssueComment> newestFirst = issueCommentRepository.findFirstPage(
                workspaceId,
                projectId,
                issueId,
                PageRequest.of(0, fetchSize(limit))
        );
        return boundedChronological(newestFirst, limit);
    }

    private BoundedResult<ActivityEvent> loadActivities(UUID workspaceId, UUID projectId, UUID issueId) {
        int limit = aiProperties.includeActivityLimit();
        List<ActivityEvent> newestFirst = activityEventRepository.findFirstPage(
                workspaceId,
                projectId,
                issueId,
                PageRequest.of(0, fetchSize(limit))
        );
        return boundedChronological(newestFirst, limit);
    }

    private IssueBreakdownContext.AllowedCandidates loadAllowedCandidates(UUID workspaceId, UUID projectId) {
        List<IssueBreakdownContext.LabelCandidate> labels = projectLabelRepository
                .findByWorkspace_IdAndProject_IdOrderByNameAsc(workspaceId, projectId)
                .stream()
                .map(this::toLabelCandidate)
                .toList();
        List<IssueBreakdownContext.MemberCandidate> members = projectMemberRepository
                .findByProjectForMemberList(workspaceId, projectId)
                .stream()
                .filter(member -> member.getStatus() == MembershipStatus.ACTIVE)
                .map(this::toMemberCandidate)
                .toList();
        List<IssueBreakdownContext.WorkflowStateCandidate> workflowStates = projectWorkflowStateRepository
                .findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc(workspaceId, projectId)
                .stream()
                .map(this::toWorkflowStateCandidate)
                .toList();

        return new IssueBreakdownContext.AllowedCandidates(labels, members, workflowStates);
    }

    private IssueBreakdownContext.SourceIssueContext toSourceIssueContext(Issue issue) {
        ProjectWorkflowState workflowState = issue.getWorkflowState();
        User assignee = issue.getAssigneeUser();
        List<UUID> labelIds = issue.getLabels().stream()
                .map(ProjectLabel::getId)
                .toList();

        return new IssueBreakdownContext.SourceIssueContext(
                issue.getId(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getStatus().name(),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                issue.getDueDate(),
                new IssueBreakdownContext.WorkflowStateContext(
                        workflowState.getId(),
                        workflowState.getName(),
                        workflowState.getCategory().name()
                ),
                assignee == null
                        ? null
                        : new IssueBreakdownContext.AssigneeContext(assignee.getId(), assignee.getDisplayName()),
                labelIds
        );
    }

    private IssueBreakdownContext.CommentContext toCommentContext(IssueComment comment) {
        return new IssueBreakdownContext.CommentContext(
                comment.getCreatedAt(),
                comment.getAuthorUser().getDisplayName(),
                comment.getBody()
        );
    }

    private IssueBreakdownContext.ActivityContext toActivityContext(ActivityEvent event) {
        return new IssueBreakdownContext.ActivityContext(
                event.getCreatedAt(),
                event.getActorUser().getDisplayName(),
                event.getEventType().name(),
                event.getMetadata()
        );
    }

    private IssueBreakdownContext.LabelCandidate toLabelCandidate(ProjectLabel label) {
        return new IssueBreakdownContext.LabelCandidate(label.getId(), label.getName(), label.getColor());
    }

    private IssueBreakdownContext.MemberCandidate toMemberCandidate(ProjectMember member) {
        return new IssueBreakdownContext.MemberCandidate(
                member.getUser().getId(),
                member.getUser().getDisplayName(),
                member.getRole().name()
        );
    }

    private IssueBreakdownContext.WorkflowStateCandidate toWorkflowStateCandidate(ProjectWorkflowState state) {
        return new IssueBreakdownContext.WorkflowStateCandidate(
                state.getId(),
                state.getName(),
                state.getCategory().name()
        );
    }

    private void requireActiveSource(Project project, Issue issue) {
        if (project.getArchivedAt() != null) {
            throw AiFeatureException.requestInvalid("Archived projects cannot be used for issue breakdown");
        }
        if (issue.getArchivedAt() != null) {
            throw AiFeatureException.requestInvalid("Archived issues cannot be broken down");
        }
    }

    private String normalizeInstruction(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return null;
        }

        String normalized = instruction.strip();
        if (normalized.length() > MAX_INSTRUCTION_LENGTH) {
            throw AiFeatureException.requestInvalid("Instruction must be at most 2000 characters");
        }
        return normalized;
    }

    private int resolveMaxItems(Integer requestedMaxItems) {
        int configuredMaximum = aiProperties.maxBreakdownItems();
        int resolved = requestedMaxItems == null
                ? Math.min(DEFAULT_MAX_ITEMS, configuredMaximum)
                : requestedMaxItems;

        if (resolved < MIN_MAX_ITEMS || resolved > ABSOLUTE_MAX_ITEMS) {
            throw AiFeatureException.requestInvalid("maxItems must be between 2 and 8");
        }
        if (resolved > configuredMaximum) {
            throw AiFeatureException.requestInvalid(
                    "maxItems exceeds the configured maximum of " + configuredMaximum
            );
        }
        return resolved;
    }

    private int fetchSize(int limit) {
        return limit == Integer.MAX_VALUE ? limit : limit + 1;
    }

    private <T> BoundedResult<T> boundedChronological(List<T> newestFirst, int limit) {
        boolean truncated = newestFirst.size() > limit;
        List<T> retained = new ArrayList<>(newestFirst.subList(0, Math.min(limit, newestFirst.size())));
        Collections.reverse(retained);
        return new BoundedResult<>(retained, truncated);
    }

    private <T, R> List<R> map(List<T> values, Function<T, R> mapper) {
        return values.stream().map(mapper).toList();
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record BoundedResult<T>(List<T> items, boolean truncated) {
        private BoundedResult {
            items = List.copyOf(items);
        }

        private static <T> BoundedResult<T> empty() {
            return new BoundedResult<>(List.of(), false);
        }
    }
}
