package com.vokyo.backend.ai.breakdown;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

@Component
public class IssueBreakdownPromptFactory {

    public static final String VERSION = "issue-breakdown-v1";

    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final String repairSystemPrompt;

    public IssueBreakdownPromptFactory(
            ObjectMapper objectMapper,
            @Value("classpath:prompts/issue-breakdown-v1.st")
            Resource systemPromptResource,
            @Value("classpath:prompts/issue-breakdown-repair-v1.st")
            Resource repairSystemPromptResource
    ) {
        this.objectMapper = objectMapper;
        this.systemPrompt = loadPrompt(systemPromptResource);
        this.repairSystemPrompt = loadPrompt(repairSystemPromptResource);
    }

    public IssueBreakdownPrompt createRepair(
            String invalidOutput,
            String validationError
    ) {
        Objects.requireNonNull(invalidOutput, "invalidOutput is required");
        Objects.requireNonNull(validationError, "validationError is required");
        try {
            return new IssueBreakdownPrompt(
                    VERSION,
                    repairSystemPrompt,
                    objectMapper.writeValueAsString(Map.of(
                            "validationError", validationError,
                            "invalidOutput", invalidOutput
                    ))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize issue breakdown repair input",
                    exception
            );
        }
    }

    public IssueBreakdownPrompt create(IssueBreakdownContext context) {
        try {
            return new IssueBreakdownPrompt(
                    VERSION,
                    systemPrompt,
                    objectMapper.writeValueAsString(context)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize issue breakdown context",
                    exception
            );
        }
    }

    private String loadPrompt(Resource resource) {
        try {
            String prompt = resource.getContentAsString(StandardCharsets.UTF_8).strip();

            if (prompt.isBlank()) {
                throw new IllegalStateException(
                        "Issue breakdown system prompt is empty"
                );
            }

            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load issue breakdown system prompt",
                    exception
            );
        }
    }

    public record IssueBreakdownPrompt(
            String version,
            String systemPrompt,
            String userPrompt
    ) {
        public String canonicalInput() {
            return version + "\n" + systemPrompt + "\n" + userPrompt;
        }
    }
}
