package com.vokyo.backend.ai.springai;

import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiModelGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

public class SpringAiModelGateway implements AiModelGateway {

    private final ChatClient chatClient;

    public SpringAiModelGateway(ChatModel chatModel) {
        this.chatClient = ChatClient.create(chatModel);
    }

    @Override
    public <T> T generate(String systemPrompt, String userPrompt, Class<T> responseType) {
        T result = chatClient
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(responseType);

        if (result == null) {
            throw AiFeatureException.invalidResponse();
        }
        return result;
    }
}
