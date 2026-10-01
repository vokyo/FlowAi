package com.vokyo.backend.agent.internal;

import com.vokyo.backend.agent.AgentAccessService;
import com.vokyo.backend.agent.internal.dto.AgentIssueSearchResponse;
import com.vokyo.backend.agent.internal.dto.AgentProjectMembersResponse;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueQueryService;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Read-only project data for the planning agent. The project always comes from the
 * agent token and never from the request, and every call re-checks that the user
 * behind the token can still read it.
 */
@Service
public class AgentProjectQueryService {

    static final int MAX_ISSUE_RESULTS = 20;
    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_MEMBER_RESULTS = 50;

    private final AgentAccessService agentAccessService;
    private final IssueQueryService issueQueryService;
    private final ProjectAccessService projectAccessService;

    public AgentProjectQueryService(
        AgentAccessService agentAccessService,
        IssueQueryService issueQueryService,
        ProjectAccessService projectAccessService
    ) {
        this.agentAccessService = agentAccessService;
        this.issueQueryService = issueQueryService;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public AgentIssueSearchResponse searchIssues(Jwt agentJwt, String query, int limit) {
        Project project = agentAccessService.requireAccessibleProject(agentJwt);
        String normalizedQuery = normalizeQuery(query);
        if (limit < 1 || limit > MAX_ISSUE_RESULTS) {
            throw badRequest("limit must be between 1 and " + MAX_ISSUE_RESULTS);
        }

        // Asking for one more than we return is how we know the project has more matches.
        List<Issue> issues = issueQueryService.searchActiveIssues(project, normalizedQuery, limit + 1);
        return new AgentIssueSearchResponse(
            issues.stream().limit(limit).map(this::toIssueItem).toList(),
            issues.size() > limit
        );
    }

    @Transactional(readOnly = true)
    public AgentProjectMembersResponse listMembers(Jwt agentJwt) {
        Project project = agentAccessService.requireAccessibleProject(agentJwt);
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

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
