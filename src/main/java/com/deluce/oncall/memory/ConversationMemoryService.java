package com.deluce.oncall.memory;

import com.deluce.oncall.config.MemoryProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 多轮对话记忆服务：v1 窗口记忆 + v2 超长上下文摘要压缩。
 */
@Service
public class ConversationMemoryService {

    private final ChatMemory chatMemory;
    private final ChatClient chatClient;
    private final MemoryProperties memoryProperties;
    private final ConcurrentMap<String, String> sessionSummaries = new ConcurrentHashMap<>();

    public ConversationMemoryService(
            ChatMemory chatMemory,
            @Qualifier("chatClient") ChatClient chatClient,
            MemoryProperties memoryProperties) {
        this.chatMemory = chatMemory;
        this.chatClient = chatClient;
        this.memoryProperties = memoryProperties;
    }

    public String conversationId(String sessionId) {
        return sessionId != null && !sessionId.isBlank() ? sessionId : "default";
    }

    public String getSummaryContext(String sessionId) {
        return sessionSummaries.getOrDefault(conversationId(sessionId), "");
    }

    public void maybeSummarize(String sessionId) {
        String convId = conversationId(sessionId);
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
    }
}
