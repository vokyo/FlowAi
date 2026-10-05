package com.vokyo.backend.ai;

/**
 * Turns text into an embedding vector. The bean only exists when an embedding model
 * is configured, so callers that can run without one take it as an ObjectProvider.
 */
public interface TextEmbedder {

    float[] embed(String text);
}
