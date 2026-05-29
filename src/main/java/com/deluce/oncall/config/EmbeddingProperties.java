package com.deluce.oncall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oncall.embedding")
public record EmbeddingProperties(
        /** lmstudio = LM Studio 原生 /api/v0/embeddings；local = 本地 hash 向量 */
        String provider,
        /** LM Studio 服务地址，如 http://127.0.0.1:1234 */
        String baseUrl,
        /** LM Studio API Token，对应 LM_API_TOKEN */
        String apiKey,
        /** embedding 模型，如 text-embedding-nomic-embed-text-v1.5 */
        String model,
        /** API 路径，默认 /api/v0/embeddings */
        String path,
        /** 可选，预声明向量维度，避免启动时探测 */
        Integer dimensions
) {
}
