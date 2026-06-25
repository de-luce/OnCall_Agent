package com.deluce.oncall.service;

import com.deluce.oncall.agent.ChatAgent;
import com.deluce.oncall.agent.KnowledgeAgent;
import com.deluce.oncall.agent.OpsAgent;
import com.deluce.oncall.dto.*;
import com.deluce.oncall.history.ChatHistoryRepository;
import com.deluce.oncall.memory.ConversationMemoryService;
import com.deluce.oncall.rag.KnowledgeCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class OnCallService {

    private final ChatAgent chatAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final OpsAgent opsAgent;
    private final KnowledgeCatalog knowledgeCatalog;
    private final ChatHistoryRepository historyRepository;
    private final ConversationMemoryService memoryService;
    private final Path uploadDir;

    public OnCallService(
            ChatAgent chatAgent,
            KnowledgeAgent knowledgeAgent,
            OpsAgent opsAgent,
            KnowledgeCatalog knowledgeCatalog,
            ChatHistoryRepository historyRepository,
            ConversationMemoryService memoryService,
            @Value("${oncall.upload.storage-dir}") String uploadDir) throws Exception {
        this.chatAgent = chatAgent;
        this.knowledgeAgent = knowledgeAgent;
        this.opsAgent = opsAgent;
        this.knowledgeCatalog = knowledgeCatalog;
        this.historyRepository = historyRepository;
        this.memoryService = memoryService;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
    }

    public ChatResponse chat(ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        String answer = chatAgent.chat(request.message(), sessionId);
        return new ChatResponse(sessionId, answer);
    }

    public Flux<String> chatStream(ChatRequest request, AtomicBoolean cancelled) {
        String sessionId = resolveSessionId(request.sessionId());
        return chatAgent.chatStream(request.message(), sessionId, cancelled);
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
        String answer = knowledgeAgent.answer(request.message());
        return new ChatResponse(sessionId, answer);
    }

    public HistorySessionListResponse listHistorySessions(int limit, int offset) {
        return new HistorySessionListResponse(historyRepository.listSessions(limit, offset));
    }

    public HistoryMessagesResponse getHistoryMessages(String sessionId) {
        String convId = memoryService.conversationId(sessionId);
        ChatHistoryRepository.SessionMeta session = historyRepository.getSession(convId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        ChatHistoryRepository.LoadedSession loaded = historyRepository.loadSession(convId);
        return new HistoryMessagesResponse(
                convId,
                session.title(),
                session.summary(),
                loaded.messages()
        );
    }

    public boolean deleteHistorySession(String sessionId) {
        String convId = memoryService.conversationId(sessionId);
        boolean deleted = historyRepository.deleteSession(convId);
        if (deleted) {
            memoryService.clearSessionCache(convId);
        }
        return deleted;
    }

    private String resolveSessionId(String sessionId) {
        return sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
    }
}
