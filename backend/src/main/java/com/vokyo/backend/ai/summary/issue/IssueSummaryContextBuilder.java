package com.vokyo.backend.ai.summary.issue;

import com.vokyo.backend.activity.ActivityEvent;
import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.ai.summary.issue.dto.IssueSummaryRequest;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueComment;
import com.vokyo.backend.issue.IssueCommentRepository;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
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
public class IssueSummaryContextBuilder {

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;
    private final IssueRepository issueRepository;
    private final IssueCommentRepository commentRepository;
    private final ActivityEventRepository activityRepository;
    private final AiProperties aiProperties;

    public IssueSummaryContextBuilder(
            WorkspaceAccessService workspaceAccessService,
            ProjectAccessService projectAccessService,
            IssueRepository issueRepository,
            IssueCommentRepository commentRepository,
            ActivityEventRepository activityRepository,
            AiProperties aiProperties
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
        this.issueRepository = issueRepository;
        this.commentRepository = commentRepository;
        this.activityRepository = activityRepository;
        this.aiProperties = aiProperties;
    }

    @Transactional(readOnly = true)
    public BuiltIssueSummaryContext build(
            Jwt jwt,
            UUID issueId,
            IssueSummaryRequest request
    ) {
        Objects.requireNonNull(request, "request is required");
        CurrentWorkspaceContext context =
                workspaceAccessService.requireCurrentContext(jwt);
        UUID workspaceId = context.workspace().getId();
        Issue issue = issueRepository.findByIdAndWorkspace_Id(issueId, workspaceId)
                .orElseThrow(() -> notFound("Issue not found"));
        projectAccessService.requireIssueProjectAccess(issue, context);
        Project project = issue.getProject();
        UUID projectId = project.getId();

        BoundedResult<IssueComment> comments = request.commentsEnabled()
                ? loadComments(workspaceId, projectId, issueId)
                : BoundedResult.empty();
        BoundedResult<ActivityEvent> activities = request.activityEnabled()
                ? loadActivities(workspaceId, projectId, issueId)
                : BoundedResult.empty();

        IssueSummaryContext.SourceStats stats =
                new IssueSummaryContext.SourceStats(
                        comments.items().size(),
                        activities.items().size(),
                        comments.truncated(),
                        activities.truncated(),
                        comments.truncated() || activities.truncated()
                );
        IssueSummaryContext modelContext = new IssueSummaryContext(
                new IssueSummaryContext.ProjectContext(
                        project.getName(),
                        project.getDescription()
                ),
                toIssueContext(issue),
                map(comments.items(), this::toCommentContext),
                map(activities.items(), this::toActivityContext),
                stats
        );
        return new BuiltIssueSummaryContext(context, project, issue, modelContext);
    }

    private BoundedResult<IssueComment> loadComments(
            UUID workspaceId,
            UUID projectId,
            UUID issueId
    ) {
        int limit = aiProperties.includeCommentsLimit();
        return boundedChronological(commentRepository.findFirstPage(
                workspaceId,
                projectId,
                issueId,
                PageRequest.of(0, limit + 1)
        ), limit);
    }

    private BoundedResult<ActivityEvent> loadActivities(
            UUID workspaceId,
            UUID projectId,
            UUID issueId
    ) {
        int limit = aiProperties.includeActivityLimit();
        return boundedChronological(activityRepository.findFirstPage(
                workspaceId,
                projectId,
                issueId,
                PageRequest.of(0, limit + 1)
        ), limit);
    }

    private IssueSummaryContext.IssueContext toIssueContext(Issue issue) {
        User assignee = issue.getAssigneeUser();
        return new IssueSummaryContext.IssueContext(
                issue.getId(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getStatus().name(),
                issue.getWorkflowState().getName(),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                issue.getDueDate(),
                assignee == null ? null : assignee.getDisplayName(),
                issue.getLabels().stream().map(ProjectLabel::getName).toList(),
                issue.getCreatedAt(),
                issue.getUpdatedAt()
        );
    }

    private IssueSummaryContext.CommentContext toCommentContext(
            IssueComment comment
    ) {
        return new IssueSummaryContext.CommentContext(
                comment.getCreatedAt(),
                comment.getAuthorUser().getDisplayName(),
                comment.getBody()
        );
    }

    private IssueSummaryContext.ActivityContext toActivityContext(
            ActivityEvent activity
    ) {
        return new IssueSummaryContext.ActivityContext(
                activity.getCreatedAt(),
                activity.getActorUser().getDisplayName(),
                activity.getEventType().name(),
                activity.getMetadata()
        );
    }

    private <T> BoundedResult<T> boundedChronological(
            List<T> newestFirst,
            int limit
    ) {
        boolean truncated = newestFirst.size() > limit;
        List<T> retained = new ArrayList<>(
                newestFirst.subList(0, Math.min(limit, newestFirst.size()))
        );
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
