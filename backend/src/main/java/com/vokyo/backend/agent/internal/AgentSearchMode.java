package com.vokyo.backend.agent.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How the agent's issue search matches its query. The agent service picks one from its
 * configuration, so an evaluation can compare them on the same goals; the model never does.
 */
enum AgentSearchMode {

    /** The whole query as one case-insensitive substring of the title or description. */
    KEYWORD,
    /** PostgreSQL full-text search with English stemming, ranked by relevance. */
    FULLTEXT,
    /** Nearest issue embeddings to the query's embedding. */
    SEMANTIC;

    static final String ACCEPTED = "keyword, fulltext, semantic";

    static Optional<AgentSearchMode> parse(String value) {
        return Arrays.stream(values())
            .filter(mode -> mode.name().toLowerCase(Locale.ROOT).equals(value))
            .findFirst();
    }
}
