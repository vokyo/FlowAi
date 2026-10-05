package com.vokyo.backend.ai.springai;

import com.vokyo.backend.ai.TextEmbedder;
import org.springframework.ai.embedding.EmbeddingModel;

public class SpringAiTextEmbedder implements TextEmbedder {

    private final EmbeddingModel embeddingModel;

    public SpringAiTextEmbedder(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }
}
