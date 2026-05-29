package com.deluce.oncall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oncall.rag")
public record RagProperties(int chunkSize, int chunkOverlap, int topK) {
}
