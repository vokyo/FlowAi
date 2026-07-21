package com.vokyo.backend.ai;

import com.vokyo.backend.ai.springai.SpringAiModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiModelGatewayTests {

    @Test
    void keepsSystemInstructionsSeparateFromUserContent() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("{\"value\":\"ok\"}"))
            ));
        };
        SpringAiModelGateway gateway = new SpringAiModelGateway(chatModel);

        AiGeneration<TestResponse> response = gateway.generate(
                "Trusted system instruction",
                "Untrusted issue description",
                TestResponse.class
        );

        Prompt prompt = capturedPrompt.get();

        assertThat(prompt).isNotNull();
        assertThat(prompt.getSystemMessage().getText())
                .isEqualTo("Trusted system instruction");
        assertThat(prompt.getUserMessage().getText())
                .contains("Untrusted issue description");
        assertThat(prompt.getUserMessage().getText())
                .contains("Untrusted issue description")
                .contains("JSON Schema");
        assertThat(response.content().value()).isEqualTo("ok");
        assertThat(response.rawOutput()).isEqualTo("{\"value\":\"ok\"}");
        assertThat(response.provider()).isEqualTo("openai");
    }

    @Test
    void returnsProviderMetadataAndTokenUsage() {
        ChatModel chatModel = prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"value\":\"ok\"}"))),
                ChatResponseMetadata.builder()
                        .model("gpt-test")
                        .usage(new DefaultUsage(11, 7))
                        .build()
        );

        AiGeneration<TestResponse> response = new SpringAiModelGateway(chatModel).generate(
                "system",
                "user",
                TestResponse.class
        );

        assertThat(response.model()).isEqualTo("gpt-test");
        assertThat(response.inputTokens()).isEqualTo(11);
        assertThat(response.outputTokens()).isEqualTo(7);
    }

    @Test
    void keepsInvalidStructuredOutputForOneRepairAttempt() {
        ChatModel chatModel = prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("not-json"))
        ));

        assertThatThrownBy(() ->
                        new SpringAiModelGateway(chatModel).generate("system", "user", TestResponse.class))
                .isInstanceOf(AiModelOutputException.class)
                .satisfies(exception -> assertThat(((AiModelOutputException) exception).rawOutput())
                        .isEqualTo("not-json"));
    }

    @Test
    void rejectsFieldsOutsideTheDeclaredSchema() {
        ChatModel chatModel = prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"value\":\"ok\",\"workspaceId\":\"forbidden\"}"))
        ));

        assertThatThrownBy(() -> new SpringAiModelGateway(chatModel)
                .generate("system", "user", TestResponse.class))
                .isInstanceOf(AiModelOutputException.class);
    }

    @Test
    void mapsTimeoutsAndProviderFailuresToStableErrors() {
        ChatModel timeoutModel = prompt -> {
            throw new IllegalStateException(new SocketTimeoutException("provider details"));
        };
        assertThatThrownBy(() -> new SpringAiModelGateway(timeoutModel)
                .generate("system", "user", TestResponse.class))
                .isInstanceOf(AiFeatureException.class)
                .satisfies(exception -> assertThat(((AiFeatureException) exception).code())
                        .isEqualTo("AI_PROVIDER_TIMEOUT"));

        ChatModel unavailableModel = prompt -> {
            throw new IllegalStateException("provider details");
        };
        assertThatThrownBy(() -> new SpringAiModelGateway(unavailableModel)
                .generate("system", "user", TestResponse.class))
                .isInstanceOf(AiFeatureException.class)
                .satisfies(exception -> assertThat(((AiFeatureException) exception).code())
                        .isEqualTo("AI_PROVIDER_UNAVAILABLE"));
    }

    private record TestResponse(String value) {
    }
}
