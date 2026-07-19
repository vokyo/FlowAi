package com.vokyo.backend.ai;

import com.vokyo.backend.ai.springai.SpringAiModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class)
            .withPropertyValues(
                    "app.ai.enabled=false",
                    "app.ai.suggestion-ttl=7d",
                    "app.ai.request-timeout=30s",
                    "app.ai.max-breakdown-items=8",
                    "app.ai.include-comments-limit=20",
                    "app.ai.include-activity-limit=20",
                    "app.ai.max-context-issues=100"
            );

    @Test
    void bindsAiProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.suggestionTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.maxBreakdownItems()).isEqualTo(8);
            assertThat(properties.includeCommentsLimit()).isEqualTo(20);
            assertThat(properties.includeActivityLimit()).isEqualTo(20);
            assertThat(properties.maxContextIssues()).isEqualTo(100);
        });
    }

    @Test
    void doesNotCreateGatewayWhenAiIsDisabled() {
        contextRunner
                .withBean(ChatModel.class, () -> prompt -> null)
                .run(context -> assertThat(context).doesNotHaveBean(AiModelGateway.class));
    }

    @Test
    void doesNotCreateGatewayWhenChatModelIsMissing() {
        contextRunner
                .withPropertyValues("app.ai.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AiModelGateway.class));
    }

    @Test
    void createsGatewayWhenAiAndChatModelAreAvailable() {
        contextRunner
                .withPropertyValues("app.ai.enabled=true")
                .withBean(ChatModel.class, () -> prompt -> null)
                .run(context -> {
                    assertThat(context).hasSingleBean(AiModelGateway.class);
                    assertThat(context.getBean(AiModelGateway.class))
                            .isInstanceOf(SpringAiModelGateway.class);
                });
    }
}
