package com.vokyo.backend.issue;

import com.vokyo.backend.issue.dto.IssueWatchResponse;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class IssueWatchService {
    private final WorkspaceAccessService workspaceAccessService;
    private final IssueRepository issueRepository;
    private final ProjectAccessService projectAccessService;
    private final IssueWatcherRepository issueWatcherRepository;

    public IssueWatchService(WorkspaceAccessService workspaceAccessService, IssueRepository issueRepository, ProjectAccessService projectAccessService, IssueWatcherRepository issueWatcherRepository) {
        this.workspaceAccessService = workspaceAccessService;
        this.issueRepository = issueRepository;
        this.projectAccessService = projectAccessService;
        this.issueWatcherRepository = issueWatcherRepository;
    }

    @Transactional
    public IssueWatchResponse watch(Jwt jwt, UUID issueId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId,context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        issueWatcherRepository.save(new IssueWatcher(issueId, context.user().getId()));
        return new IssueWatchResponse(issueWatcherRepository.countByIssueId(issueId),true);
    }

    @Transactional
    public IssueWatchResponse unwatch(Jwt jwt, UUID issueId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId,context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        issueWatcherRepository.deleteById(new IssueWatcherId(issueId, context.user().getId()));
        return new IssueWatchResponse(issueWatcherRepository.countByIssueId(issueId),false);
    }

    private Issue requireIssue(UUID issueId, UUID workspaceId) {
        return issueRepository.findByIdAndWorkspace_Id(issueId,workspaceId)
            .orElseThrow(()->notFound("Issue not found"));
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

}
