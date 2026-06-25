package com.deluce.oncall.history.repository;

import com.deluce.oncall.history.entity.ChatSessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionJpaRepository extends JpaRepository<ChatSessionEntity, String> {

    Page<ChatSessionEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
