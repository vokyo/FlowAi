package com.vokyo.backend.project;

import com.vokyo.backend.issue.IssueStatus;

public enum WorkflowStateCategory {
    TODO,
    IN_PROGRESS,
    DONE;

    public IssueStatus toIssueStatus() {
        return switch (this) {
            case TODO -> IssueStatus.TODO;
            case IN_PROGRESS -> IssueStatus.IN_PROGRESS;
            case DONE -> IssueStatus.DONE;
        };
    }
}
