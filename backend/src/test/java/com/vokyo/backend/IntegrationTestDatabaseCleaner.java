package com.vokyo.backend;

import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.auth.RefreshTokenRepository;
import com.vokyo.backend.issue.IssueCommentRepository;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.WorkspaceInvitationRepository;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;

final class IntegrationTestDatabaseCleaner {

    private final ActivityEventRepository activityEventRepository;
    private final AiSuggestionRepository aiSuggestionRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final IssueRepository issueRepository;
    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectWorkflowStateRepository projectWorkflowStateRepository;
    private final ProjectRepository projectRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;

    IntegrationTestDatabaseCleaner(
            ActivityEventRepository activityEventRepository,
            AiSuggestionRepository aiSuggestionRepository,
            WorkspaceInvitationRepository invitationRepository,
            IssueCommentRepository issueCommentRepository,
            IssueRepository issueRepository,
            ProjectLabelRepository projectLabelRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectWorkflowStateRepository projectWorkflowStateRepository,
            ProjectRepository projectRepository,
            RefreshTokenRepository refreshTokenRepository,
            WorkspaceMembershipRepository membershipRepository,
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository
    ) {
        this.activityEventRepository = activityEventRepository;
        this.aiSuggestionRepository = aiSuggestionRepository;
        this.invitationRepository = invitationRepository;
        this.issueCommentRepository = issueCommentRepository;
        this.issueRepository = issueRepository;
        this.projectLabelRepository = projectLabelRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectWorkflowStateRepository = projectWorkflowStateRepository;
        this.projectRepository = projectRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
    }

    void clean() {
        aiSuggestionRepository.deleteAllInBatch();
        activityEventRepository.deleteAllInBatch();
        invitationRepository.deleteAllInBatch();
        issueCommentRepository.deleteAllInBatch();
        issueRepository.deleteAllInBatch();
        projectLabelRepository.deleteAllInBatch();
        projectMemberRepository.deleteAllInBatch();
        projectWorkflowStateRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        membershipRepository.deleteAllInBatch();
        workspaceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }
}
