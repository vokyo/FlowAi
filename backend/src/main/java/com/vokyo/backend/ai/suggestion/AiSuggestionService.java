package com.vokyo.backend.ai.suggestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class AiSuggestionService {

    private final AiSuggestionRepository suggestionRepository;
    private final AiProperties aiProperties;
    private final Clock clock;

    public AiSuggestionService(
            AiSuggestionRepository suggestionRepository,
            AiProperties aiProperties,
            Clock clock
    ) {
        this.suggestionRepository = suggestionRepository;
        this.aiProperties = aiProperties;
        this.clock = clock;
    }


    @Transactional
    public AiSuggestion createDraft(CreateDraftCommand command) {
        Objects.requireNonNull(command, "command is required");

        validateTenantGraph(command);

        Instant now = clock.instant();
        Duration ttl = aiProperties.suggestionTtl();

        if (ttl.isZero() || ttl.isNegative()) {
            throw AiFeatureException.suggestionInvalid(
                    "Suggestion TTL must be positive"
            );
        }

        Instant expiresAt = now.plus(ttl);
        String inputHash = sha256(command.canonicalInput());

        AiSuggestion suggestion = new AiSuggestion(
                command.context().workspace(),
                command.project(),
                command.sourceIssue(),
                command.context().user(),
                command.type(),
                command.content(),
                command.promptVersion(),
                command.provider(),
                command.model(),
                inputHash,
                command.inputTokens(),
                command.outputTokens(),
                expiresAt
        );

        return suggestionRepository.save(suggestion);
    }

    @Transactional
    public AiSuggestion getOwnedSuggestion(
            CurrentWorkspaceContext context,
            UUID suggestionId
    ) {
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(
                suggestionId,
                "suggestionId is required"
        );

        AiSuggestion suggestion = requireOwnedForUpdate(
                context,
                suggestionId
        );

        expireIfNeeded(suggestion, clock.instant());

        return suggestion;
    }


    @Transactional
    public AiSuggestion dismiss(
            CurrentWorkspaceContext context,
            UUID suggestionId
    ) {
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(
                suggestionId,
                "suggestionId is required"
        );

        AiSuggestion suggestion = requireOwnedForUpdate(
                context,
                suggestionId
        );

        Instant now = clock.instant();

        if (expireIfNeeded(suggestion, now)) {
            return suggestion;
        }

        if (suggestion.getStatus() != AiSuggestionStatus.DRAFT) {
            throw AiFeatureException.suggestionNotDraft();
        }

        suggestion.dismiss(now);

        return suggestion;
    }


    @Transactional
    public AiSuggestion requireApplicableDraft(
            CurrentWorkspaceContext context,
            UUID suggestionId,
            UUID idempotencyKey
    ) {
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(
                suggestionId,
                "suggestionId is required"
        );
        Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey is required"
        );

        AiSuggestion suggestion = requireOwnedForUpdate(
                context,
                suggestionId
        );

        Instant now = clock.instant();

        /*
         * 相同幂等键重复调用时，可以返回之前的 Apply 结果。
         */
        if (suggestion.wasAppliedWith(idempotencyKey)) {
            return suggestion;
        }

        if (expireIfNeeded(suggestion, now)) {
            return suggestion;
        }

        if (suggestion.getStatus() != AiSuggestionStatus.DRAFT) {
            throw AiFeatureException.suggestionNotDraft();
        }

        return suggestion;
    }


    @Transactional
    public AiSuggestion markApplied(
            AiSuggestion suggestion,
            UUID idempotencyKey
    ) {
        Objects.requireNonNull(
                suggestion,
                "suggestion is required"
        );
        Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey is required"
        );

        if (suggestion.wasAppliedWith(idempotencyKey)) {
            return suggestion;
        }

        if (suggestion.getStatus() != AiSuggestionStatus.DRAFT) {
            throw AiFeatureException.suggestionNotDraft();
        }

        Instant now = clock.instant();

        if (suggestion.isExpiredAt(now)) {
            suggestion.expire(now);
            return suggestion;
        }

        suggestion.apply(idempotencyKey, now);

        return suggestion;
    }


    private AiSuggestion requireOwnedForUpdate(
            CurrentWorkspaceContext context,
            UUID suggestionId
    ) {
        return suggestionRepository.findOwnedByIdForUpdate(
                        context.workspace().getId(),
                        context.user().getId(),
                        suggestionId
                )
                .orElseThrow(
                        AiFeatureException::suggestionNotFound
                );
    }


    private boolean expireIfNeeded(
            AiSuggestion suggestion,
            Instant now
    ) {
        if (!suggestion.isExpiredAt(now)) {
            return false;
        }

        suggestion.expire(now);
        return true;
    }


    private void validateTenantGraph(CreateDraftCommand command) {
        UUID workspaceId = command.context().workspace().getId();
        Project project = command.project();
        Issue sourceIssue = command.sourceIssue();

        if (!workspaceId.equals(
                project.getWorkspace().getId()
        )) {
            throw AiFeatureException.suggestionInvalid(
                    "Project does not belong to the current workspace"
            );
        }

        if (sourceIssue != null) {
            boolean sameWorkspace = workspaceId.equals(
                    sourceIssue.getWorkspace().getId()
            );

            boolean sameProject = project.getId().equals(
                    sourceIssue.getProject().getId()
            );

            if (!sameWorkspace || !sameProject) {
                throw AiFeatureException.suggestionInvalid(
                        "Source issue does not belong to the project"
                );
            }
        }

        boolean issueSuggestion =
                command.type()
                        == AiSuggestionType.ISSUE_BREAKDOWN
                        || command.type()
                        == AiSuggestionType.ISSUE_SUMMARY;

        if (issueSuggestion && sourceIssue == null) {
            throw AiFeatureException.suggestionInvalid(
                    "Issue suggestion requires a source issue"
            );
        }

        if (command.type() == AiSuggestionType.PROJECT_SUMMARY
                && sourceIssue != null) {
            throw AiFeatureException.suggestionInvalid(
                    "Project summary cannot have a source issue"
            );
        }
    }


    private String sha256(String value) {
        if (value == null || value.isBlank()) {
            throw AiFeatureException.suggestionInvalid(
                    "Canonical AI input is required"
            );
        }

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }


    public record CreateDraftCommand(
            CurrentWorkspaceContext context,
            Project project,
            Issue sourceIssue,
            AiSuggestionType type,
            JsonNode content,
            String promptVersion,
            String provider,
            String model,
            String canonicalInput,
            Integer inputTokens,
            Integer outputTokens
    ) {
        public CreateDraftCommand {
            Objects.requireNonNull(
                    context,
                    "context is required"
            );
            Objects.requireNonNull(
                    project,
                    "project is required"
            );
            Objects.requireNonNull(
                    type,
                    "type is required"
            );
            Objects.requireNonNull(
                    content,
                    "content is required"
            );
        }
    }
}