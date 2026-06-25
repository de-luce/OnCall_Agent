package com.deluce.oncall.agent;

import com.deluce.oncall.llm.OpenAiStreamClient;
import com.deluce.oncall.memory.ConversationMemoryService;
import com.deluce.oncall.rag.DocumentRetriever;
import com.deluce.oncall.tool.KnowledgeSearchTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话 Agent：同步 ReAct + 知识库工具；流式直连 LM Studio（reasoning / content）。
 */
@Service
public class ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);
    private static final ObjectMapper STREAM_JSON = new ObjectMapper();

    private static final String SYNC_SYSTEM_PROMPT = """
            你是智能 OnCall 对话助手，负责处理高频重复的运维咨询问题。
            
            当问题需要知识库支持时，必须调用 search_knowledge 工具检索，再根据检索结果作答。
            只向用户输出最终答复，不要输出 Thought、Action、Observation 等中间步骤，
            也不要只说「正在检索」却不给出完整结论。
            
            回答要求：简洁专业，优先引用知识库内容，必要时给出排查步骤。
            """;

    private static final String STREAM_SYSTEM_PROMPT = """
            你是智能 OnCall 对话助手，负责处理高频重复的运维咨询问题。
            用户消息中会附带「知识库参考」（可能为空）；请基于参考内容直接给出可执行的答复。
            只输出最终答案，不要输出 Thought、Action、Observation，不要说「正在检索」。
            
            回答要求：简洁专业，优先引用知识库内容，参考不足时补充通用排查建议。
            """;

    private final ChatClient chatClient;
    private final ConversationMemoryService memoryService;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final DocumentRetriever documentRetriever;
    private final OpenAiStreamClient openAiStreamClient;

    public ChatAgent(
            @Qualifier("chatClient") ChatClient chatClient,
            ConversationMemoryService memoryService,
            KnowledgeSearchTool knowledgeSearchTool,
            DocumentRetriever documentRetriever,
            OpenAiStreamClient openAiStreamClient) {
        this.chatClient = chatClient;
        this.memoryService = memoryService;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.documentRetriever = documentRetriever;
        this.openAiStreamClient = openAiStreamClient;
    }

    public String chat(String message, String sessionId) {
        String convId = memoryService.conversationId(sessionId);
        memoryService.ensureLoaded(sessionId);
        String enrichedMessage = enrichMessage(message, sessionId);

        log.info("[发送 LLM] sync, sessionId={}, convId={}, messageLength={}", sessionId, convId, enrichedMessage.length());
        long start = System.currentTimeMillis();
        try {
            String answer = chatClient.prompt()
                    .system(SYNC_SYSTEM_PROMPT)
                    .user(enrichedMessage)
                    .tools(knowledgeSearchTool)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                    .call()
                    .content();
            log.info("[接收 LLM] sync, sessionId={}, elapsed={}ms, answerLength={}",
                    sessionId, System.currentTimeMillis() - start, answer != null ? answer.length() : 0);
            log.debug("[接收 LLM] sync, sessionId={}, answer={}", sessionId, answer);
            if (answer != null && !answer.isBlank()) {
                saveExchange(sessionId, message, answer);
            }
            return answer;
        } catch (Exception e) {
            log.error("[LLM 异常] sync, sessionId={}, elapsed={}ms, error={}",
                    sessionId, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }

    public Flux<String> chatStream(String message, String sessionId, AtomicBoolean cancelled) {
        memoryService.ensureLoaded(sessionId);
        String enrichedMessage = enrichMessage(message, sessionId);
        String ragContext = documentRetriever.buildContext(message);
        String streamUser = buildStreamUserMessage(enrichedMessage, ragContext);

        log.info("[发送 LLM] stream, sessionId={}, messageLength={}, ragContextLength={}",
                sessionId, streamUser.length(), ragContext.length());
        long start = System.currentTimeMillis();
        StringBuilder answerParts = new StringBuilder();
        StringBuilder reasoningParts = new StringBuilder();

        return openAiStreamClient.streamChat(STREAM_SYSTEM_PROMPT, streamUser, cancelled)
                .takeWhile(delta -> !cancelled.get())
                .concatMap(delta -> {
                    List<String> chunkEvents = new ArrayList<>();
                    if (!delta.reasoning().isEmpty()) {
                        reasoningParts.append(delta.reasoning());
                        log.debug("[接收 LLM] stream reasoning, sessionId={}, chunk={}", sessionId, delta.reasoning());
                        chunkEvents.add(streamEvent("reasoning", delta.reasoning(), null, null));
                    }
                    if (!delta.content().isEmpty()) {
                        answerParts.append(delta.content());
                        log.debug("[接收 LLM] stream chunk, sessionId={}, chunk={}", sessionId, delta.content());
                        chunkEvents.add(streamEvent("content", delta.content(), null, null));
                    }
                    return Flux.fromIterable(chunkEvents);
                })
                .concatWith(Flux.defer(() -> {
                    boolean wasCancelled = cancelled.get();
                    String rawAnswer = answerParts.toString();
                    final String answer = rawAnswer.isEmpty()
                            && !reasoningParts.isEmpty()
                            && !wasCancelled
                            ? reasoningParts.toString()
                            : rawAnswer;

                    log.info("[接收 LLM] stream 完成, sessionId={}, elapsed={}ms, totalLength={}, cancelled={}",
                            sessionId, System.currentTimeMillis() - start, answer.length(), wasCancelled);

                    return Flux.just(streamEvent("done", null, answer, wasCancelled))
                            .doOnComplete(() -> {
                                if (!wasCancelled && !answer.isBlank()) {
                                    afterResponsePersist(sessionId, message, answer);
                                }
                            });
                }))
                .doOnError(e -> {
                    if (!cancelled.get()) {
                        log.error("[LLM 异常] stream, sessionId={}, elapsed={}ms, error={}",
                                sessionId, System.currentTimeMillis() - start, e.getMessage(), e);
                    } else {
                        log.info("Chat stream interrupted: {}", e.getMessage());
                    }
                });
    }

    private String enrichMessage(String message, String sessionId) {
        String summary = memoryService.getSummaryContext(sessionId);
        if (summary.isBlank()) {
            return message;
        }
        return "【历史摘要】\n" + summary + "\n\n【当前问题】\n" + message;
    }

    private String buildStreamUserMessage(String enrichedMessage, String ragContext) {
        String reference = ragContext.isBlank() ? "未找到相关知识" : ragContext;
        return enrichedMessage + "\n\n知识库参考：\n" + reference;
    }

    private void saveExchange(String sessionId, String userMessage, String assistantMessage) {
        memoryService.persistExchange(sessionId, userMessage, assistantMessage);
        memoryService.scheduleSummarize(sessionId);
    }

    private void afterResponsePersist(String sessionId, String userMessage, String assistantMessage) {
        memoryService.appendExchange(sessionId, userMessage, assistantMessage);
        memoryService.scheduleSummarize(sessionId);
    }

    private static String streamEvent(String type, String text, String answer, Boolean cancelled) {
        try {
            ObjectNode node = STREAM_JSON.createObjectNode();
            node.put("type", type);
            if (text != null) {
                node.put("text", text);
            }
            if (answer != null) {
                node.put("answer", answer);
            }
            if (cancelled != null) {
                node.put("cancelled", cancelled);
            }
            return STREAM_JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stream event", e);
        }
    }
}
