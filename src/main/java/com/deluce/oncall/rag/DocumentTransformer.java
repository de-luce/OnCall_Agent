package com.deluce.oncall.rag;

import com.deluce.oncall.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档分片器：将长文档切分为适合向量化的片段。
 */
@Component
public class DocumentTransformer {

    private final TokenTextSplitter splitter;

    public DocumentTransformer(RagProperties ragProperties) {
        this.splitter = new TokenTextSplitter(
                ragProperties.chunkSize(),
                ragProperties.chunkOverlap(),
                5,
                10000,
                true
        );
    }

    public List<Document> transform(List<Document> documents) {
        return splitter.apply(documents);
    }
}
