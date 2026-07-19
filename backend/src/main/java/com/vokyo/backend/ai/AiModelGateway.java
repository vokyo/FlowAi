package com.vokyo.backend.ai;

public interface AiModelGateway {

    <T> T generate(
            String systemPrompt,
            String userPrompt,
            Class<T> responseType
    );
}