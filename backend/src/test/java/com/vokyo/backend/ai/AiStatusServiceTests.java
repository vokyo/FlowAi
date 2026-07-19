package com.vokyo.backend.ai;

import com.vokyo.backend.ai.dto.AiStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiStatusServiceTests {

    private static final AiModelGateway AVAILABLE_GATEWAY = new AiModelGateway() {
        @Override
        public <T> T generate(String systemPrompt, String userPrompt, Class<T> responseType) {
            return null;
        }
    };

    @Test
    void reportsAiDisabledBeforeCheckingTheGateway() {
        AiStatusResponse response = service(false, AVAILABLE_GATEWAY).getStatus();

        assertThat(response.enabled()).isFalse();
        assertThat(response.disabledReason()).isEqualTo("AI_DISABLED");
        assertThat(response.breakdownAvailable()).isFalse();
        assertThat(response.issueSummaryAvailable()).isFalse();
        assertThat(response.projectSummaryAvailable()).isFalse();
        assertThat(response.agentAvailable()).isFalse();
    }

    @Test
    void reportsProviderUnavailableWhenEnabledWithoutGateway() {
        AiStatusResponse response = service(true, null).getStatus();

        assertThat(response.enabled()).isFalse();
        assertThat(response.disabledReason()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
    }

    @Test
    void reportsProviderReadyWithoutClaimingUnimplementedFeatures() {
        AiStatusResponse response = service(true, AVAILABLE_GATEWAY).getStatus();

        assertThat(response.enabled()).isTrue();
        assertThat(response.disabledReason()).isNull();
        assertThat(response.breakdownAvailable()).isFalse();
        assertThat(response.issueSummaryAvailable()).isFalse();
        assertThat(response.projectSummaryAvailable()).isFalse();
        assertThat(response.agentAvailable()).isFalse();
    }

    private AiStatusService service(boolean enabled, AiModelGateway gateway) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (gateway != null) {
            beanFactory.addBean("aiModelGateway", gateway);
        }
        return new AiStatusService(
                properties(enabled),
                beanFactory.getBeanProvider(AiModelGateway.class)
        );
    }

    private AiProperties properties(boolean enabled) {
        return new AiProperties(
                enabled,
                Duration.ofDays(7),
                Duration.ofSeconds(30),
                8,
                20,
                20,
                100
        );
    }
}
