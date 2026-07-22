package com.vokyo.backend.ai.summary.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGenerationRateLimiter;
import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.AiRateLimitExceededException;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService.GeneratedSummary;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService.SummaryPrompt;
import com.vokyo.backend.ai.summary.project.dto.ProjectSummaryRequest;
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
public class ProjectSummaryService {
    private final ProjectSummaryContextBuilder contextBuilder;
    private final ProjectSummaryPromptFactory promptFactory;
    private final ProjectSummaryValidator validator;
    private final AiSummaryGenerationService generationService;
    private final AiSuggestionService suggestionService;
    private final AiSuggestionMapper suggestionMapper;
    private final ObjectMapper objectMapper;
    private final AiGenerationRateLimiter rateLimiter;
    private final AiMetrics metrics;

    public ProjectSummaryService(
            ProjectSummaryContextBuilder contextBuilder,
            ProjectSummaryPromptFactory promptFactory,
            ProjectSummaryValidator validator,
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

    public AiSuggestionResponse generate(Jwt jwt, UUID projectId, ProjectSummaryRequest request) {
        Timer.Sample timer = metrics.start();
        GeneratedSummary<ProjectSummaryModelOutput> generated = null;
        String metricResult = "internal_error";
        try {
            BuiltProjectSummaryContext built = contextBuilder.build(jwt, projectId, request);
            rateLimiter.requirePermit(built.currentContext());
            SummaryPrompt prompt = promptFactory.create(built.modelContext());
            generated = generationService.generate(
                    prompt, ProjectSummaryModelOutput.class,
                    validator::validate, promptFactory::createRepair
            );
            metrics.recordTokens(
                    "project_summary", generated.model(),
                    generated.inputTokens(), generated.outputTokens()
            );
            metrics.recordGenerationMetadata(
                    "project_summary", prompt.version(), generated.provider(), generated.model(),
                    generated.inputTokens(), generated.outputTokens()
            );
            ProjectSummaryModelOutput output = generated.content();
            ProjectSummaryResult result = new ProjectSummaryResult(
                    output.executiveSummary(), output.progressHighlights(),
                    output.currentRisks(), output.blockers(),
                    output.workloadObservations(), output.recommendedNextActions(),
                    built.modelContext().sourceStats()
            );
            AiSuggestion suggestion = suggestionService.createDraft(
                    new AiSuggestionService.CreateDraftCommand(
                        built.currentContext(),
                        built.project(),
                        null,
                        AiSuggestionType.PROJECT_SUMMARY,
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
            metrics.recordSuggestion(AiSuggestionType.PROJECT_SUMMARY, suggestion.getStatus());
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
                    timer, "project_summary", metricResult,
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
