package com.vokyo.backend.ai.summary.project;

import com.vokyo.backend.project.Project;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;

public record BuiltProjectSummaryContext(
        CurrentWorkspaceContext currentContext,
        Project project,
        ProjectSummaryContext modelContext
) {
}
