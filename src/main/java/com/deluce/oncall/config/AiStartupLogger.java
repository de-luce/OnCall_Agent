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
    private String chatBaseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Value("${oncall.embedding.provider:lmstudio}")
    private String embeddingProvider;

    @Value("${oncall.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${oncall.embedding.path:/api/v0/embeddings}")
    private String embeddingPath;

    @Value("${oncall.embedding.model}")
    private String embeddingModel;

    @EventListener(ApplicationReadyEvent.class)
    public void logAiConfig() {
        log.info("========== LM Studio 配置 ==========");
        log.info("Chat  endpoint : {}/v1/chat/completions", chatBaseUrl.replaceAll("/+$", ""));
        log.info("Chat  model     : {}", chatModel);
        log.info("Embed provider  : {}", embeddingProvider);
        if ("lmstudio".equalsIgnoreCase(embeddingProvider)) {
            log.info("Embed endpoint  : {}{}", embeddingBaseUrl.replaceAll("/+$", ""), embeddingPath);
            log.info("Embed model     : {}", embeddingModel);
        }
        log.info("====================================");
    }
}
