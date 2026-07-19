package com.vokyo.backend.issue;

import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.issue.dto.IssueCommentResponse;
import com.vokyo.backend.issue.dto.IssueDetailResponse;
import com.vokyo.backend.issue.dto.IssueSummaryResponse;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;
import com.vokyo.backend.user.User;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class IssueMapper {

    public IssueSummaryResponse toSummaryResponse(Issue issue, long commentCount) {
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

    public IssueDetailResponse toDetailResponse(Issue issue) {
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
                issue.getUpdatedAt()
        );
    }

    public IssueCommentResponse toCommentResponse(IssueComment comment) {
        User author = comment.getAuthorUser();
        return new IssueCommentResponse(
                comment.getId(),
                comment.getIssue().getId(),
                toUserResponse(author),
                comment.getBody(),
                comment.getCreatedAt()
        );
    }

    public ProjectWorkflowStateResponse toWorkflowStateResponse(ProjectWorkflowState workflowState) {
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

    private List<ProjectLabelResponse> toLabelResponses(Issue issue) {
        return issue.getLabels()
                .stream()
                .sorted(Comparator
                        .comparing(ProjectLabel::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ProjectLabel::getId))
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

    private UserResponse toUserResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }

    private String responseStatus(Issue issue) {
        if (issue.getArchivedAt() != null) {
            return IssueStatus.ARCHIVED.name();
        }
        return issue.getWorkflowState().getCategory().toIssueStatus().name();
    }
}
