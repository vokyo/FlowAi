package com.vokyo.backend.agent.internal;

import com.vokyo.backend.agent.AgentAccessService;
import com.vokyo.backend.ai.TextEmbedder;
import com.vokyo.backend.agent.internal.dto.AgentIssueSearchResponse;
import com.vokyo.backend.agent.internal.dto.AgentProjectMembersResponse;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueQueryService;
import com.vokyo.backend.issue.IssueSearchService;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only project data for the planning agent. The project always comes from the
 * agent token and never from the request, and every call re-checks that the user
 * behind the token can still read it.
 */
@Service
public class AgentProjectQueryService {

    private static final Logger log = LoggerFactory.getLogger(AgentProjectQueryService.class);

    public static final int MAX_ISSUE_RESULTS = 20;
    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_MEMBER_RESULTS = 50;

    private final AgentAccessService agentAccessService;
    private final IssueQueryService issueQueryService;
    private final IssueSearchService issueSearchService;
    private final ProjectAccessService projectAccessService;
    private final ObjectProvider<TextEmbedder> textEmbedder;
    private final TransactionTemplate readOnlyTransaction;

    public AgentProjectQueryService(
        AgentAccessService agentAccessService,
        IssueQueryService issueQueryService,
        IssueSearchService issueSearchService,
        ProjectAccessService projectAccessService,
        ObjectProvider<TextEmbedder> textEmbedder,
        PlatformTransactionManager transactionManager
    ) {
        this.agentAccessService = agentAccessService;
        this.issueQueryService = issueQueryService;
        this.issueSearchService = issueSearchService;
        this.projectAccessService = projectAccessService;
        this.textEmbedder = textEmbedder;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * Two short read-only transactions instead of one: a semantic search calls the
     * embedding API between them, and no database connection should wait on that call.
     */
    public AgentIssueSearchResponse searchIssues(Jwt agentJwt, String query, int limit, String mode) {
        SearchScope scope = Objects.requireNonNull(readOnlyTransaction.execute(status -> {
            Project project = agentAccessService.requireAccessibleProject(agentJwt);
            return new SearchScope(project.getWorkspace().getId(), project.getId());
        }));
        AgentSearchMode searchMode = AgentSearchMode.parse(mode)
            .orElseThrow(() -> badRequest("mode must be one of " + AgentSearchMode.ACCEPTED));
        return searchIssues(scope.workspaceId(), scope.projectId(), query, limit, searchMode);
    }

    /**
     * The search itself, for a project the caller has already been checked against.
     * The MCP endpoint shares it with the agent's internal endpoint.
     */
    public AgentIssueSearchResponse searchIssues(
        UUID workspaceId,
        UUID projectId,
        String query,
        int limit,
        AgentSearchMode searchMode
    ) {
        SearchScope scope = new SearchScope(workspaceId, projectId);
        String normalizedQuery = normalizeQuery(query);
        if (limit < 1 || limit > MAX_ISSUE_RESULTS) {
            throw badRequest("limit must be between 1 and " + MAX_ISSUE_RESULTS);
        }
        // Without a query every mode lists the newest issues, which needs no matching at all.
        AgentSearchMode effectiveMode = normalizedQuery == null ? AgentSearchMode.KEYWORD : searchMode;
        float[] queryVector = effectiveMode == AgentSearchMode.SEMANTIC ? embed(normalizedQuery) : null;

        // Asking for one more than we return is how we know the project has more matches.
        int maxResults = limit + 1;
        return readOnlyTransaction.execute(status -> {
            List<Issue> issues = switch (effectiveMode) {
                case KEYWORD -> issueQueryService.searchActiveIssues(
                    scope.workspaceId(), scope.projectId(), normalizedQuery, maxResults);
                case FULLTEXT -> issueSearchService.searchByFullText(
                    scope.workspaceId(), scope.projectId(), normalizedQuery, maxResults);
                case SEMANTIC -> issueSearchService.searchBySimilarity(
                    scope.workspaceId(), scope.projectId(), queryVector, maxResults);
            };
            return new AgentIssueSearchResponse(
                issues.stream().limit(limit).map(this::toIssueItem).toList(),
                issues.size() > limit
            );
        });
    }

    @Transactional(readOnly = true)
    public AgentProjectMembersResponse listMembers(Jwt agentJwt) {
        return listMembers(agentAccessService.requireAccessibleProject(agentJwt));
    }

    /** The active members of a project the caller has already been checked against. */
    @Transactional(readOnly = true)
    public AgentProjectMembersResponse listMembers(Project project) {
        List<ProjectMember> activeMembers = projectAccessService.listActiveProjectMembers(project);
        return new AgentProjectMembersResponse(
            activeMembers.stream().limit(MAX_MEMBER_RESULTS).map(this::toMemberItem).toList(),
            activeMembers.size() > MAX_MEMBER_RESULTS
        );
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.trim();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw badRequest("q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        return normalized;
    }

    private AgentIssueSearchResponse.Item toIssueItem(Issue issue) {
        User assignee = issue.getAssigneeUser();
        return new AgentIssueSearchResponse.Item(
            issue.getId(),
            issue.getTitle(),
            issue.getWorkflowState().getCategory().toIssueStatus(),
            issue.getPriority(),
            assignee == null ? null : assignee.getId(),
            assignee == null ? null : assignee.getDisplayName()
        );
    }

    private AgentProjectMembersResponse.Item toMemberItem(ProjectMember member) {
        return new AgentProjectMembersResponse.Item(
            member.getUser().getId(),
            member.getUser().getDisplayName(),
            member.getRole()
        );
    }

    /** Semantic when issue embeddings are on, keyword otherwise. */
    public AgentSearchMode defaultSearchMode() {
        return textEmbedder.getIfAvailable() != null ? AgentSearchMode.SEMANTIC : AgentSearchMode.KEYWORD;
    }

    private float[] embed(String query) {
        TextEmbedder embedder = textEmbedder.getIfAvailable();
        if (embedder == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "semantic search is not enabled");
        }
        try {
            return embedder.embed(query);
        } catch (RuntimeException exception) {
            log.warn("event=agent_search_embedding_failed reason={}", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "embedding request failed");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record SearchScope(UUID workspaceId, UUID projectId) {
    }
}
