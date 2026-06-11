package com.deluce.oncall.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AiStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(AiStartupLogger.class);

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Value("${spring.ai.openai.embedding.options.model}")
    private String embeddingModel;

    @EventListener(ApplicationReadyEvent.class)
    public void logAiConfig() {
        String root = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
        log.info("========== LM Studio 配置 ==========");
        log.info("Base URL        : {}", root);
        log.info("Chat endpoint   : {}/v1/chat/completions", root);
        log.info("Chat model      : {}", chatModel);
        log.info("Embed endpoint  : {}/v1/embeddings", root);
        log.info("Embed model     : {}", embeddingModel);
        log.info("====================================");
    }
}
