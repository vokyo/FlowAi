package com.vokyo.backend.ai;

import com.vokyo.backend.ai.dto.AiStatusResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * What the AI features can do on this deployment. The planning agent is a separate
 * service with a switch of its own, so it can be on while the Copilot is off and the
 * other way round; the switch is read as a property so this package does not depend
 * on the agent's.
 */
@Service
public class AiStatusService {

    private final AiProperties properties;
    private final ObjectProvider<AiModelGateway> gatewayProvider;
    private final boolean agentEnabled;

    public AiStatusService(
            AiProperties properties,
            ObjectProvider<AiModelGateway> gatewayProvider,
            @Value("${app.agent.enabled:false}") boolean agentEnabled
    ) {
        this.properties = properties;
        this.gatewayProvider = gatewayProvider;
        this.agentEnabled = agentEnabled;
    }

    public AiStatusResponse getStatus() {
        if (!properties.enabled()) {
            return disabled("AI_DISABLED");
        }

        AiModelGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return disabled("AI_PROVIDER_UNAVAILABLE");
        }

        return new AiStatusResponse(
                true,
                true,
                true,
                true,
                agentEnabled,
                null
        );
    }

    private AiStatusResponse disabled(String reason) {
        return new AiStatusResponse(
                false,
                false,
                false,
                false,
                agentEnabled,
                reason
        );
    }
}
