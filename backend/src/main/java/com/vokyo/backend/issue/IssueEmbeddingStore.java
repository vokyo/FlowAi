package com.vokyo.backend.issue;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The worker side of the embedding outbox, in plain JDBC because claiming uses
 * FOR UPDATE SKIP LOCKED with RETURNING and the vector column has no JPA mapping.
 * Each method is its own short statement; nothing here holds a transaction open
 * while the embedding API is called.
 */
@Repository
class IssueEmbeddingStore {

    private final NamedParameterJdbcTemplate jdbc;

    IssueEmbeddingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes up to batchSize due jobs and pushes each one's next attempt out by the lease,
     * so another worker passes over them until this one finishes or the lease runs out.
     */
    List<ClaimedJob> claim(int batchSize, Duration lease) {
        return jdbc.query("""
                update issue_embedding_jobs job
                set next_attempt_at = now() + make_interval(secs => :leaseSeconds),
                    attempts = job.attempts + 1
                where job.issue_id in (
                    select due.issue_id
                    from issue_embedding_jobs due
                    where due.next_attempt_at <= now()
                    order by due.next_attempt_at
                    limit :batchSize
                    for update skip locked
                )
                returning job.issue_id, job.revision, job.attempts
                """,
            new MapSqlParameterSource()
                .addValue("leaseSeconds", lease.toSeconds())
                .addValue("batchSize", batchSize),
            (row, index) -> new ClaimedJob(
                row.getObject("issue_id", UUID.class),
                row.getLong("revision"),
                row.getInt("attempts")
            )
        );
    }

    /** The issue's current text, with the hash and model of the embedding stored for it, if any. */
    Optional<IssueText> loadText(UUID issueId) {
        return jdbc.query("""
                select issue.workspace_id, issue.project_id, issue.title, issue.description,
                       embedding.content_hash, embedding.model
                from issues issue
                left join issue_embeddings embedding on embedding.issue_id = issue.id
                where issue.id = :issueId
                """,
            new MapSqlParameterSource("issueId", issueId),
            (row, index) -> new IssueText(
                issueId,
                row.getObject("workspace_id", UUID.class),
                row.getObject("project_id", UUID.class),
                row.getString("title"),
                row.getString("description"),
                row.getString("content_hash"),
                row.getString("model")
            )
        ).stream().findFirst();
    }

    void saveEmbedding(IssueText issue, String model, String contentHash, float[] embedding) {
        jdbc.update("""
                insert into issue_embeddings
                    (issue_id, workspace_id, project_id, model, content_hash, embedding, embedded_at)
                values (:issueId, :workspaceId, :projectId, :model, :contentHash,
                        cast(:embedding as vector), now())
                on conflict (issue_id) do update
                    set model = excluded.model,
                        content_hash = excluded.content_hash,
                        embedding = excluded.embedding,
                        embedded_at = excluded.embedded_at
                """,
            new MapSqlParameterSource()
                .addValue("issueId", issue.issueId())
                .addValue("workspaceId", issue.workspaceId())
                .addValue("projectId", issue.projectId())
                .addValue("model", model)
                .addValue("contentHash", contentHash)
                .addValue("embedding", PgVectors.literal(embedding))
        );
    }

    /** Removes the job only if no newer request arrived since it was claimed. */
    void complete(ClaimedJob job) {
        jdbc.update("""
                delete from issue_embedding_jobs
                where issue_id = :issueId
                  and revision = :revision
                """,
            new MapSqlParameterSource()
                .addValue("issueId", job.issueId())
                .addValue("revision", job.revision())
        );
    }

    /** Schedules the next attempt after a failure, unless a newer request already reset the job. */
    void fail(ClaimedJob job, Duration retryAfter, String error) {
        jdbc.update("""
                update issue_embedding_jobs
                set next_attempt_at = now() + make_interval(secs => :retrySeconds),
                    last_error = :error
                where issue_id = :issueId
                  and revision = :revision
                """,
            new MapSqlParameterSource()
                .addValue("retrySeconds", retryAfter.toSeconds())
                .addValue("error", error)
                .addValue("issueId", job.issueId())
                .addValue("revision", job.revision())
        );
    }

    record ClaimedJob(UUID issueId, long revision, int attempts) {
    }

    record IssueText(
        UUID issueId,
        UUID workspaceId,
        UUID projectId,
        String title,
        String description,
        String storedContentHash,
        String storedModel
    ) {
    }
}
