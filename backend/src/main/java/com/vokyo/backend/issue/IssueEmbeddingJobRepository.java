package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Writes embedding requests into the issue_embedding_jobs outbox. Callers run inside the
 * transaction that changed the issue, so the request commits or rolls back with it. As a
 * JPA query it flushes the pending issue insert first, which the foreign key needs.
 */
interface IssueEmbeddingJobRepository extends Repository<Issue, UUID> {

    @Modifying
    @Query(value = """
            insert into issue_embedding_jobs (issue_id)
            values (:issueId)
            on conflict (issue_id) do update
                set revision = issue_embedding_jobs.revision + 1,
                    requested_at = now(),
                    next_attempt_at = now(),
                    attempts = 0,
                    last_error = null
            """, nativeQuery = true)
    void request(@Param("issueId") UUID issueId);
}
