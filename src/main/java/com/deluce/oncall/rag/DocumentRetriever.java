package com.deluce.oncall.rag;

import com.deluce.oncall.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 文档检索器：基于语义相似度从向量库召回相关片段。
 */
@Component
public class DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocumentRetriever.class);

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    public DocumentRetriever(VectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    public List<Document> retrieve(String query) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(ragProperties.topK())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[RAG 检索失败] query={}, error={}", query, e.getMessage());
            log.debug("[RAG 检索失败] 详细堆栈", e);
            return Collections.emptyList();
        }
    }

    public String buildContext(String query) {
        List<Document> documents = retrieve(query);
        if (documents.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            context.append("【片段").append(i + 1).append("】\n")
                    .append(doc.getText())
                    .append("\n\n");
        }
        return context.toString();
    }
}
