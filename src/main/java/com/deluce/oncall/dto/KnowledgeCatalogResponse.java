package com.deluce.oncall.dto;

import java.util.List;

public record KnowledgeCatalogResponse(
        List<String> keywords,
        List<KnowledgeDocumentItem> documents,
        int keywordCount,
        int documentCount
) {
}
