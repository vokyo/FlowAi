package com.vokyo.backend.ai;

public interface AiModelGateway {

    <T> AiGeneration<T> generate(
            String systemPrompt,
            String userPrompt,
            Class<T> responseType
    );
}
