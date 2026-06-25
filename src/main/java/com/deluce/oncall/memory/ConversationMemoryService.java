package com.deluce.oncall.memory;

import com.deluce.oncall.config.MemoryProperties;
import com.deluce.oncall.dto.HistoryMessageItem;
import com.deluce.oncall.history.ChatHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多轮对话记忆：窗口记忆 + MySQL 持久化 + 超长对话摘要。
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ChatMemory chatMemory;
    private final ChatClient chatClient;
    private final MemoryProperties memoryProperties;
    private final ChatHistoryRepository historyRepository;
    private final ConcurrentMap<String, String> sessionSummaries = new ConcurrentHashMap<>();
    private final Set<String> loadedSessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService summarizeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ConversationMemoryService(
            ChatMemory chatMemory,
            @Qualifier("chatClient") ChatClient chatClient,
            MemoryProperties memoryProperties,
            ChatHistoryRepository historyRepository) {
        this.chatMemory = chatMemory;
        this.chatClient = chatClient;
        this.memoryProperties = memoryProperties;
        this.historyRepository = historyRepository;
    }

    public String conversationId(String sessionId) {
        return sessionId != null && !sessionId.isBlank() ? sessionId : "default";
    }

    public void ensureLoaded(String sessionId) {
        String convId = conversationId(sessionId);
        if (loadedSessions.contains(convId)) {
            return;
        }
        ChatHistoryRepository.LoadedSession loaded = historyRepository.loadSession(convId);
        for (HistoryMessageItem item : loaded.messages()) {
            Message message = "user".equals(item.role())
                    ? new UserMessage(item.content())
                    : new AssistantMessage(item.content());
            chatMemory.add(convId, message);
        }
        if (loaded.summary() != null && !loaded.summary().isBlank()) {
            sessionSummaries.put(convId, loaded.summary());
        }
        loadedSessions.add(convId);
    }

    public String getSummaryContext(String sessionId) {
        ensureLoaded(sessionId);
        return sessionSummaries.getOrDefault(conversationId(sessionId), "");
    }

    public void persistExchange(String sessionId, String userMessage, String assistantMessage) {
        String convId = conversationId(sessionId);
        ensureLoaded(sessionId);
        historyRepository.appendExchange(convId, userMessage, assistantMessage);
    }

    /** 流式对话结束后写入内存与数据库（同步对话由 ChatMemory Advisor 维护内存）。 */
    public void appendExchange(String sessionId, String userMessage, String assistantMessage) {
        String convId = conversationId(sessionId);
        ensureLoaded(sessionId);
        chatMemory.add(convId, new UserMessage(userMessage));
        chatMemory.add(convId, new AssistantMessage(assistantMessage));
        historyRepository.appendExchange(convId, userMessage, assistantMessage);
    }

    public void maybeSummarize(String sessionId) {
        String convId = conversationId(sessionId);
        ensureLoaded(sessionId);
        var messages = chatMemory.get(convId);
        if (messages.size() < memoryProperties.summaryThreshold()) {
            return;
        }
        String summary = chatClient.prompt()
                .system("请将以下对话历史压缩为简洁摘要，保留关键故障信息和已确认结论。")
                .user(messages.toString())
                .call()
                .content();
        sessionSummaries.put(convId, summary);
        historyRepository.updateSummary(convId, summary);
    }

    /** 在响应返回用户后再异步压缩上下文，避免阻塞对话。 */
    public void scheduleSummarize(String sessionId) {
        summarizeExecutor.execute(() -> {
            try {
                maybeSummarize(sessionId);
            } catch (Exception e) {
                log.warn("后台摘要压缩失败, sessionId={}", sessionId, e);
            }
        });
    }

    public void clearSessionCache(String sessionId) {
        String convId = conversationId(sessionId);
        chatMemory.clear(convId);
        sessionSummaries.remove(convId);
        loadedSessions.remove(convId);
    }
}
