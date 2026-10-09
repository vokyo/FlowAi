package com.vokyo.backend.agent;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs are only ever looked up for the user who started them, in the workspace they
 * are working in; anyone else gets nothing, as if the run did not exist.
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    @Query("""
        select run
        from AgentRun run
        where run.id = :runId
          and run.workspace.id = :workspaceId
          and run.createdByUser.id = :userId
        """)
    Optional<AgentRun> findOwned(UUID runId, UUID workspaceId, UUID userId);

    /** The caller's runs on one project, newest first; the creator index serves it. */
    @Query("""
        select run
        from AgentRun run
        where run.workspace.id = :workspaceId
          and run.createdByUser.id = :userId
          and run.project.id = :projectId
        order by run.createdAt desc, run.id desc
        """)
    List<AgentRun> findOwnedInProject(UUID workspaceId, UUID userId, UUID projectId, Limit limit);

    /** Locks the run, so revising, approving and cancelling it happen one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select run
        from AgentRun run
        where run.id = :runId
          and run.workspace.id = :workspaceId
          and run.createdByUser.id = :userId
        """)
    Optional<AgentRun> findOwnedForUpdate(UUID runId, UUID workspaceId, UUID userId);
}
