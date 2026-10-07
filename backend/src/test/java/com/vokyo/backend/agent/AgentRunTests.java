package com.vokyo.backend.agent;

import com.vokyo.backend.project.Project;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunTests {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    @Test
    void aNewRunIsUnderReviewWithItsFirstVersion() {
        AgentRun run = run();

        assertThat(run.getState()).isEqualTo(AgentRunState.REVIEWING);
        assertThat(run.getLatestVersion()).isEqualTo(1);
        assertThat(run.isNew()).isTrue();
    }

    @Test
    void aRunTakesFourRevisionsAndNoMore() {
        AgentRun run = run();

        for (int expected = 2; expected <= AgentRun.MAX_VERSIONS; expected++) {
            assertThat(run.addVersion(NOW)).isEqualTo(expected);
        }

        assertThat(run.hasRoomForAnotherVersion()).isFalse();
        assertThatThrownBy(() -> run.addVersion(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(run.getLatestVersion()).isEqualTo(5);
    }

    @Test
    void anApprovedRunCanNeitherChangeNorBeCancelled() {
        AgentRun run = run();
        run.approve(NOW);

        assertThat(run.getState()).isEqualTo(AgentRunState.APPROVED);
        assertThatThrownBy(() -> run.addVersion(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.cancel(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.approve(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCancelledRunCanNeitherChangeNorBeApproved() {
        AgentRun run = run();
        run.cancel(NOW);

        assertThat(run.getState()).isEqualTo(AgentRunState.CANCELLED);
        assertThatThrownBy(() -> run.addVersion(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.approve(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approvingTheSameVersionAlwaysUsesTheSameIdempotencyKey() {
        AgentRun run = run();

        assertThat(AgentRunReviewService.idempotencyKey(run, 1))
            .isEqualTo(AgentRunReviewService.idempotencyKey(run, 1))
            .isNotEqualTo(AgentRunReviewService.idempotencyKey(run, 2))
            .isNotEqualTo(AgentRunReviewService.idempotencyKey(run(), 1));
    }

    private static AgentRun run() {
        User owner = new User("owner@example.com", "hash", "Owner");
        Workspace workspace = new Workspace(owner, "Workspace", "workspace");
        Project project = new Project(workspace, owner, "Project", "Description");
        return new AgentRun(
            UUID.randomUUID(), workspace, project, owner, "Clear the login debt", LocalDate.parse("2026-10-07"), NOW
        );
    }
}
