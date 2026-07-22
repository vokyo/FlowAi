package com.vokyo.backend.ai;

import com.vokyo.backend.ai.dto.AiStatusResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class AiStatusService {

    private final AiProperties properties;
    private final ObjectProvider<AiModelGateway> gatewayProvider;

    public AiStatusService(
            AiProperties properties,
            ObjectProvider<AiModelGateway> gatewayProvider
    ) {
        this.properties = properties;
        this.gatewayProvider = gatewayProvider;
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
                false,
                null
        );
    }

    private AiStatusResponse disabled(String reason) {
        return new AiStatusResponse(
                false,
                false,
                false,
                false,
                false,
                reason
        );
    }
}
