package com.deluce.oncall.rag;

import com.deluce.oncall.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档检索器：基于语义相似度从向量库召回相关片段。
 */
@Component
public class DocumentRetriever {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    public DocumentRetriever(VectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    public List<Document> retrieve(String query) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(ragProperties.topK())
                        .build()
        );
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
