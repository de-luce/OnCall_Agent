package com.deluce.oncall.embedding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LM Studio 原生 Embeddings API DTO。
 *
 * @see <a href="https://lmstudio.ai/docs/developer/rest/endpoints">POST /api/v0/embeddings</a>
 */
public final class LmStudioEmbeddingApi {

    private LmStudioEmbeddingApi() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateRequest(
            String model,
            Object input
    ) {
        public static CreateRequest of(String model, String text) {
            return new CreateRequest(model, text);
        }

        public static CreateRequest batch(String model, List<String> texts) {
            return new CreateRequest(model, texts);
        }
    }

    public record CreateResponse(
            String object,
            List<EmbeddingData> data,
            String model,
            Usage usage
    ) {
    }

    public record EmbeddingData(
            String object,
            List<Double> embedding,
            int index
    ) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
