-- OnCall Agent
CREATE DATABASE IF NOT EXISTS oncall_agent
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE oncall_agent;

CREATE TABLE IF NOT EXISTS chat_sessions (
    session_id VARCHAR(64) NOT NULL PRIMARY KEY,
    title      VARCHAR(256) NOT NULL DEFAULT '新会话',
    summary    TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_messages (
    id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_messages_session
    FOREIGN KEY (session_id) REFERENCES chat_sessions (session_id) ON DELETE CASCADE,
    INDEX idx_chat_messages_session_id (session_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
