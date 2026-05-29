package com.deluce.oncall.dto;

public record KnowledgeDocumentItem(
        String fileName,
        String displayName,
        int chunkCount,
        long uploadedAt
) {
}
