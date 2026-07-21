package com.vokyo.backend.ai.breakdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.ai.AiModelOutputException;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownRequest;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownSuggestionResponse;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionService;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class IssueBreakdownService {

    private final IssueBreakdownContextBuilder contextBuilder;
    private final IssueBreakdownPromptFactory promptFactory;
    private final ObjectProvider<AiModelGateway> gatewayProvider;
    private final IssueBreakdownValidator validator;
    private final AiSuggestionService suggestionService;
    private final ObjectMapper objectMapper;
    private final IssueBreakdownMetrics metrics;

    public IssueBreakdownService(
            IssueBreakdownContextBuilder contextBuilder,
            IssueBreakdownPromptFactory promptFactory,
            ObjectProvider<AiModelGateway> gatewayProvider,
            IssueBreakdownValidator validator,
            AiSuggestionService suggestionService,
            ObjectMapper objectMapper,
            IssueBreakdownMetrics metrics
    ) {
        this.contextBuilder = contextBuilder;
        this.promptFactory = promptFactory;
        this.gatewayProvider = gatewayProvider;
        this.validator = validator;
        this.suggestionService = suggestionService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public IssueBreakdownSuggestionResponse generate(
            Jwt jwt,
            UUID issueId,
            IssueBreakdownRequest request
    ) {
        Timer.Sample timer = metrics.start();
        AttemptTotals totals = new AttemptTotals();
        String metricResult = "internal_error";
        try {
            AiModelGateway gateway = gatewayProvider.getIfAvailable();
            if (gateway == null) {
                throw AiFeatureException.providerUnavailable();
            }

            BuiltIssueBreakdownContext built = contextBuilder.build(jwt, issueId, request);
            IssueBreakdownPromptFactory.IssueBreakdownPrompt prompt = promptFactory.create(built.modelContext());
            IssueBreakdownResult validated = generateValidated(gateway, prompt, built.modelContext(), totals);

            AiSuggestion suggestion = suggestionService.createDraft(
                    new AiSuggestionService.CreateDraftCommand(
                            built.currentContext(),
                            built.project(),
                            built.sourceIssue(),
                            AiSuggestionType.ISSUE_BREAKDOWN,
                            objectMapper.valueToTree(validated),
                            prompt.version(),
                            totals.provider,
                            totals.model,
                            prompt.canonicalInput(),
                            totals.inputTokens,
                            totals.outputTokens
                    )
            );
            metrics.recordDraft();
            metricResult = "success";
            return toResponse(suggestion, built, validated);
        } catch (AiFeatureException exception) {
            metricResult = metricResult(exception);
            throw exception;
        } catch (IssueBreakdownValidationException | AiModelOutputException exception) {
            metricResult = "invalid_response";
            throw AiFeatureException.invalidResponse();
        } catch (ResponseStatusException exception) {
            metricResult = "request_rejected";
            throw exception;
        } finally {
            metrics.complete(timer, metricResult, totals.provider, totals.model);
        }
    }

    private IssueBreakdownResult generateValidated(
            AiModelGateway gateway,
            IssueBreakdownPromptFactory.IssueBreakdownPrompt prompt,
            IssueBreakdownContext context,
            AttemptTotals totals
    ) {
        try {
            AiGeneration<IssueBreakdownResult> first = gateway.generate(
                    prompt.systemPrompt(),
                    prompt.userPrompt(),
                    IssueBreakdownResult.class
            );
            totals.add(first);
            try {
                return validator.validate(first.content(), context);
            } catch (IssueBreakdownValidationException exception) {
                return repair(gateway, first.rawOutput(), exception.getMessage(), context, totals);
            }
        } catch (AiModelOutputException exception) {
            totals.add(exception);
            return repair(gateway, exception.rawOutput(), exception.getMessage(), context, totals);
        }
    }

    private IssueBreakdownResult repair(
            AiModelGateway gateway,
            String invalidOutput,
            String validationError,
            IssueBreakdownContext context,
            AttemptTotals totals
    ) {
        IssueBreakdownPromptFactory.IssueBreakdownPrompt repairPrompt =
                promptFactory.createRepair(invalidOutput, validationError);
        try {
            AiGeneration<IssueBreakdownResult> repaired = gateway.generate(
                    repairPrompt.systemPrompt(),
                    repairPrompt.userPrompt(),
                    IssueBreakdownResult.class
            );
            totals.add(repaired);
            return validator.validate(repaired.content(), context);
        } catch (AiModelOutputException exception) {
            totals.add(exception);
            throw AiFeatureException.invalidResponse();
        } catch (IssueBreakdownValidationException exception) {
            throw AiFeatureException.invalidResponse();
        }
    }

    private IssueBreakdownSuggestionResponse toResponse(
            AiSuggestion suggestion,
            BuiltIssueBreakdownContext built,
            IssueBreakdownResult content
    ) {
        return new IssueBreakdownSuggestionResponse(
                suggestion.getId(),
                suggestion.getType(),
                suggestion.getStatus(),
                built.project().getId(),
                built.sourceIssue().getId(),
                content,
                new IssueBreakdownSuggestionResponse.Metadata(
                        suggestion.getPromptVersion(),
                        suggestion.getCreatedAt(),
                        built.modelContext().sourceStats().contextTruncated()
                ),
                suggestion.getCreatedAt(),
                suggestion.getExpiresAt()
        );
    }

    private String metricResult(AiFeatureException exception) {
        return switch (exception.code()) {
            case "AI_PROVIDER_UNAVAILABLE" -> "provider_unavailable";
            case "AI_PROVIDER_TIMEOUT" -> "timeout";
            case "AI_INVALID_RESPONSE" -> "invalid_response";
            case "AI_REQUEST_INVALID" -> "request_rejected";
            default -> "failed";
        };
    }

    private final class AttemptTotals {
        private String provider;
        private String model;
        private Integer inputTokens;
        private Integer outputTokens;

        private void add(AiGeneration<?> generation) {
            add(
                    generation.provider(),
                    generation.model(),
                    generation.inputTokens(),
                    generation.outputTokens()
            );
        }

        private void add(AiModelOutputException exception) {
            add(
                    exception.provider(),
                    exception.model(),
                    exception.inputTokens(),
                    exception.outputTokens()
            );
        }

        private void add(
                String attemptProvider,
                String attemptModel,
                Integer attemptInputTokens,
                Integer attemptOutputTokens
        ) {
            provider = attemptProvider;
            model = attemptModel;
            inputTokens = sum(inputTokens, attemptInputTokens);
            outputTokens = sum(outputTokens, attemptOutputTokens);
            metrics.recordTokens(attemptModel, attemptInputTokens, attemptOutputTokens);
        }

        private Integer sum(Integer total, Integer value) {
            if (value == null) {
                return total;
            }
            return total == null ? value : Math.addExact(total, value);
        }
    }
}
