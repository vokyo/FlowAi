package com.vokyo.backend.issue;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.activity.dto.ActivityEventResponse;
import com.vokyo.backend.issue.dto.UpdateIssueRequest;
import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.issue.dto.CreateCommentRequest;
import com.vokyo.backend.issue.dto.CreateIssueRequest;
import com.vokyo.backend.issue.dto.IssueCommentResponse;
import com.vokyo.backend.issue.dto.IssueDetailResponse;
import com.vokyo.backend.issue.dto.IssueSummaryResponse;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class IssueService {

    private final IssueRepository issueRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActivityService activityService;

    public IssueService(
            IssueRepository issueRepository,
            IssueCommentRepository issueCommentRepository,
            ProjectRepository projectRepository,
            WorkspaceAccessService workspaceAccessService,
            ActivityService activityService
    ) {
        this.issueRepository = issueRepository;
        this.issueCommentRepository = issueCommentRepository;
        this.projectRepository = projectRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public List<IssueSummaryResponse> listIssues(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        requireProject(projectId, context.workspace().getId());

        return issueRepository.findByWorkspace_IdAndProject_IdOrderByCreatedAtDesc(
                        context.workspace().getId(),
                        projectId
                )
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @Transactional
    public IssueSummaryResponse createIssue(Jwt jwt, CreateIssueRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = requireProject(request.projectId(), context.workspace().getId());
        Issue issue = issueRepository.save(new Issue(
                context.workspace(),
                project,
                context.user(),
                request.title().trim(),
                normalizeOptionalText(request.description()),
                request.status(),
                request.priority()
        ));

        activityService.recordIssueCreated(issue, context.user());
        return toSummaryResponse(issue);
    }

    @Transactional(readOnly = true)
    public IssueDetailResponse getIssue(Jwt jwt, UUID issueId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        List<IssueCommentResponse> comments = issueCommentRepository.findByIssue_IdOrderByCreatedAtAsc(issue.getId())
                .stream()
                .map(this::toCommentResponse)
                .toList();

        return toDetailResponse(issue, comments);
    }

    @Transactional
    public IssueCommentResponse createComment(Jwt jwt, UUID issueId, CreateCommentRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        IssueComment comment = issueCommentRepository.save(new IssueComment(
                context.workspace(),
                issue.getProject(),
                issue,
                context.user(),
                request.body().trim()
        ));

        activityService.recordCommentCreated(comment, context.user());
        return toCommentResponse(comment);
    }

    @Transactional(readOnly = true)
    public List<ActivityEventResponse> listIssueActivities(Jwt jwt, UUID issueId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        return activityService.listIssueActivities(issue.getId(), context.workspace().getId());
    }

    private Project requireProject(UUID projectId, UUID workspaceId) {
        return projectRepository.findByIdAndWorkspace_Id(projectId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    private Issue requireIssue(UUID issueId, UUID workspaceId) {
        return issueRepository.findByIdAndWorkspace_Id(issueId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found"));
    }

    private IssueSummaryResponse toSummaryResponse(Issue issue) {
        return new IssueSummaryResponse(
                issue.getId(),
                issue.getProject().getId(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getStatus().name(),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                issueCommentRepository.countByIssue_Id(issue.getId())
        );
    }

    private IssueDetailResponse toDetailResponse(Issue issue, List<IssueCommentResponse> comments) {
        return new IssueDetailResponse(
                issue.getId(),
                issue.getProject().getId(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getStatus().name(),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                comments
        );
    }

    private IssueCommentResponse toCommentResponse(IssueComment comment) {
        User author = comment.getAuthorUser();
        return new IssueCommentResponse(
                comment.getId(),
                comment.getIssue().getId(),
                new UserResponse(author.getId(), author.getEmail(), author.getDisplayName()),
                comment.getBody(),
                comment.getCreatedAt()
        );
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    @Transactional
    public IssueDetailResponse updateIssue(Jwt jwt, UUID issueId, @Valid UpdateIssueRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        IssueStatus previousStatus = issue.getStatus();
        if (request.title() != null) {
            String title = request.title().trim();
            if(title.isBlank()){
                throw new  ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required");
            }
            issue.rename(title);
        }
        if (request.description() != null) {
            issue.changeDescription(normalizeOptionalText(request.description()));
        }
        if (request.status() != null) {
            issue.changeStatus(request.status());
        }

        if (request.priority() != null) {
            issue.changePriority(request.priority());
        }

        if (request.status() != null && previousStatus != request.status()) {
            activityService.recordIssueStatusChanged(
                    issue,
                    context.user(),
                    previousStatus,
                    request.status()
            );
        }

        List<IssueCommentResponse> comments = issueCommentRepository.findByIssue_IdOrderByCreatedAtAsc(issue.getId())
                .stream()
                .map(this::toCommentResponse)
                .toList();

        return toDetailResponse(issue, comments);
    }
}
