package com.deluce.oncall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oncall.milvus")
public record MilvusProperties(
        String uri,
        String user,
        String password,
        String collection,
        int embeddingDimension
) {
}
