package com.vokyo.backend;

import com.vokyo.backend.activity.ActivityEventRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner(
            ActivityEventRepository activityEventRepository,
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
        return new IntegrationTestDatabaseCleaner(
                activityEventRepository,
                invitationRepository,
                issueCommentRepository,
                issueRepository,
                projectLabelRepository,
                projectMemberRepository,
                projectWorkflowStateRepository,
                projectRepository,
                refreshTokenRepository,
                membershipRepository,
                workspaceRepository,
                userRepository
        );
    }

}
