package com.vokyo.backend.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.activity.dto.ActivityEventResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.criteria.Predicate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IssueService {

    private static final int MAX_TITLE_LENGTH = 240;
    private static final int MAX_DESCRIPTION_LENGTH = 10000;

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
    public List<IssueSummaryResponse> listIssues(
            Jwt jwt,
            UUID projectId,
            IssueStatus status,
            IssuePriority priority,
            String query
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        requireProject(projectId, context.workspace().getId());
        String normalizedQuery = normalizeOptionalText(query);

        List<Issue> issues = issueRepository.findAll(
                issueListSpecification(
                        context.workspace().getId(),
                        projectId,
                        status,
                        priority,
                        normalizedQuery
                ),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Map<UUID, Long> commentCounts = loadCommentCounts(issues);

        return issues
                .stream()
                .map(issue -> toSummaryResponse(issue, commentCounts.getOrDefault(issue.getId(), 0L)))
                .toList();
    }

    @Transactional
    public IssueSummaryResponse createIssue(Jwt jwt, CreateIssueRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = requireProject(request.projectId(), context.workspace().getId());
        if (request.status() == IssueStatus.ARCHIVED) {
            throw badRequest("Issue cannot be created as archived");
        }

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
        return toSummaryResponse(issue, 0L);
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

    private Specification<Issue> issueListSpecification(
            UUID workspaceId,
            UUID projectId,
            IssueStatus status,
            IssuePriority priority,
            String query
    ) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("workspace").get("id"), workspaceId));
            predicates.add(criteriaBuilder.equal(root.get("project").get("id"), projectId));

            if (status == null) {
                predicates.add(criteriaBuilder.notEqual(root.get("status"), IssueStatus.ARCHIVED));
            } else {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }

            if (priority != null) {
                predicates.add(criteriaBuilder.equal(root.get("priority"), priority));
            }

            if (query != null) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("description")), pattern)
                ));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Map<UUID, Long> loadCommentCounts(List<Issue> issues) {
        if (issues.isEmpty()) {
            return Map.of();
        }

        List<UUID> issueIds = issues.stream()
                .map(Issue::getId)
                .toList();

        return issueCommentRepository.countByIssueIds(issueIds)
                .stream()
                .collect(Collectors.toMap(
                        IssueCommentRepository.IssueCommentCount::getIssueId,
                        IssueCommentRepository.IssueCommentCount::getCommentCount,
                        Long::sum
                ));
    }

    private IssueSummaryResponse toSummaryResponse(Issue issue, long commentCount) {
        return new IssueSummaryResponse(
                issue.getId(),
                issue.getProject().getId(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getStatus().name(),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                toUserResponse(issue.getCreatedByUser()),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                commentCount
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
                toUserResponse(issue.getCreatedByUser()),
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

    private UserResponse toUserResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    @Transactional
    public IssueDetailResponse updateIssue(Jwt jwt, UUID issueId, JsonNode request) {
        if (request == null || !request.isObject()) {
            throw badRequest("Patch body must be a JSON object");
        }

        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        IssueStatus previousStatus = issue.getStatus();

        if (request.has("title")) {
            String title = requiredText(request, "title", "Title is required").trim();
            if (title.isBlank()) {
                throw badRequest("Title is required");
            }
            if (title.length() > MAX_TITLE_LENGTH) {
                throw badRequest("Title must be at most 240 characters");
            }
            issue.rename(title);
        }

        if (request.has("description")) {
            JsonNode description = request.get("description");
            if (description.isNull()) {
                issue.changeDescription(null);
            } else {
                String descriptionValue = requiredText(request, "description", "Description must be text");
                if (descriptionValue.length() > MAX_DESCRIPTION_LENGTH) {
                    throw badRequest("Description must be at most 10000 characters");
                }
                issue.changeDescription(normalizeOptionalText(descriptionValue));
            }
        }

        IssueStatus requestedStatus = null;
        if (request.has("status")) {
            requestedStatus = requiredEnum(request, "status", IssueStatus.class, "Status is required");
            issue.changeStatus(requestedStatus);
        }

        if (request.has("priority")) {
            JsonNode priority = request.get("priority");
            if (priority.isNull()) {
                issue.changePriority(null);
            } else {
                issue.changePriority(requiredEnum(request, "priority", IssuePriority.class, "Priority is invalid"));
            }
        }

        if (requestedStatus != null && previousStatus != requestedStatus) {
            activityService.recordIssueStatusChanged(
                    issue,
                    context.user(),
                    previousStatus,
                    requestedStatus
            );
        }

        List<IssueCommentResponse> comments = issueCommentRepository.findByIssue_IdOrderByCreatedAtAsc(issue.getId())
                .stream()
                .map(this::toCommentResponse)
                .toList();

        return toDetailResponse(issue, comments);
    }

    private String requiredText(JsonNode request, String fieldName, String message) {
        JsonNode value = request.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw badRequest(message);
        }

        return value.asText();
    }

    private <E extends Enum<E>> E requiredEnum(
            JsonNode request,
            String fieldName,
            Class<E> enumType,
            String message
    ) {
        JsonNode value = request.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw badRequest(message);
        }

        try {
            return Enum.valueOf(enumType, value.asText());
        } catch (IllegalArgumentException exception) {
            throw badRequest(message);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
