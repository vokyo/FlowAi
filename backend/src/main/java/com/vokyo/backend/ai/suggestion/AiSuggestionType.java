package com.vokyo.backend.ai.suggestion;

/**
 * Each type says whether it belongs to one issue or to a whole project, which is
 * the same rule the ck_ai_suggestions_source constraint enforces in the database.
 */
public enum AiSuggestionType {
    ISSUE_BREAKDOWN(true),
    ISSUE_SUMMARY(true),
    PROJECT_SUMMARY(false),
    PROJECT_PLAN(false);

    private final boolean requiresSourceIssue;

    AiSuggestionType(boolean requiresSourceIssue) {
        this.requiresSourceIssue = requiresSourceIssue;
    }

    public boolean requiresSourceIssue() {
        return requiresSourceIssue;
    }
}
