package com.deluce.oncall.history.repository;

import com.deluce.oncall.history.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageEntity, Long> {

    long countBySession_SessionId(String sessionId);

    void deleteBySession_SessionId(String sessionId);
}
