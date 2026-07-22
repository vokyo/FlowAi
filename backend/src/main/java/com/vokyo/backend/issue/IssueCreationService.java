package com.vokyo.backend.issue;

import com.vokyo.backend.activity.ActivityService;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IssueCreationService {

    private static final int MAX_TITLE_LENGTH = 240;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final long BOARD_POSITION_STEP = 10_000L;

    private final IssueRepository issueRepository;
    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectWorkflowStateRepository workflowStateRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;

    public IssueCreationService(
            IssueRepository issueRepository,
            ProjectLabelRepository projectLabelRepository,
            ProjectWorkflowStateRepository workflowStateRepository,
            ProjectAccessService projectAccessService,
            ActivityService activityService
    ) {
        this.issueRepository = issueRepository;
        this.projectLabelRepository = projectLabelRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.projectAccessService = projectAccessService;
        this.activityService = activityService;
    }

    @Transactional
    public Issue create(
            CurrentWorkspaceContext context,
            Project project,
            IssueCreationCommand command
    ) {
        String title = requireTitle(command.title());
        String description = normalizeDescription(command.description());
        if (command.status() == IssueStatus.ARCHIVED) {
            throw badRequest("Issue cannot be created as archived");
        }

        User assignee = resolveAssignee(project, command.assigneeUserId());
        List<ProjectLabel> labels = resolveProjectLabels(project, command.labelIds());
        ProjectWorkflowState workflowState = resolveWorkflowState(
                project,
                command.workflowStateId(),
                command.status()
        );

        Issue issue = issueRepository.save(new Issue(
                context.workspace(),
                project,
                context.user(),
                title,
                description,
                assignee,
                workflowState,
                command.priority(),
                command.dueDate(),
                nextBoardPosition(project, workflowState)
        ));
        issue.replaceLabels(labels);
        activityService.recordIssueCreated(issue, context.user());
        return issue;
    }

    private String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("Title is required");
        }
        String title = value.trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw badRequest("Title must be at most 240 characters");
        }
        return title;
    }

    private String normalizeDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String description = value.trim();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw badRequest("Description must be at most 10000 characters");
        }
        return description;
    }

    private User resolveAssignee(Project project, UUID assigneeUserId) {
        if (assigneeUserId == null) {
            return null;
        }
        return projectAccessService.requireActiveProjectMemberUser(
                project,
                assigneeUserId
        );
    }

    private List<ProjectLabel> resolveProjectLabels(
            Project project,
            List<UUID> labelIds
    ) {
        if (labelIds == null || labelIds.isEmpty()) {
            return List.of();
        }

        Set<UUID> uniqueLabelIds = Set.copyOf(labelIds);
        List<ProjectLabel> labels =
                projectLabelRepository.findByWorkspace_IdAndProject_IdAndIdIn(
                        project.getWorkspace().getId(),
                        project.getId(),
                        uniqueLabelIds
                );
        if (labels.size() != uniqueLabelIds.size()) {
            throw notFound("Project label not found");
        }

        Map<UUID, ProjectLabel> labelsById = labels.stream()
                .collect(Collectors.toMap(ProjectLabel::getId, label -> label));
        return labelIds.stream().distinct().map(labelsById::get).toList();
    }

    private ProjectWorkflowState resolveWorkflowState(
            Project project,
            UUID workflowStateId,
            IssueStatus status
    ) {
        if (workflowStateId != null) {
            return workflowStateRepository.findByWorkspace_IdAndProject_IdAndId(
                            project.getWorkspace().getId(),
                            project.getId(),
                            workflowStateId
                    )
                    .orElseThrow(() -> notFound("Project workflow state not found"));
        }

        WorkflowStateCategory category = status == null
                ? WorkflowStateCategory.TODO
                : categoryFromStatus(status);
        return workflowStateRepository
                .findFirstByWorkspace_IdAndProject_IdAndCategoryOrderByPositionAscNameAsc(
                        project.getWorkspace().getId(),
                        project.getId(),
                        category
                )
                .orElseThrow(() -> notFound("Project workflow state not found"));
    }

    private WorkflowStateCategory categoryFromStatus(IssueStatus status) {
        return switch (status) {
            case TODO -> WorkflowStateCategory.TODO;
            case IN_PROGRESS -> WorkflowStateCategory.IN_PROGRESS;
            case DONE -> WorkflowStateCategory.DONE;
            case ARCHIVED -> throw badRequest("Archived is not a workflow state");
        };
    }

    private long nextBoardPosition(
            Project project,
            ProjectWorkflowState workflowState
    ) {
        return issueRepository.findMaxActiveBoardPosition(
                project.getWorkspace().getId(),
                project.getId(),
                workflowState.getId()
        ) + BOARD_POSITION_STEP;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
