package com.deluce.oncall.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * OpenAI 兼容流式客户端，用于读取 LM Studio 的 reasoning_content / content。
 */
@Component
public class OpenAiStreamClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiStreamClient.class);

    public record StreamDelta(String reasoning, String content) {
    }

    private final String apiKey;
    private final String completionsUrl;
    private final String model;
    private final double temperature;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiStreamClient(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.3}") double temperature,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.completionsUrl = resolveCompletionsUrl(baseUrl);
        this.model = model;
        this.temperature = temperature;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public Flux<StreamDelta> streamChat(String systemPrompt, String userMessage, AtomicBoolean cancelled) {
        return Flux.<StreamDelta>create(sink -> {
            String body;
            try {
                body = buildRequestBody(systemPrompt, userMessage);
            } catch (Exception e) {
                sink.error(e);
                return;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(completionsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofMinutes(5))
                    .build();

            CompletableFuture<HttpResponse<Stream<String>>> responseFuture = httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofLines()
            );

            sink.onDispose(() -> {
                cancelled.set(true);
                responseFuture.cancel(true);
            });

            responseFuture.whenComplete((response, error) -> {
                if (error != null) {
                    if (cancelled.get()) {
                        sink.complete();
                        return;
                    }
                    sink.error(error);
                    return;
                }

                try (Stream<String> lines = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        sink.error(new IllegalStateException("LLM stream failed: HTTP " + response.statusCode()));
                        return;
                    }

                    lines.forEach(line -> {
                        if (cancelled.get() || sink.isCancelled()) {
                            return;
                        }
                        if (!line.startsWith("data:")) {
                            return;
                        }
                        String data = line.substring(5).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) {
                            return;
                        }
                        parseDelta(data).ifPresent(sink::next);
                    });
                    sink.complete();
                } catch (Exception e) {
                    if (cancelled.get()) {
                        sink.complete();
                    } else {
                        sink.error(e);
                    }
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildRequestBody(String systemPrompt, String userMessage) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.put("temperature", temperature);

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);

        return objectMapper.writeValueAsString(root);
    }

    private Optional<StreamDelta> parseDelta(String json) {
        try {
            JsonNode delta = objectMapper.readTree(json).path("choices").path(0).path("delta");
            String reasoning = textOrEmpty(delta.get("reasoning_content"));
            String content = textOrEmpty(delta.get("content"));
            if (reasoning.isEmpty() && content.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new StreamDelta(reasoning, content));
        } catch (Exception e) {
            log.debug("忽略无法解析的流式片段: {}", json, e);
            return Optional.empty();
        }
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    static String resolveCompletionsUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }
}
