package com.vokyo.backend.ai.summary.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGenerationRateLimiter;
import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.AiRateLimitExceededException;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService.GeneratedSummary;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService.SummaryPrompt;
import com.vokyo.backend.ai.summary.issue.dto.IssueSummaryRequest;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionMapper;
import com.vokyo.backend.ai.suggestion.AiSuggestionService;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.ai.suggestion.dto.AiSuggestionResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.Timer;

import java.util.UUID;

@Service
public class IssueSummaryService {

    private final IssueSummaryContextBuilder contextBuilder;
    private final IssueSummaryPromptFactory promptFactory;
    private final IssueSummaryValidator validator;
    private final AiSummaryGenerationService generationService;
    private final AiSuggestionService suggestionService;
    private final AiSuggestionMapper suggestionMapper;
    private final ObjectMapper objectMapper;
    private final AiGenerationRateLimiter rateLimiter;
    private final AiMetrics metrics;

    public IssueSummaryService(
            IssueSummaryContextBuilder contextBuilder,
            IssueSummaryPromptFactory promptFactory,
            IssueSummaryValidator validator,
            AiSummaryGenerationService generationService,
            AiSuggestionService suggestionService,
            AiSuggestionMapper suggestionMapper,
            ObjectMapper objectMapper,
            AiGenerationRateLimiter rateLimiter,
            AiMetrics metrics
    ) {
        this.contextBuilder = contextBuilder;
        this.promptFactory = promptFactory;
        this.validator = validator;
        this.generationService = generationService;
        this.suggestionService = suggestionService;
        this.suggestionMapper = suggestionMapper;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    public AiSuggestionResponse generate(
            Jwt jwt,
            UUID issueId,
            IssueSummaryRequest request
    ) {
        Timer.Sample timer = metrics.start();
        GeneratedSummary<IssueSummaryModelOutput> generated = null;
        String metricResult = "internal_error";
        try {
            BuiltIssueSummaryContext built = contextBuilder.build(jwt, issueId, request);
            rateLimiter.requirePermit(built.currentContext());
            SummaryPrompt prompt = promptFactory.create(built.modelContext());
            generated = generationService.generate(
                        prompt,
                        IssueSummaryModelOutput.class,
                        validator::validate,
                        promptFactory::createRepair
                );
            metrics.recordTokens(
                    "issue_summary",
                    generated.model(),
                    generated.inputTokens(),
                    generated.outputTokens()
            );
            metrics.recordGenerationMetadata(
                    "issue_summary", prompt.version(), generated.provider(), generated.model(),
                    generated.inputTokens(), generated.outputTokens()
            );
            IssueSummaryModelOutput output = generated.content();
            IssueSummaryResult result = new IssueSummaryResult(
                    output.summary(), output.decisions(), output.openQuestions(),
                    output.blockers(), output.nextActions(), built.modelContext().sourceStats()
            );
            AiSuggestion suggestion = suggestionService.createDraft(
                    new AiSuggestionService.CreateDraftCommand(
                        built.currentContext(),
                        built.project(),
                        built.sourceIssue(),
                        AiSuggestionType.ISSUE_SUMMARY,
                        objectMapper.valueToTree(result),
                        prompt.version(),
                        generated.provider(),
                        generated.model(),
                        prompt.canonicalInput(),
                        generated.inputTokens(),
                        generated.outputTokens(),
                        built.modelContext().sourceStats().contextTruncated()
                    )
            );
            metrics.recordSuggestion(AiSuggestionType.ISSUE_SUMMARY, suggestion.getStatus());
            metricResult = "success";
            return suggestionMapper.toResponse(suggestion);
        } catch (AiRateLimitExceededException exception) {
            metricResult = "rate_limited";
            throw exception;
        } catch (AiFeatureException exception) {
            metricResult = metricResult(exception);
            throw exception;
        } catch (ResponseStatusException exception) {
            metricResult = "request_rejected";
            throw exception;
        } finally {
            metrics.complete(
                    timer,
                    "issue_summary",
                    metricResult,
                    generated == null ? null : generated.provider(),
                    generated == null ? null : generated.model()
            );
        }
    }

    private String metricResult(AiFeatureException exception) {
        return switch (exception.code()) {
            case "AI_PROVIDER_UNAVAILABLE" -> "provider_unavailable";
            case "AI_PROVIDER_TIMEOUT" -> "timeout";
            case "AI_INVALID_RESPONSE" -> "invalid_response";
            default -> "failed";
        };
    }
}
