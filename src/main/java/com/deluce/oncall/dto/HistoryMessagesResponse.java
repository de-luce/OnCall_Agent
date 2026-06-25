package com.deluce.oncall.dto;

import java.util.List;

public record HistoryMessagesResponse(
        String sessionId,
        String title,
        String summary,
        List<HistoryMessageItem> messages
) {
}
