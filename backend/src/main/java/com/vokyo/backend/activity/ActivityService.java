package com.vokyo.backend.activity;

import com.vokyo.backend.activity.dto.ActivityEventResponse;
import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueComment;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueStatus;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityService {

    private final ActivityEventRepository activityEventRepository;

    public ActivityService(ActivityEventRepository activityEventRepository) {
        this.activityEventRepository = activityEventRepository;
    }

    @Transactional
    public void recordProjectCreated(Project project, User actor) {
        activityEventRepository.save(new ActivityEvent(
                project.getWorkspace(),
                project,
                null,
                actor,
                ActivityEventType.PROJECT_CREATED,
                metadata(
                        "projectId", project.getId(),
                        "projectName", project.getName()
                )
        ));
    }

    @Transactional
    public void recordIssueCreated(Issue issue, User actor) {
        activityEventRepository.save(new ActivityEvent(
                issue.getWorkspace(),
                issue.getProject(),
                issue,
                actor,
                ActivityEventType.ISSUE_CREATED,
                metadata(
                        "issueId", issue.getId(),
                        "issueTitle", issue.getTitle()
                )
        ));
    }

    @Transactional
    public void recordCommentCreated(IssueComment comment, User actor) {
        activityEventRepository.save(new ActivityEvent(
                comment.getWorkspace(),
                comment.getProject(),
                comment.getIssue(),
                actor,
                ActivityEventType.COMMENT_CREATED,
                metadata(
                        "commentId", comment.getId(),
                        "issueId", comment.getIssue().getId()
                )
        ));
    }

    @Transactional(readOnly = true)
    public List<ActivityEventResponse> listIssueActivities(UUID issueId, UUID workspaceId) {
        return activityEventRepository.findByIssue_IdAndWorkspace_IdOrderByCreatedAtAsc(issueId, workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ActivityEventResponse toResponse(ActivityEvent event) {
        User actor = event.getActorUser();
        return new ActivityEventResponse(
                event.getId(),
                event.getEventType().name(),
                new UserResponse(actor.getId(), actor.getEmail(), actor.getDisplayName()),
                event.getMetadata(),
                event.getCreatedAt()
        );
    }

    private Map<String, Object> metadata(Object... entries) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            String key = (String) entries[i];
            Object value = entries[i + 1];
            metadata.put(key, value instanceof UUID uuid ? uuid.toString() : value);
        }
        return metadata;
    }

    @Transactional
    public void recordIssueStatusChanged(
            Issue issue,
            User actor,
            IssueStatus fromStatus,
            IssueStatus toStatus
    ) {
        activityEventRepository.save(new ActivityEvent(
                issue.getWorkspace(),
                issue.getProject(),
                issue,
                actor,
                ActivityEventType.ISSUE_STATUS_CHANGED,
                metadata(
                        "issueId", issue.getId(),
                        "fromStatus", fromStatus.name(),
                        "toStatus", toStatus.name()
                )
        ));
    }

    @Transactional
    public void recordIssueTitleChanged(
            Issue issue,
            User actor,
            String fromTitle,
            String toTitle
    ) {
        activityEventRepository.save(new ActivityEvent(
                issue.getWorkspace(),
                issue.getProject(),
                issue,
                actor,
                ActivityEventType.ISSUE_TITLE_CHANGED,
                metadata(
                        "issueId", issue.getId(),
                        "fromTitle", fromTitle,
                        "toTitle", toTitle
                )
        ));
    }

    @Transactional
    public void recordIssuePriorityChanged(
            Issue issue,
            User actor,
            IssuePriority fromPriority,
            IssuePriority toPriority
    ) {
        activityEventRepository.save(new ActivityEvent(
                issue.getWorkspace(),
                issue.getProject(),
                issue,
                actor,
                ActivityEventType.ISSUE_PRIORITY_CHANGED,
                metadata(
                        "issueId", issue.getId(),
                        "fromPriority", nullableName(fromPriority),
                        "toPriority", nullableName(toPriority)
                )
        ));
    }

    @Transactional
    public void recordIssueAssigneeChanged(
            Issue issue,
            User actor,
            User fromAssignee,
            User toAssignee
    ) {
        activityEventRepository.save(new ActivityEvent(
                issue.getWorkspace(),
                issue.getProject(),
                issue,
                actor,
                ActivityEventType.ISSUE_ASSIGNEE_CHANGED,
                metadata(
                        "issueId", issue.getId(),
                        "fromAssigneeUserId", nullableId(fromAssignee),
                        "fromAssigneeName", nullableDisplayName(fromAssignee),
                        "toAssigneeUserId", nullableId(toAssignee),
                        "toAssigneeName", nullableDisplayName(toAssignee)
                )
        ));
    }

    @Transactional
    public void recordIssueDueDateChanged(
            Issue issue,
            User actor,
            LocalDate fromDueDate,
            LocalDate toDueDate
    ) {
        activityEventRepository.save(new ActivityEvent(
                issue.getWorkspace(),
                issue.getProject(),
                issue,
                actor,
                ActivityEventType.ISSUE_DUE_DATE_CHANGED,
                metadata(
                        "issueId", issue.getId(),
                        "fromDueDate", nullableString(fromDueDate),
                        "toDueDate", nullableString(toDueDate)
                )
        ));
    }

    private String nullableName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String nullableId(User user) {
        return user == null ? null : user.getId().toString();
    }

    private String nullableDisplayName(User user) {
        if (user == null) {
            return null;
        }

        return user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? user.getEmail()
                : user.getDisplayName();
    }

    private String nullableString(Object value) {
        return value == null ? null : value.toString();
    }
}
