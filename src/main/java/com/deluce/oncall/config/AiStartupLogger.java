package com.deluce.oncall.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class AiStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(AiStartupLogger.class);

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Value("${spring.ai.openai.embedding.options.model}")
    private String embeddingModel;

    @Value("${spring.datasource.url}")
    private String mysqlUrl;

    @Value("${oncall.milvus.uri}")
    private String milvusUri;

    @Value("${oncall.milvus.collection}")
    private String milvusCollection;

    @Value("${oncall.upload.storage-dir}")
    private String uploadDir;

    @EventListener(ApplicationReadyEvent.class)
    public void logAiConfig() {
        String root = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
        log.info("========== OnCall Agent 已启动 ==========");
        log.info("LLM Base URL     : {}", root);
        log.info("Chat model       : {}", chatModel);
        log.info("Embedding model  : {}", embeddingModel);
        log.info("MySQL            : {}", mysqlUrl);
        log.info("Milvus           : {} / {}", milvusUri, milvusCollection);
        log.info("Upload dir       : {}", Path.of(uploadDir).toAbsolutePath().normalize());
        log.info("========================================");
    }
}
