package com.vokyo.backend.issue;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.issue.dto.BoardColumnResponse;
import com.vokyo.backend.issue.dto.IssueSummaryResponse;
import com.vokyo.backend.issue.dto.MoveIssueStateRequest;
import com.vokyo.backend.issue.dto.ProjectBoardResponse;
import com.vokyo.backend.issue.dto.ReorderIssuesRequest;
import com.vokyo.backend.issue.dto.ReorderIssuesResponse;
import com.vokyo.backend.pagination.CursorCodec;
import com.vokyo.backend.pagination.CursorPage;
import com.vokyo.backend.pagination.CursorPagination;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class BoardService {

    private static final long BOARD_POSITION_STEP = 10_000L;

    private final IssueRepository issueRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ActivityService activityService;
    private final CursorCodec cursorCodec;
    private final IssueCommentCountQuery commentCountQuery;
    private final IssueMapper issueMapper;

    public BoardService(
            IssueRepository issueRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            ProjectAccessService projectAccessService,
            WorkspaceAccessService workspaceAccessService,
            ActivityService activityService,
            CursorCodec cursorCodec,
            IssueCommentCountQuery commentCountQuery,
            IssueMapper issueMapper
    ) {
        this.issueRepository = issueRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.projectAccessService = projectAccessService;
        this.workspaceAccessService = workspaceAccessService;
        this.activityService = activityService;
        this.cursorCodec = cursorCodec;
        this.commentCountQuery = commentCountQuery;
        this.issueMapper = issueMapper;
    }

    @Transactional(readOnly = true)
    public ProjectBoardResponse getBoard(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        List<ProjectWorkflowState> workflowStates = projectWorkflowStateRepository
                .findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc(
                        project.getWorkspace().getId(),
                        project.getId()
                );
        List<BoardColumnResponse> columns = workflowStates
                .stream()
                .map(workflowState -> toBoardColumn(
                        workflowState,
                        loadBoardStatePage(
                                project,
                                workflowState,
                                null,
                                CursorPagination.DEFAULT_LIMIT
                        )
                ))
                .toList();
        return new ProjectBoardResponse(project.getId(), columns);
    }

    @Transactional(readOnly = true)
    public CursorPage<IssueSummaryResponse> getBoardStatePage(
            Jwt jwt,
            UUID projectId,
            UUID workflowStateId,
            String cursor,
            int requestedLimit
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        ProjectWorkflowState workflowState = requireProjectWorkflowState(project, workflowStateId);
        return loadBoardStatePage(project, workflowState, cursor, requestedLimit);
    }

    @Transactional
    public IssueSummaryResponse moveIssueState(Jwt jwt, UUID issueId, MoveIssueStateRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = requireIssueProjectForUpdate(issueId, context);
        Issue issue = requireIssue(issueId, context.workspace().getId());
        if (issue.getArchivedAt() != null) {
            throw conflict("Archived issue cannot be moved");
        }

        ProjectWorkflowState targetWorkflowState = requireProjectWorkflowState(project, request.workflowStateId());
        ProjectWorkflowState previousWorkflowState = issue.getWorkflowState();
        if (previousWorkflowState.getId().equals(targetWorkflowState.getId())) {
            return issueMapper.toSummaryResponse(issue, commentCountQuery.load(issue));
        }

        String previousStatus = displayStatus(issue);
        issue.changeWorkflowState(targetWorkflowState);
        issue.moveOnBoard(nextBoardPosition(project, targetWorkflowState));
        activityService.recordIssueStatusChanged(
                issue,
                context.user(),
                previousStatus,
                displayStatus(issue),
                previousWorkflowState.getId(),
                targetWorkflowState.getId()
        );
        return issueMapper.toSummaryResponse(issue, commentCountQuery.load(issue));
    }

    @Transactional
    public ReorderIssuesResponse reorderIssues(Jwt jwt, ReorderIssuesRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = requireIssueProjectForUpdate(request.issueId(), context);
        Issue movedIssue = requireIssue(request.issueId(), context.workspace().getId());
        if (movedIssue.getArchivedAt() != null) {
            throw conflict("Archived issue cannot be reordered");
        }

        ProjectWorkflowState targetWorkflowState = requireProjectWorkflowState(project, request.workflowStateId());
        validateDistinctBoardNeighbors(request, movedIssue);
        Issue previousIssue = resolveBoardNeighbor(
                project,
                targetWorkflowState,
                movedIssue,
                request.previousIssueId()
        );
        Issue requestedNextIssue = resolveBoardNeighbor(
                project,
                targetWorkflowState,
                movedIssue,
                request.nextIssueId()
        );
        Issue nextIssue = resolveEffectiveNextBoardNeighbor(
                project,
                targetWorkflowState,
                movedIssue,
                previousIssue,
                requestedNextIssue
        );
        ProjectWorkflowState previousWorkflowState = movedIssue.getWorkflowState();
        String previousStatus = displayStatus(movedIssue);
        boolean workflowStateChanged = !previousWorkflowState.getId().equals(targetWorkflowState.getId());
        BoardPositionResult boardPosition = allocateBoardPosition(
                project,
                targetWorkflowState,
                movedIssue,
                previousIssue,
                nextIssue
        );

        if (workflowStateChanged) {
            movedIssue.changeWorkflowState(targetWorkflowState);
        }
        movedIssue.moveOnBoard(boardPosition.value());

        if (workflowStateChanged) {
            activityService.recordIssueStatusChanged(
                    movedIssue,
                    context.user(),
                    previousStatus,
                    displayStatus(movedIssue),
                    previousWorkflowState.getId(),
                    targetWorkflowState.getId()
            );
        }

        return new ReorderIssuesResponse(
                movedIssue.getId(),
                targetWorkflowState.getId(),
                boardPosition.value(),
                boardPosition.rebalanced()
        );
    }

    private CursorPage<IssueSummaryResponse> loadBoardStatePage(
            Project project,
            ProjectWorkflowState workflowState,
            String cursor,
            int requestedLimit
    ) {
        int limit = CursorPagination.validateLimit(requestedLimit);
        String scope = boardCursorScope(project, workflowState);
        PageRequest pageRequest = PageRequest.of(0, limit + 1);
        List<Issue> issues;
        if (cursor == null) {
            issues = issueRepository.findFirstActiveIssuesInWorkflowState(
                    project.getWorkspace().getId(),
                    project.getId(),
                    workflowState.getId(),
                    pageRequest
            );
        } else {
            CursorCodec.BoardCursor decoded = cursorCodec.decodeBoard(cursor, scope);
            issues = issueRepository.findActiveIssuesInWorkflowStateAfter(
                    project.getWorkspace().getId(),
                    project.getId(),
                    workflowState.getId(),
                    decoded.boardPosition(),
                    decoded.id(),
                    pageRequest
            );
        }
        Map<UUID, Long> commentCounts = commentCountQuery.load(issues);
        return CursorPagination.page(
                issues,
                limit,
                issue -> issueMapper.toSummaryResponse(
                        issue,
                        commentCounts.getOrDefault(issue.getId(), 0L)
                ),
                issue -> cursorCodec.encodeBoard(scope, issue.getBoardPosition(), issue.getId())
        );
    }

    private BoardColumnResponse toBoardColumn(
            ProjectWorkflowState workflowState,
            CursorPage<IssueSummaryResponse> page
    ) {
        return new BoardColumnResponse(
                issueMapper.toWorkflowStateResponse(workflowState),
                page.items(),
                page.nextCursor()
        );
    }

    private String boardCursorScope(Project project, ProjectWorkflowState workflowState) {
        return "board-state:" + project.getWorkspace().getId()
                + ":" + project.getId()
                + ":" + workflowState.getId();
    }

    private long nextBoardPosition(Project project, ProjectWorkflowState workflowState) {
        return issueRepository.findMaxActiveBoardPosition(
                project.getWorkspace().getId(),
                project.getId(),
                workflowState.getId()
        ) + BOARD_POSITION_STEP;
    }

    private void validateDistinctBoardNeighbors(ReorderIssuesRequest request, Issue movedIssue) {
        if (Objects.equals(request.previousIssueId(), request.nextIssueId())
                && request.previousIssueId() != null) {
            throw badRequest("Previous and next issues must be different");
        }
        if (movedIssue.getId().equals(request.previousIssueId())
                || movedIssue.getId().equals(request.nextIssueId())) {
            throw badRequest("The moved issue cannot be its own neighbor");
        }
    }

    private Issue resolveBoardNeighbor(
            Project project,
            ProjectWorkflowState targetWorkflowState,
            Issue movedIssue,
            UUID neighborIssueId
    ) {
        if (neighborIssueId == null) {
            return null;
        }

        Issue neighbor = issueRepository.findByIdAndWorkspace_Id(
                        neighborIssueId,
                        project.getWorkspace().getId()
                )
                .orElseThrow(() -> notFound("Issue not found"));
        if (!neighbor.getProject().getId().equals(project.getId())) {
            throw notFound("Issue not found");
        }
        if (neighbor.getArchivedAt() != null) {
            throw badRequest("Archived issues cannot be board neighbors");
        }
        if (!neighbor.getWorkflowState().getId().equals(targetWorkflowState.getId())) {
            throw badRequest("Board neighbors must belong to the target workflow state");
        }
        if (neighbor.getId().equals(movedIssue.getId())) {
            throw badRequest("The moved issue cannot be its own neighbor");
        }
        return neighbor;
    }

    private Issue resolveEffectiveNextBoardNeighbor(
            Project project,
            ProjectWorkflowState targetWorkflowState,
            Issue movedIssue,
            Issue previousIssue,
            Issue requestedNextIssue
    ) {
        PageRequest firstResult = PageRequest.of(0, 1);
        Issue actualNextIssue;
        if (previousIssue == null) {
            actualNextIssue = firstOrNull(issueRepository.findFirstActiveIssueInWorkflowStateExcludingIssue(
                    project.getWorkspace().getId(),
                    project.getId(),
                    targetWorkflowState.getId(),
                    movedIssue.getId(),
                    firstResult
            ));
            if (!Objects.equals(issueId(actualNextIssue), issueId(requestedNextIssue))) {
                throw badRequest("Next issue is not the first issue in the target workflow state");
            }
            return requestedNextIssue;
        }

        actualNextIssue = firstOrNull(issueRepository.findFirstActiveIssueAfterExcludingIssue(
                project.getWorkspace().getId(),
                project.getId(),
                targetWorkflowState.getId(),
                previousIssue.getBoardPosition(),
                previousIssue.getId(),
                movedIssue.getId(),
                firstResult
        ));
        if (requestedNextIssue != null
                && !requestedNextIssue.getId().equals(issueId(actualNextIssue))) {
            throw badRequest("Previous and next issues are not adjacent");
        }
        return requestedNextIssue == null ? actualNextIssue : requestedNextIssue;
    }

    private BoardPositionResult allocateBoardPosition(
            Project project,
            ProjectWorkflowState targetWorkflowState,
            Issue movedIssue,
            Issue previousIssue,
            Issue nextIssue
    ) {
        Long position = sparseBoardPosition(previousIssue, nextIssue);
        if (position != null) {
            return new BoardPositionResult(position, false);
        }

        rebalanceWorkflowState(project, targetWorkflowState, movedIssue);
        position = sparseBoardPosition(previousIssue, nextIssue);
        if (position == null) {
            throw conflict("Unable to allocate board position");
        }
        return new BoardPositionResult(position, true);
    }

    private Long sparseBoardPosition(Issue previousIssue, Issue nextIssue) {
        if (previousIssue == null && nextIssue == null) {
            return BOARD_POSITION_STEP;
        }
        if (previousIssue == null) {
            long upper = nextIssue.getBoardPosition();
            return upper > 1 ? upper / 2 : null;
        }
        if (nextIssue == null) {
            try {
                return Math.addExact(previousIssue.getBoardPosition(), BOARD_POSITION_STEP);
            } catch (ArithmeticException exception) {
                return null;
            }
        }

        long lower = previousIssue.getBoardPosition();
        long upper = nextIssue.getBoardPosition();
        if (lower >= upper || upper - lower <= 1) {
            return null;
        }
        return lower + (upper - lower) / 2;
    }

    private void rebalanceWorkflowState(
            Project project,
            ProjectWorkflowState targetWorkflowState,
            Issue movedIssue
    ) {
        List<Issue> issues = issueRepository.findAllActiveIssuesInWorkflowState(
                project.getWorkspace().getId(),
                project.getId(),
                targetWorkflowState.getId()
        );
        long index = 0;
        for (Issue issue : issues) {
            if (issue.getId().equals(movedIssue.getId())) {
                continue;
            }
            try {
                issue.moveOnBoard(Math.multiplyExact(++index, BOARD_POSITION_STEP));
            } catch (ArithmeticException exception) {
                throw conflict("Workflow state contains too many issues to reorder");
            }
        }
        issueRepository.saveAllAndFlush(issues);
    }

    private Project requireIssueProjectForUpdate(UUID issueId, CurrentWorkspaceContext context) {
        UUID projectId = issueRepository.findProjectIdByIdAndWorkspaceId(
                        issueId,
                        context.workspace().getId()
                )
                .orElseThrow(() -> notFound("Issue not found"));
        return projectAccessService.requireAccessibleProjectForUpdate(projectId, context);
    }

    private Issue requireIssue(UUID issueId, UUID workspaceId) {
        return issueRepository.findByIdAndWorkspace_Id(issueId, workspaceId)
                .orElseThrow(() -> notFound("Issue not found"));
    }

    private ProjectWorkflowState requireProjectWorkflowState(Project project, UUID workflowStateId) {
        return projectWorkflowStateRepository.findByWorkspace_IdAndProject_IdAndId(
                        project.getWorkspace().getId(),
                        project.getId(),
                        workflowStateId
                )
                .orElseThrow(() -> notFound("Project workflow state not found"));
    }

    private Issue firstOrNull(List<Issue> issues) {
        return issues.isEmpty() ? null : issues.getFirst();
    }

    private UUID issueId(Issue issue) {
        return issue == null ? null : issue.getId();
    }

    private String displayStatus(Issue issue) {
        return issue.getArchivedAt() == null ? issue.getWorkflowState().getName() : "Archived";
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record BoardPositionResult(long value, boolean rebalanced) {
    }
}
