package com.vokyo.backend.ai.breakdown;

import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;

import java.util.Objects;

public record BuiltIssueBreakdownContext(
        CurrentWorkspaceContext currentContext,
        Project project,
        Issue sourceIssue,
        IssueBreakdownContext modelContext
) {
    public BuiltIssueBreakdownContext {
        Objects.requireNonNull(
                currentContext,
                "currentContext is required"
        );
        Objects.requireNonNull(
                project,
                "project is required"
        );
        Objects.requireNonNull(
                sourceIssue,
                "sourceIssue is required"
        );
        Objects.requireNonNull(
                modelContext,
                "modelContext is required"
        );
    }
}
