package com.deluce.oncall.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文档加载器：将上传文件解析为 Spring AI Document。
 */
@Component
public class DocumentLoader {

    public List<Document> load(Path filePath) {
        String fileName = filePath.getFileName().toString();
        List<Document> documents = new TikaDocumentReader(new FileSystemResource(filePath)).get();

        return documents.stream()
                .map(doc -> {
                    Map<String, Object> metadata = doc.getMetadata();
                    metadata.put("source", fileName);
                    return new Document(doc.getText(), metadata);
                })
                .toList();
    }
}
