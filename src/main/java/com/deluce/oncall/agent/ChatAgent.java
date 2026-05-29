package com.deluce.oncall.agent;

import com.deluce.oncall.memory.ConversationMemoryService;
import com.deluce.oncall.tool.KnowledgeSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 对话 Agent：ReAct 模式，推理 + 工具调用 + 知识库检索。
 */
@Service
public class ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是智能 OnCall 对话助手，负责处理高频重复的运维咨询问题。
            
            工作方式（ReAct）：
            1. Thought：分析用户问题，判断是否需要检索知识库
            2. Action：需要时调用 searchKnowledge 工具获取相关文档
            3. Observation：结合检索结果组织回答
            4. Answer：给出清晰、可执行的答复
            
            回答要求：简洁专业，优先引用知识库内容，必要时给出排查步骤。
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ConversationMemoryService memoryService;
    private final KnowledgeSearchTool knowledgeSearchTool;

    public ChatAgent(
            @Qualifier("chatClient") ChatClient chatClient,
            ChatMemory chatMemory,
            ConversationMemoryService memoryService,
            KnowledgeSearchTool knowledgeSearchTool) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.memoryService = memoryService;
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    public String chat(String message, String sessionId) {
        String convId = memoryService.conversationId(sessionId);
        String summary = memoryService.getSummaryContext(sessionId);
        String enrichedMessage = summary.isBlank()
                ? message
                : "【历史摘要】\n" + summary + "\n\n【当前问题】\n" + message;

        log.info("[发送 LLM] sync, sessionId={}, convId={}, messageLength={}", sessionId, convId, enrichedMessage.length());
        long start = System.currentTimeMillis();
        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(enrichedMessage)
                    .tools(knowledgeSearchTool)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                    .call()
                    .content();
            log.info("[接收 LLM] sync, sessionId={}, elapsed={}ms, answerLength={}",
                    sessionId, System.currentTimeMillis() - start, answer != null ? answer.length() : 0);
            log.debug("[接收 LLM] sync, sessionId={}, answer={}", sessionId, answer);
            memoryService.maybeSummarize(sessionId);
            return answer;
        } catch (Exception e) {
            log.error("[LLM 异常] sync, sessionId={}, elapsed={}ms, error={}",
                    sessionId, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }

    public Flux<String> chatStream(String message, String sessionId) {
        String convId = memoryService.conversationId(sessionId);
        String summary = memoryService.getSummaryContext(sessionId);
        String enrichedMessage = summary.isBlank()
                ? message
                : "【历史摘要】\n" + summary + "\n\n【当前问题】\n" + message;

        log.info("[发送 LLM] stream, sessionId={}, convId={}, messageLength={}", sessionId, convId, enrichedMessage.length());
        long start = System.currentTimeMillis();
        StringBuilder received = new StringBuilder();

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(enrichedMessage)
                .tools(knowledgeSearchTool)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .stream()
                .content()
                .doOnNext(chunk -> {
                    received.append(chunk);
                    log.debug("[接收 LLM] stream chunk, sessionId={}, chunk={}", sessionId, chunk);
                })
                .doOnComplete(() -> {
                    log.info("[接收 LLM] stream 完成, sessionId={}, elapsed={}ms, totalLength={}",
                            sessionId, System.currentTimeMillis() - start, received.length());
                    memoryService.maybeSummarize(sessionId);
                })
                .doOnError(e -> log.error("[LLM 异常] stream, sessionId={}, elapsed={}ms, error={}",
                        sessionId, System.currentTimeMillis() - start, e.getMessage(), e));
    }
}
