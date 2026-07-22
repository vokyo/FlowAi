package com.vokyo.backend.ai.summary;

import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.ai.AiModelOutputException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;
import java.util.function.Function;

@Service
public class AiSummaryGenerationService {

    private final ObjectProvider<AiModelGateway> gatewayProvider;

    public AiSummaryGenerationService(
            ObjectProvider<AiModelGateway> gatewayProvider
    ) {
        this.gatewayProvider = gatewayProvider;
    }

    public <T> GeneratedSummary<T> generate(
            SummaryPrompt prompt,
            Class<T> responseType,
            Function<T, T> validator,
            BiFunction<String, String, SummaryPrompt> repairPromptFactory
    ) {
        AiModelGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw AiFeatureException.providerUnavailable();
        }

        Totals totals = new Totals();
        try {
            AiGeneration<T> first = gateway.generate(
                    prompt.systemPrompt(),
                    prompt.userPrompt(),
                    responseType
            );
            totals.add(first);
            try {
                return totals.result(validator.apply(first.content()));
            } catch (AiSummaryValidationException exception) {
                return repair(
                        gateway,
                        responseType,
                        validator,
                        repairPromptFactory,
                        first.rawOutput(),
                        exception.getMessage(),
                        totals
                );
            }
        } catch (AiModelOutputException exception) {
            totals.add(exception);
            return repair(
                    gateway,
                    responseType,
                    validator,
                    repairPromptFactory,
                    exception.rawOutput(),
                    exception.getMessage(),
                    totals
            );
        }
    }

    private <T> GeneratedSummary<T> repair(
            AiModelGateway gateway,
            Class<T> responseType,
            Function<T, T> validator,
            BiFunction<String, String, SummaryPrompt> repairPromptFactory,
            String invalidOutput,
            String validationError,
            Totals totals
    ) {
        SummaryPrompt repair = repairPromptFactory.apply(
                invalidOutput,
                validationError
        );
        try {
            AiGeneration<T> repaired = gateway.generate(
                    repair.systemPrompt(),
                    repair.userPrompt(),
                    responseType
            );
            totals.add(repaired);
            return totals.result(validator.apply(repaired.content()));
        } catch (AiModelOutputException exception) {
            totals.add(exception);
            throw AiFeatureException.invalidResponse();
        } catch (AiSummaryValidationException exception) {
            throw AiFeatureException.invalidResponse();
        }
    }

    public record SummaryPrompt(
            String version,
            String systemPrompt,
            String userPrompt
    ) {
        public String canonicalInput() {
            return version + "\n" + systemPrompt + "\n" + userPrompt;
        }
    }

    public record GeneratedSummary<T>(
            T content,
            String provider,
            String model,
            Integer inputTokens,
            Integer outputTokens
    ) {
    }

    private static final class Totals {
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
        }

        private Integer sum(Integer total, Integer value) {
            if (value == null) return total;
            return total == null ? value : Math.addExact(total, value);
        }

        private <T> GeneratedSummary<T> result(T content) {
            return new GeneratedSummary<>(
                    content,
                    provider,
                    model,
                    inputTokens,
                    outputTokens
            );
        }
    }
}
