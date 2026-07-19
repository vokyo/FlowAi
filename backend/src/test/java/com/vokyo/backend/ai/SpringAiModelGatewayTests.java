package com.vokyo.backend.ai;

import com.vokyo.backend.ai.springai.SpringAiModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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

        TestResponse response = gateway.generate(
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
        assertThat(response.value()).isEqualTo("ok");
    }

    private record TestResponse(String value) {
    }
}
