package com.deluce.oncall.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Embedding 实现，不调用 LM Studio / OpenAI API。
 * 适用于 LM Studio 未提供 embedding 接口或未加载模型的场景。
 */
public class LocalHashEmbeddingModel implements EmbeddingModel {

    public static final int DEFAULT_DIMENSIONS = 384;

    private final int dimensions;

    public LocalHashEmbeddingModel() {
        this(DEFAULT_DIMENSIONS);
    }

    public LocalHashEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        for (String text : request.getInstructions()) {
            embeddings.add(new Embedding(toVector(text), 0));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(String text) {
        return toVector(text);
    }

    @Override
    public float[] embed(Document document) {
        return toVector(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] toVector(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : text.toLowerCase().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            vector[Math.floorMod(token.hashCode(), dimensions)] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        if (sum == 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
