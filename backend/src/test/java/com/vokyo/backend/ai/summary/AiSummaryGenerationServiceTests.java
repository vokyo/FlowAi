package com.vokyo.backend.ai.summary;

import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.ai.AiModelOutputException;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService.SummaryPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSummaryGenerationServiceTests {

    @Test
    void repairsOnceAndAccumulatesTokensUsingFinalMetadata() {
        SequenceGateway gateway = new SequenceGateway(
                generation("invalid", "first-model", 3, 4),
                generation("valid", "final-model", 5, 6)
        );
        AiSummaryGenerationService service = new AiSummaryGenerationService(
                provider(gateway)
        );

        var result = service.generate(
                new SummaryPrompt("v1", "normal", "context"),
                String.class,
                value -> {
                    if (!"valid".equals(value)) {
                        throw new AiSummaryValidationException("invalid value");
                    }
                    return value;
                },
                (raw, error) -> new SummaryPrompt(
                        "v1",
                        "repair",
                        raw + ":" + error
                )
        );

        assertThat(gateway.systemPrompts).containsExactly("normal", "repair");
        assertThat(gateway.userPrompts.get(1)).contains("raw-invalid", "invalid value");
        assertThat(result.content()).isEqualTo("valid");
        assertThat(result.model()).isEqualTo("final-model");
        assertThat(result.inputTokens()).isEqualTo(8);
        assertThat(result.outputTokens()).isEqualTo(10);
    }

    @Test
    void mapsSecondConversionFailureToInvalidResponse() {
        AiModelOutputException invalid = new AiModelOutputException(
                "schema", "bad", "fake", "model", 1, 1, null
        );
        AiSummaryGenerationService service = new AiSummaryGenerationService(
                provider(new SequenceGateway(invalid, invalid))
        );

        assertThatThrownBy(() -> service.generate(
                new SummaryPrompt("v1", "normal", "context"),
                String.class,
                value -> value,
                (raw, error) -> new SummaryPrompt("v1", "repair", raw + error)
        )).isInstanceOfSatisfying(AiFeatureException.class, exception ->
                assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE")
        );
    }

    private AiGeneration<String> generation(
            String value,
            String model,
            int inputTokens,
            int outputTokens
    ) {
        return new AiGeneration<>(
                value,
                "raw-" + value,
                "fake",
                model,
                inputTokens,
                outputTokens
        );
    }

    private ObjectProvider<AiModelGateway> provider(AiModelGateway gateway) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("gateway", gateway);
        return beanFactory.getBeanProvider(AiModelGateway.class);
    }

    private static final class SequenceGateway implements AiModelGateway {
        private final ArrayDeque<Object> responses = new ArrayDeque<>();
        private final java.util.ArrayList<String> systemPrompts = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> userPrompts = new java.util.ArrayList<>();

        private SequenceGateway(Object... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public <T> AiGeneration<T> generate(
                String systemPrompt,
                String userPrompt,
                Class<T> responseType
        ) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            Object next = responses.removeFirst();
            if (next instanceof RuntimeException exception) throw exception;
            @SuppressWarnings("unchecked")
            AiGeneration<T> generation = (AiGeneration<T>) next;
            return generation;
        }
    }
}
