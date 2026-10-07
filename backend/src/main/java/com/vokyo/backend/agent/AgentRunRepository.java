package com.vokyo.backend.agent;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
