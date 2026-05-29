package com.deluce.oncall.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档索引器：将分片后的文档向量化并写入向量库。
 */
@Component
public class DocumentIndexer {

    private final VectorStore vectorStore;

    public DocumentIndexer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int index(List<Document> documents) {
        vectorStore.add(documents);
        return documents.size();
    }
}
