package com.vokyo.backend.issue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the embedding worker in the background, only when an embedding model is
 * configured. Without one, requests wait in the outbox and are picked up once it is.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "spring.ai.model", name = "embedding", havingValue = "openai")
class IssueEmbeddingConfiguration {

    private final IssueEmbeddingWorker worker;

    IssueEmbeddingConfiguration(IssueEmbeddingWorker worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    void embedPendingIssues() {
        worker.processBatch();
    }
}
