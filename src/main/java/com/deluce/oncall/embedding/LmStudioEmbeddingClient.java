package com.deluce.oncall.embedding;

import com.deluce.oncall.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LM Studio 原生 Embeddings 客户端：POST /api/v0/embeddings
 *
 * @see <a href="https://lmstudio.ai/docs/developer/rest/endpoints">LM Studio REST API v0</a>
 */
public class LmStudioEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LmStudioEmbeddingClient.class);
    private static final String DEFAULT_PATH = "/api/v0/embeddings";

    private final RestClient restClient;
    private final String embeddingsPath;
    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;

    public LmStudioEmbeddingClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        String baseUrl = normalizeBaseUrl(properties.baseUrl());
        this.embeddingsPath = properties.path() != null && !properties.path().isBlank()
                ? properties.path() : DEFAULT_PATH;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("[LM Studio Embedding] 端点={}{}", baseUrl, embeddingsPath);
    }

    public float[] embed(String text) {
        return embedBatch(List.of(text)).getFirst();
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        LmStudioEmbeddingApi.CreateRequest request = texts.size() == 1
                ? LmStudioEmbeddingApi.CreateRequest.of(properties.model(), texts.getFirst())
                : LmStudioEmbeddingApi.CreateRequest.batch(properties.model(), texts);

        log.info("[Embedding 请求] POST {} model={}, count={}", embeddingsPath, properties.model(), texts.size());
        log.debug("[Embedding 请求体] {}", safeJson(request));

        try {
            LmStudioEmbeddingApi.CreateResponse response = restClient.post()
                    .uri(embeddingsPath)
                    .body(request)
                    .retrieve()
                    .body(LmStudioEmbeddingApi.CreateResponse.class);

            return parseResponse(response);
        } catch (RestClientResponseException e) {
            log.error("[Embedding 失败] status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new EmbeddingException(parseErrorMessage(e.getResponseBodyAsString()), e);
        }
    }

    private List<float[]> parseResponse(LmStudioEmbeddingApi.CreateResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new EmbeddingException("LM Studio Embedding API 返回空 data");
        }

        List<LmStudioEmbeddingApi.EmbeddingData> sorted = new ArrayList<>(response.data());
        sorted.sort(Comparator.comparingInt(LmStudioEmbeddingApi.EmbeddingData::index));

        List<float[]> vectors = new ArrayList<>(sorted.size());
        for (LmStudioEmbeddingApi.EmbeddingData item : sorted) {
            vectors.add(toFloatArray(item.embedding()));
        }

        if (response.usage() != null) {
            log.info("[Embedding 响应] model={}, vectors={}, promptTokens={}, totalTokens={}",
                    response.model(), vectors.size(),
                    response.usage().promptTokens(), response.usage().totalTokens());
        } else {
            log.info("[Embedding 响应] model={}, vectors={}, dim={}",
                    response.model(), vectors.size(), vectors.getFirst().length);
        }
        return vectors;
    }

    private float[] toFloatArray(List<Double> embedding) {
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i).floatValue();
        }
        return vector;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl.replaceAll("/+$", "");
        if (url.endsWith("/v1")) {
            return url.substring(0, url.length() - 3);
        }
        return url;
    }

    private String safeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private String parseErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "LM Studio Embedding API 调用失败";
        }
        if (body.contains("No models loaded")) {
            return "LM Studio 未加载 embedding 模型，请加载 "
                    + properties.model() + " 并开启 Local Server";
        }
        return "LM Studio Embedding API 调用失败: " + body;
    }
}
