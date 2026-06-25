package com.deluce.oncall.history;

import com.deluce.oncall.dto.HistoryMessageItem;
import com.deluce.oncall.dto.HistorySessionItem;
import com.deluce.oncall.history.entity.ChatMessageEntity;
import com.deluce.oncall.history.entity.ChatSessionEntity;
import com.deluce.oncall.history.repository.ChatMessageJpaRepository;
import com.deluce.oncall.history.repository.ChatSessionJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatHistoryRepository {

    private final ChatSessionJpaRepository sessionRepository;
    private final ChatMessageJpaRepository messageRepository;

    public ChatHistoryRepository(
            ChatSessionJpaRepository sessionRepository,
            ChatMessageJpaRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    public void ensureSession(String sessionId, String title) {
        sessionRepository.findById(sessionId).ifPresentOrElse(
                record -> {
                    if (title != null && "新会话".equals(record.getTitle())) {
                        record.setTitle(truncateTitle(title));
                        sessionRepository.save(record);
                    }
                },
                () -> {
                    ChatSessionEntity record = new ChatSessionEntity();
                    record.setSessionId(sessionId);
                    record.setTitle(truncateTitle(title != null ? title : "新会话"));
                    sessionRepository.save(record);
                }
        );
    }

    @Transactional
    public void appendExchange(String sessionId, String userMessage, String assistantMessage) {
        ChatSessionEntity record = sessionRepository.findById(sessionId).orElseGet(() -> {
            ChatSessionEntity created = new ChatSessionEntity();
            created.setSessionId(sessionId);
            created.setTitle(truncateTitle(userMessage));
            return created;
        });
        if ("新会话".equals(record.getTitle())) {
            record.setTitle(truncateTitle(userMessage));
        }
        record.touchUpdatedAt();

        ChatMessageEntity user = new ChatMessageEntity();
        user.setRole("user");
        user.setContent(userMessage);
        record.addMessage(user);

        ChatMessageEntity assistant = new ChatMessageEntity();
        assistant.setRole("assistant");
        assistant.setContent(assistantMessage);
        record.addMessage(assistant);

        sessionRepository.save(record);
    }

    @Transactional(readOnly = true)
    public LoadedSession loadSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .map(record -> new LoadedSession(
                        toMessageItems(record),
                        record.getSummary() != null ? record.getSummary() : ""
                ))
                .orElseGet(() -> new LoadedSession(List.of(), ""));
    }

    @Transactional
    public void updateSummary(String sessionId, String summary) {
        sessionRepository.findById(sessionId).ifPresent(record -> {
            record.setSummary(summary);
            record.touchUpdatedAt();
            sessionRepository.save(record);
        });
    }

    @Transactional(readOnly = true)
    public List<HistorySessionItem> listSessions(int limit, int offset) {
        int page = offset / Math.max(limit, 1);
        var sessions = sessionRepository.findAllByOrderByUpdatedAtDesc(PageRequest.of(page, limit));
        List<HistorySessionItem> result = new ArrayList<>();
        for (ChatSessionEntity session : sessions) {
            long messageCount = messageRepository.countBySession_SessionId(session.getSessionId());
            result.add(new HistorySessionItem(
                    session.getSessionId(),
                    session.getTitle(),
                    session.getSummary(),
                    toEpochMillis(session.getCreatedAt()),
                    toEpochMillis(session.getUpdatedAt()),
                    messageCount
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<SessionMeta> getSession(String sessionId) {
        return sessionRepository.findById(sessionId).map(session -> new SessionMeta(
                session.getSessionId(),
                session.getTitle(),
                session.getSummary(),
                toEpochMillis(session.getCreatedAt()),
                toEpochMillis(session.getUpdatedAt())
        ));
    }

    @Transactional
    public boolean deleteSession(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            return false;
        }
        messageRepository.deleteBySession_SessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        return true;
    }

    private List<HistoryMessageItem> toMessageItems(ChatSessionEntity record) {
        List<HistoryMessageItem> messages = new ArrayList<>();
        for (ChatMessageEntity item : record.getMessages()) {
            messages.add(new HistoryMessageItem(item.getRole(), item.getContent()));
        }
        return messages;
    }

    static String truncateTitle(String text) {
        if (text == null || text.isBlank()) {
            return "新会话";
        }
        String cleaned = text.trim().replaceAll("\\s+", " ");
        if (cleaned.length() <= 48) {
            return cleaned.isEmpty() ? "新会话" : cleaned;
        }
        return cleaned.substring(0, 47) + "…";
    }

    private static long toEpochMillis(java.time.LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public record LoadedSession(List<HistoryMessageItem> messages, String summary) {
    }

    public record SessionMeta(
            String sessionId,
            String title,
            String summary,
            long createdAt,
            long updatedAt
    ) {
    }
}
