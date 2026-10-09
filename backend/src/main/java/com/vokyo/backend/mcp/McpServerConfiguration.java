package com.vokyo.backend.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** The MCP server Spring AI runs at /api/mcp, with FlowAI's tools and its caller. */
@Configuration
class McpServerConfiguration {

    static final String CALLER = "caller";

    /**
     * Replaces Spring AI's stateless transport only to hand each tool call its caller.
     * The tools run on another thread than the request, where Spring Security's context
     * is not set, so the caller found on the request travels with the call instead.
     */
    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            ObjectMapper objectMapper,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}") String endpoint
    ) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(endpoint)
                .contextExtractor(request -> request.principal()
                        .flatMap(PersonalAccessTokenIntrospector::caller)
                        .map(caller -> McpTransportContext.create(Map.of(CALLER, caller)))
                        .orElse(McpTransportContext.EMPTY))
                .build();
    }

    /**
     * One list rather than a bean per tool: Spring AI's stateless server gathers beans
     * that are lists of tools and flattens them, and passes over single tools.
     */
    @Bean
    List<SyncToolSpecification> flowAiTools(McpProjectTools tools) {
        return List.of(tools.listProjects(), tools.searchIssues(), tools.listProjectMembers());
    }
}
