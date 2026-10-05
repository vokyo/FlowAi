package com.vokyo.backend.issue;

import com.vokyo.backend.ai.TextEmbedder;
import com.vokyo.backend.issue.IssueEmbeddingStore.ClaimedJob;
import com.vokyo.backend.issue.IssueEmbeddingStore.IssueText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Turns issue_embedding_jobs rows into issue_embeddings rows. Claiming, saving and
 * completing are separate short statements; the embedding API is called between them
 * with no transaction open. A crash after saving but before completing only means the
 * job runs again and is skipped by the content hash.
 */
@Service
public class IssueEmbeddingWorker {

    static final int BATCH_SIZE = 20;
    static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY = Duration.ofHours(1);
    private static final int MAX_ERROR_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(IssueEmbeddingWorker.class);

    private final IssueEmbeddingStore store;
    private final ObjectProvider<TextEmbedder> textEmbedder;
    private final String model;

    public IssueEmbeddingWorker(
        IssueEmbeddingStore store,
        ObjectProvider<TextEmbedder> textEmbedder,
        @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String model
    ) {
        this.store = store;
        this.textEmbedder = textEmbedder;
        this.model = model;
    }

    /** Processes one batch of due jobs and returns how many it claimed. */
    public int processBatch() {
        TextEmbedder embedder = textEmbedder.getIfAvailable();
        if (embedder == null) {
            return 0;
        }
        List<ClaimedJob> jobs = store.claim(BATCH_SIZE, LEASE);
        int embedded = 0;
        int failed = 0;
        for (ClaimedJob job : jobs) {
            try {
                if (process(job, embedder)) {
                    embedded++;
                }
            } catch (RuntimeException exception) {
                failed++;
                store.fail(job, retryAfter(job.attempts()), describe(exception));
                log.warn(
                    "event=issue_embedding_failed issueId={} attempts={} reason={}",
                    job.issueId(), job.attempts(), exception.getClass().getSimpleName()
                );
            }
        }
        if (!jobs.isEmpty()) {
            log.info(
                "event=issue_embedding_batch claimed={} embedded={} failed={}",
                jobs.size(), embedded, failed
            );
        }
        return jobs.size();
    }

    // Returns whether the embedding API was called.
    private boolean process(ClaimedJob job, TextEmbedder embedder) {
        Optional<IssueText> loaded = store.loadText(job.issueId());
        if (loaded.isEmpty()) {
            // The issue is gone; its job went with it through the cascade.
            return false;
        }
        IssueText issue = loaded.get();
        String text = embeddingText(issue);
        String contentHash = sha256(text);
        // Status, assignee or label edits do not request embeddings, but a request can
        // still arrive for text that is already embedded, e.g. a title changed and back.
        boolean unchanged = contentHash.equals(issue.storedContentHash()) && model.equals(issue.storedModel());
        if (!unchanged) {
            store.saveEmbedding(issue, model, contentHash, embedder.embed(text));
        }
        store.complete(job);
        return !unchanged;
    }

    static String embeddingText(IssueText issue) {
        if (issue.description() == null || issue.description().isBlank()) {
            return issue.title();
        }
        return issue.title() + "\n\n" + issue.description();
    }

    // 30 s, 1 min, 2 min, ... capped at one hour, so a provider outage is not hammered.
    static Duration retryAfter(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 7);
        Duration delay = FIRST_RETRY.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    private static String describe(RuntimeException exception) {
        String description = exception.getClass().getSimpleName()
            + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
        return description.length() > MAX_ERROR_LENGTH ? description.substring(0, MAX_ERROR_LENGTH) : description;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
