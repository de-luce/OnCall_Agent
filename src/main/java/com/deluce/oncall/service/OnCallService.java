package com.deluce.oncall.service;

import com.deluce.oncall.agent.ChatAgent;
import com.deluce.oncall.agent.KnowledgeAgent;
import com.deluce.oncall.agent.OpsAgent;
import com.deluce.oncall.dto.*;
import com.deluce.oncall.rag.KnowledgeCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class OnCallService {

    private static final Logger log = LoggerFactory.getLogger(OnCallService.class);

    private final ChatAgent chatAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final OpsAgent opsAgent;
    private final KnowledgeCatalog knowledgeCatalog;
    private final Path uploadDir;

    public OnCallService(
            ChatAgent chatAgent,
            KnowledgeAgent knowledgeAgent,
            OpsAgent opsAgent,
            KnowledgeCatalog knowledgeCatalog,
            @Value("${oncall.upload.storage-dir}") String uploadDir) throws Exception {
        this.chatAgent = chatAgent;
        this.knowledgeAgent = knowledgeAgent;
        this.opsAgent = opsAgent;
        this.knowledgeCatalog = knowledgeCatalog;
        this.uploadDir = Path.of(uploadDir);
        Files.createDirectories(this.uploadDir);
    }

    public ChatResponse chat(ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        log.info("[调用 LLM] chat mode=sync, sessionId={}", sessionId);
        long start = System.currentTimeMillis();
        String answer = chatAgent.chat(request.message(), sessionId);
        log.info("[LLM 响应] chat mode=sync, sessionId={}, elapsed={}ms, answerLength={}",
                sessionId, System.currentTimeMillis() - start, answer != null ? answer.length() : 0);
        return new ChatResponse(sessionId, answer, "CHAT_REACT");
    }

    public Flux<String> chatStream(ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        log.info("[调用 LLM] chat mode=stream, sessionId={}", sessionId);
        return chatAgent.chatStream(request.message(), sessionId);
    }

    public UploadResponse uploadFile(MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileName = file.getOriginalFilename();
        Path saved = uploadDir.resolve(UUID.randomUUID() + "_" + fileName);
        file.transferTo(saved);
        int chunkCount = knowledgeAgent.ingestDocument(saved);
        return new UploadResponse(fileName, chunkCount, "文档已成功入库并完成向量化");
    }

    public OpsResponse aiOps(OpsRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        OpsAgent.OpsResult result = opsAgent.investigate(request.alertMessage(), request.serviceName());
        return new OpsResponse(
                sessionId,
                result.rootCause(),
                result.recommendation(),
                result.executedSteps(),
                result.report()
        );
    }

    public KnowledgeCatalogResponse knowledgeCatalog() {
        return knowledgeCatalog.snapshot();
    }

    public ChatResponse knowledgeChat(ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        log.info("[调用 LLM] knowledge chat, sessionId={}", sessionId);
        long start = System.currentTimeMillis();
        String answer = knowledgeAgent.answer(request.message());
        log.info("[LLM 响应] knowledge chat, sessionId={}, elapsed={}ms", sessionId, System.currentTimeMillis() - start);
        return new ChatResponse(sessionId, answer, "KNOWLEDGE_RAG");
    }

    private String resolveSessionId(String sessionId) {
        return sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
    }
}
