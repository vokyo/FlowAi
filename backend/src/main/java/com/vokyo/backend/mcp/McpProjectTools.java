package com.vokyo.backend.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.accesstoken.AccessTokenPrincipal;
import com.vokyo.backend.agent.internal.AgentProjectQueryService;
import com.vokyo.backend.agent.internal.AgentSearchMode;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What an AI app can do over MCP, all of it read-only: list the workspace's projects,
 * search a project's issues, and list a project's members. Every call checks the
 * caller's access again, and the search is the same one the planning agent uses.
 * A refused call is a tool result marked as an error, which the app's model reads.
 */
@Component
class McpProjectTools {

    private static final String PROJECT_ID = "projectId";

    private static final String LIST_PROJECTS_DESCRIPTION = """
            List the projects in this FlowAI workspace that you can open, with their ids. \
            Call it first: the other tools take a projectId from it.""";

    private static final String MEMBERS_DESCRIPTION = """
            List the active members of one project: their user ids, display names and \
            roles in the project.""";

    private static final String NO_ARGUMENTS = """
            {"type": "object", "properties": {}, "additionalProperties": false}""";

    private static final String PROJECT_ONLY = """
            {"type": "object",
             "properties": {"projectId": {"type": "string", "description": "A project id from list_projects."}},
             "required": ["projectId"],
             "additionalProperties": false}""";

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;
    private final AgentProjectQueryService queryService;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper jsonMapper;
    private final TransactionTemplate readOnlyTransaction;

    McpProjectTools(
            WorkspaceAccessService workspaceAccessService,
            ProjectAccessService projectAccessService,
            AgentProjectQueryService queryService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
        this.queryService = queryService;
        this.objectMapper = objectMapper;
        this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    SyncToolSpecification listProjects() {
        return tool("list_projects", "List projects", LIST_PROJECTS_DESCRIPTION, NO_ARGUMENTS,
                (caller, arguments) -> readOnly(() -> new ProjectList(
                        projectAccessService.listAccessibleProjects(context(caller)).stream()
                                .filter(project -> project.getArchivedAt() == null)
                                .map(project -> new ProjectItem(project.getId(), project.getName(), project.getDescription()))
                                .toList()
                )));
    }

    SyncToolSpecification searchIssues() {
        // Fixed when the server starts: whether issue embeddings are on decides how a
        // query matches, and the description tells the model which it gets.
        AgentSearchMode mode = queryService.defaultSearchMode();
        return tool("search_issues", "Search issues", searchDescription(mode), searchSchema(mode),
                (caller, arguments) -> {
                    UUID projectId = projectId(arguments);
                    String query = optionalString(arguments, "query");
                    int limit = optionalInteger(arguments, "limit", AgentProjectQueryService.MAX_ISSUE_RESULTS);
                    UUID workspaceId = readOnly(() -> accessibleProject(caller, projectId).getWorkspace().getId());
                    return queryService.searchIssues(workspaceId, projectId, query, limit, mode);
                });
    }

    SyncToolSpecification listProjectMembers() {
        return tool("list_project_members", "List project members", MEMBERS_DESCRIPTION, PROJECT_ONLY,
                (caller, arguments) -> {
                    UUID projectId = projectId(arguments);
                    return readOnly(() -> queryService.listMembers(accessibleProject(caller, projectId)));
                });
    }

    private SyncToolSpecification tool(String name, String title, String description, String inputSchema, Work work) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(jsonMapper, inputSchema)
                .annotations(new McpSchema.ToolAnnotations(title, true, false, true, false, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> call(context, request.arguments(), work))
                .build();
    }

    private CallToolResult call(McpTransportContext context, Map<String, Object> arguments, Work work) {
        if (!(context.get(McpServerConfiguration.CALLER) instanceof AccessTokenPrincipal caller)) {
            return refused("This call carries no personal access token.");
        }
        try {
            Object result = work.run(caller, arguments == null ? Map.of() : arguments);
            return CallToolResult.builder().addTextContent(objectMapper.writeValueAsString(result)).isError(false).build();
        } catch (ResponseStatusException exception) {
            return refused(exception.getReason());
        } catch (InvalidArgumentException exception) {
            return refused(exception.getMessage());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("A tool result could not be written as JSON", exception);
        }
    }

    private CurrentWorkspaceContext context(AccessTokenPrincipal caller) {
        return workspaceAccessService.requireContext(caller.userId(), caller.membershipId());
    }

    private Project accessibleProject(AccessTokenPrincipal caller, UUID projectId) {
        return projectAccessService.requireAccessibleProject(projectId, context(caller));
    }

    private <T> T readOnly(Supplier<T> work) {
        return Objects.requireNonNull(readOnlyTransaction.execute(status -> work.get()));
    }

    private static CallToolResult refused(String reason) {
        return CallToolResult.builder().addTextContent(reason == null ? "The call was refused." : reason).isError(true).build();
    }

    private static UUID projectId(Map<String, Object> arguments) {
        if (arguments.get(PROJECT_ID) instanceof String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Falls through to the same answer as a missing id.
            }
        }
        throw new InvalidArgumentException("projectId must be a project id from list_projects.");
    }

    private static String optionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new InvalidArgumentException(name + " must be text.");
    }

    private static int optionalInteger(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        throw new InvalidArgumentException(name + " must be a whole number.");
    }

    private static String searchDescription(AgentSearchMode mode) {
        String howItMatches = mode == AgentSearchMode.SEMANTIC
                ? "Issues come back ordered by how close their meaning is to the query, whatever words or "
                    + "language they use, and a search returns up to limit issues even when none is related: "
                    + "read the titles and decide which ones matter. truncated only means that less similar "
                    + "issues exist."
                : "The query is matched as one exact piece of text, ignoring case, in an issue's title or "
                    + "description, so an empty result only means no issue contains that text: try a shorter "
                    + "query, a synonym, or the language the issues are written in. If truncated is true, there "
                    + "are more matches.";
        return "Search the existing issues of one project, by a projectId from list_projects. " + howItMatches
                + " Without a query it lists the newest issues.";
    }

    private static String searchSchema(AgentSearchMode mode) {
        String query = mode == AgentSearchMode.SEMANTIC
                ? "A few words describing the work you are looking for, in any language."
                : "One short word or phrase that appears in the issues.";
        return """
                {"type": "object",
                 "properties": {
                   "projectId": {"type": "string", "description": "A project id from list_projects."},
                   "query": {"type": "string", "maxLength": 100, "description": "%s"},
                   "limit": {"type": "integer", "minimum": 1, "maximum": %d, "description": "How many issues to return."}
                 },
                 "required": ["projectId"],
                 "additionalProperties": false}""".formatted(query, AgentProjectQueryService.MAX_ISSUE_RESULTS);
    }

    @FunctionalInterface
    private interface Work {
        Object run(AccessTokenPrincipal caller, Map<String, Object> arguments);
    }

    private static final class InvalidArgumentException extends RuntimeException {
        InvalidArgumentException(String message) {
            super(message);
        }
    }

    record ProjectList(List<ProjectItem> projects) {
    }

    record ProjectItem(UUID id, String name, String description) {
    }
}
