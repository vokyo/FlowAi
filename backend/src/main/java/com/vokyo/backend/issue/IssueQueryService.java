package com.vokyo.backend.issue;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.activity.dto.ActivityEventResponse;
import com.vokyo.backend.issue.dto.IssueCommentResponse;
import com.vokyo.backend.issue.dto.IssueDetailResponse;
import com.vokyo.backend.issue.dto.IssueSummaryResponse;
import com.vokyo.backend.pagination.CursorCodec;
import com.vokyo.backend.pagination.CursorPage;
import com.vokyo.backend.pagination.CursorPagination;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IssueQueryService {

    private final IssueRepository issueRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActivityService activityService;
    private final CursorCodec cursorCodec;
    private final IssueCommentCountQuery commentCountQuery;
    private final IssueMapper issueMapper;

    public IssueQueryService(
            IssueRepository issueRepository,
            IssueCommentRepository issueCommentRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            ActivityService activityService,
            CursorCodec cursorCodec,
            IssueCommentCountQuery commentCountQuery,
            IssueMapper issueMapper
    ) {
        this.issueRepository = issueRepository;
        this.issueCommentRepository = issueCommentRepository;
        this.projectAccessService = projectAccessService;
        this.workspaceAccessService = workspaceAccessService;
        this.activityService = activityService;
        this.cursorCodec = cursorCodec;
        this.commentCountQuery = commentCountQuery;
        this.issueMapper = issueMapper;
    }

    @Transactional(readOnly = true)
    public CursorPage<IssueSummaryResponse> listIssues(
            Jwt jwt,
            UUID projectId,
            IssueStatus status,
            UUID workflowStateId,
            IssuePriority priority,
            UUID assigneeUserId,
            UUID labelId,
            String query,
            String cursor,
            int requestedLimit
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        projectAccessService.requireAccessibleProject(projectId, context);
        String normalizedQuery = normalizeOptionalText(query);
        int limit = CursorPagination.validateLimit(requestedLimit);
        String cursorScope = issueListCursorScope(
                context.workspace().getId(),
                projectId,
                status,
                workflowStateId,
                priority,
                assigneeUserId,
                labelId,
                normalizedQuery
        );
        CursorCodec.TimeCursor decodedCursor = cursor == null
                ? null
                : cursorCodec.decodeTime(cursor, cursorScope);

        Specification<Issue> specification = issueListSpecification(
                context.workspace().getId(),
                projectId,
                status,
                workflowStateId,
                priority,
                assigneeUserId,
                labelId,
                normalizedQuery,
                decodedCursor
        );
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        List<Issue> issues = issueRepository.findBy(
                specification,
                fluentQuery -> fluentQuery.sortBy(sort).limit(limit + 1).all()
        );
        Map<UUID, Long> commentCounts = commentCountQuery.load(issues);

        return CursorPagination.page(
                issues,
                limit,
                issue -> issueMapper.toSummaryResponse(
                        issue,
                        commentCounts.getOrDefault(issue.getId(), 0L)
                ),
                issue -> cursorCodec.encodeTime(cursorScope, issue.getCreatedAt(), issue.getId())
        );
    }

    @Transactional(readOnly = true)
    public IssueDetailResponse getIssue(Jwt jwt, UUID issueId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        return issueMapper.toDetailResponse(issue);
    }

    @Transactional(readOnly = true)
    public CursorPage<IssueCommentResponse> listIssueComments(
            Jwt jwt,
            UUID issueId,
            String cursor,
            int requestedLimit
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        int limit = CursorPagination.validateLimit(requestedLimit);
        UUID workspaceId = context.workspace().getId();
        UUID projectId = issue.getProject().getId();
        String scope = "issue-comments:" + workspaceId + ":" + projectId + ":" + issueId;
        PageRequest pageRequest = PageRequest.of(0, limit + 1);
        List<IssueComment> comments;
        if (cursor == null) {
            comments = issueCommentRepository.findFirstPage(workspaceId, projectId, issueId, pageRequest);
        } else {
            CursorCodec.TimeCursor decoded = cursorCodec.decodeTime(cursor, scope);
            comments = issueCommentRepository.findPageAfter(
                    workspaceId,
                    projectId,
                    issueId,
                    decoded.createdAt(),
                    decoded.id(),
                    pageRequest
            );
        }

        return CursorPagination.page(
                comments,
                limit,
                issueMapper::toCommentResponse,
                comment -> cursorCodec.encodeTime(scope, comment.getCreatedAt(), comment.getId())
        );
    }

    @Transactional(readOnly = true)
    public CursorPage<ActivityEventResponse> listIssueActivities(
            Jwt jwt,
            UUID issueId,
            String cursor,
            int requestedLimit
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        projectAccessService.requireIssueProjectAccess(issue, context);
        return activityService.listIssueActivities(
                issue.getId(),
                context.workspace().getId(),
                issue.getProject().getId(),
                cursor,
                requestedLimit
        );
    }

    private Issue requireIssue(UUID issueId, UUID workspaceId) {
        return issueRepository.findByIdAndWorkspace_Id(issueId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found"));
    }

    private Specification<Issue> issueListSpecification(
            UUID workspaceId,
            UUID projectId,
            IssueStatus status,
            UUID workflowStateId,
            IssuePriority priority,
            UUID assigneeUserId,
            UUID labelId,
            String query,
            CursorCodec.TimeCursor cursor
    ) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("workspace").get("id"), workspaceId));
            predicates.add(criteriaBuilder.equal(root.get("project").get("id"), projectId));

            if (status == null) {
                predicates.add(criteriaBuilder.isNull(root.get("archivedAt")));
            } else if (status == IssueStatus.ARCHIVED) {
                predicates.add(criteriaBuilder.isNotNull(root.get("archivedAt")));
            } else {
                predicates.add(criteriaBuilder.isNull(root.get("archivedAt")));
                predicates.add(criteriaBuilder.equal(
                        root.get("workflowState").get("category"),
                        categoryFromStatus(status)
                ));
            }

            if (workflowStateId != null) {
                predicates.add(criteriaBuilder.equal(root.get("workflowState").get("id"), workflowStateId));
            }
            if (priority != null) {
                predicates.add(criteriaBuilder.equal(root.get("priority"), priority));
            }
            if (assigneeUserId != null) {
                predicates.add(criteriaBuilder.equal(root.get("assigneeUser").get("id"), assigneeUserId));
            }
            if (labelId != null) {
                predicates.add(criteriaBuilder.equal(root.join("labels").get("id"), labelId));
            }
            if (query != null) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("description")), pattern)
                ));
            }
            if (cursor != null) {
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.lessThan(root.<Instant>get("createdAt"), cursor.createdAt()),
                        criteriaBuilder.and(
                                criteriaBuilder.equal(root.get("createdAt"), cursor.createdAt()),
                                criteriaBuilder.lessThan(root.<UUID>get("id"), cursor.id())
                        )
                ));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private String issueListCursorScope(
            UUID workspaceId,
            UUID projectId,
            IssueStatus status,
            UUID workflowStateId,
            IssuePriority priority,
            UUID assigneeUserId,
            UUID labelId,
            String query
    ) {
        String filters = String.join(
                "",
                lengthPrefixed(status),
                lengthPrefixed(workflowStateId),
                lengthPrefixed(priority),
                lengthPrefixed(assigneeUserId),
                lengthPrefixed(labelId),
                lengthPrefixed(query)
        );
        return "issues:" + workspaceId + ":" + projectId + ":" + sha256(filters);
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String lengthPrefixed(Object value) {
        if (value == null) {
            return "-1:";
        }
        String text = value.toString();
        return text.length() + ":" + text;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private WorkflowStateCategory categoryFromStatus(IssueStatus status) {
        return switch (status) {
            case TODO -> WorkflowStateCategory.TODO;
            case IN_PROGRESS -> WorkflowStateCategory.IN_PROGRESS;
            case DONE -> WorkflowStateCategory.DONE;
            case ARCHIVED -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Archived is not a workflow state"
            );
        };
    }
}
