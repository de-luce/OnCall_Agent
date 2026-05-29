package com.deluce.oncall.embedding;

import com.deluce.oncall.config.EmbeddingProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LM Studio 原生 /api/v0/embeddings 的 EmbeddingModel 实现。
 */
public class LmStudioEmbeddingModel implements EmbeddingModel {

    private final LmStudioEmbeddingClient client;
    private final EmbeddingProperties properties;
    private volatile Integer cachedDimensions;

    public LmStudioEmbeddingModel(LmStudioEmbeddingClient client, EmbeddingProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = client.embedBatch(request.getInstructions());
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return client.embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        return client.embed(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return client.embedBatch(texts);
    }

    @Override
    public int dimensions() {
        if (properties.dimensions() != null && properties.dimensions() > 0) {
            return properties.dimensions();
        }
        if (cachedDimensions != null) {
            return cachedDimensions;
        }
        synchronized (this) {
            if (cachedDimensions == null) {
                cachedDimensions = client.embed("dimension probe").length;
            }
            return cachedDimensions;
        }
    }
}
