package com.vokyo.backend.ai.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.ai.AiModelOutputException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public class SpringAiModelGateway implements AiModelGateway {

    private static final String PROVIDER = "openai";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SpringAiModelGateway(ChatModel chatModel) {
        this(chatModel, new ObjectMapper().findAndRegisterModules());
    }

    public SpringAiModelGateway(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.create(chatModel);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public <T> AiGeneration<T> generate(
            String systemPrompt,
            String userPrompt,
            Class<T> responseType
    ) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(responseType, objectMapper);
        ChatResponse response;
        try {
            response = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt + "\n\n" + converter.getFormat())
                    .call()
                    .chatResponse();
        } catch (RuntimeException exception) {
            throw mapProviderFailure(exception);
        }

        Metadata metadata = metadata(response);
        String rawOutput = rawOutput(response);
        try {
            T content = objectMapper.readerFor(responseType)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(rawOutput);
            if (content == null) {
                throw new IllegalArgumentException("Converted response is null");
            }
            return new AiGeneration<>(
                    content,
                    rawOutput,
                    PROVIDER,
                    metadata.model(),
                    metadata.inputTokens(),
                    metadata.outputTokens()
            );
        } catch (Exception exception) {
            throw new AiModelOutputException(
                    "Response does not match the required JSON schema",
                    rawOutput,
                    PROVIDER,
                    metadata.model(),
                    metadata.inputTokens(),
                    metadata.outputTokens(),
                    exception
            );
        }
    }

    private RuntimeException mapProviderFailure(RuntimeException exception) {
        if (hasCause(exception, TimeoutException.class)
                || hasCause(exception, SocketTimeoutException.class)
                || hasCause(exception, HttpTimeoutException.class)) {
            return AiFeatureException.timeout();
        }
        return AiFeatureException.providerUnavailable();
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rawOutput(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private Metadata metadata(ChatResponse response) {
        if (response == null) {
            return Metadata.empty();
        }
        ChatResponseMetadata responseMetadata = response.getMetadata();
        if (responseMetadata == null) {
            return Metadata.empty();
        }
        Usage usage = responseMetadata.getUsage();
        return new Metadata(
                responseMetadata.getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens()
        );
    }

    private record Metadata(
            String model,
            Integer inputTokens,
            Integer outputTokens
    ) {
        private static Metadata empty() {
            return new Metadata(null, null, null);
        }
    }
}
