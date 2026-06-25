package com.deluce.oncall.dto;

public record HistorySessionItem(
        String sessionId,
        String title,
        String summary,
        long createdAt,
        long updatedAt,
        long messageCount
) {
}
