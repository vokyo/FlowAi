package com.vokyo.backend.ai.summary.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.summary.AiSummaryGenerationService.SummaryPrompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class ProjectSummaryPromptFactory {
    public static final String VERSION = "project-summary-v1";

    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final String repairSystemPrompt;

    public ProjectSummaryPromptFactory(
            ObjectMapper objectMapper,
            @Value("classpath:prompts/project-summary-v1.st") Resource systemPrompt,
            @Value("classpath:prompts/project-summary-repair-v1.st") Resource repairSystemPrompt
    ) {
        this.objectMapper = objectMapper;
        this.systemPrompt = load(systemPrompt);
        this.repairSystemPrompt = load(repairSystemPrompt);
    }

    public SummaryPrompt create(ProjectSummaryContext context) {
        return new SummaryPrompt(
                VERSION,
                systemPrompt,
                serialize(context, "project summary context")
        );
    }

    public SummaryPrompt createRepair(String invalidOutput, String error) {
        return new SummaryPrompt(
                VERSION,
                repairSystemPrompt,
                serialize(Map.of(
                        "validationError", error,
                        "invalidOutput", invalidOutput
                ), "project summary repair input")
        );
    }

    private String serialize(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize " + label, exception);
        }
    }

    private String load(Resource resource) {
        try {
            String prompt = resource.getContentAsString(StandardCharsets.UTF_8).strip();
            if (prompt.isBlank()) throw new IllegalStateException("Project summary prompt is empty");
            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load project summary prompt", exception);
        }
    }
}
