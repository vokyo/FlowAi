package com.vokyo.backend.ai.summary.issue;

import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;

public record BuiltIssueSummaryContext(
        CurrentWorkspaceContext currentContext,
        Project project,
        Issue sourceIssue,
        IssueSummaryContext modelContext
) {
}
