package com.deluce.oncall.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文档加载器：将上传文件解析为 Spring AI Document。
 */
@Component
public class DocumentLoader {

    public List<Document> load(Path filePath) throws IOException {
        Resource resource = new FileSystemResource(filePath);
        String fileName = filePath.getFileName().toString();
        String contentType = Files.probeContentType(filePath);

        List<Document> documents;
        if (contentType != null && contentType.contains("pdf")) {
            documents = new TikaDocumentReader(resource).get();
        } else {
            documents = new TikaDocumentReader(resource).get();
        }

        return documents.stream()
                .map(doc -> {
                    Map<String, Object> metadata = doc.getMetadata();
                    metadata.put("source", fileName);
                    metadata.put("file_path", filePath.toString());
                    return new Document(doc.getText(), metadata);
                })
                .toList();
    }
}
