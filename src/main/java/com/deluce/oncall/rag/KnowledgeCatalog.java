package com.deluce.oncall.rag;

import com.deluce.oncall.dto.KnowledgeCatalogResponse;
import com.deluce.oncall.dto.KnowledgeDocumentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * 维护知识库已入库文档与关键词索引，供前端展示。
 */
@Component
public class KnowledgeCatalog {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCatalog.class);

    private final Path uploadDir;
    private final MilvusVectorStore vectorStore;
    private final ConcurrentMap<String, KnowledgeDocumentItem> documents = new ConcurrentHashMap<>();
    private final Set<String> keywords = ConcurrentHashMap.newKeySet();

    public KnowledgeCatalog(
            @Value("${oncall.upload.storage-dir}") String uploadDir,
            MilvusVectorStore vectorStore) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    void loadExistingUploads() {
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.warn("创建上传目录失败: {}", e.getMessage());
        }
        if (!Files.isDirectory(uploadDir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(uploadDir)) {
            paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(this::registerExistingFile);
        } catch (IOException e) {
            log.warn("扫描上传目录失败: {}", e.getMessage());
        }
        log.info("Knowledge catalog loaded from {} documents={}", uploadDir, documents.size());
    }

    public void registerIngested(Path filePath, List<Document> chunks, int chunkCount) {
        String fileName = filePath.getFileName().toString();
        String displayName = KeywordExtractor.displayNameFromPath(fileName);
        List<String> extracted = KeywordExtractor.extract(chunks, displayName);

        documents.put(fileName, new KnowledgeDocumentItem(
                fileName,
                displayName,
                chunkCount,
                Instant.now().toEpochMilli()
        ));
        keywords.addAll(extracted);
        log.info("[知识库索引] file={}, keywords={}", displayName, extracted.size());
    }

    public KnowledgeCatalogResponse snapshot() {
        List<KnowledgeDocumentItem> documentList = documents.values().stream()
                .sorted(Comparator.comparing(KnowledgeDocumentItem::uploadedAt).reversed())
                .toList();
        List<String> keywordList = new ArrayList<>(keywords);
        keywordList.sort(String::compareToIgnoreCase);
        return new KnowledgeCatalogResponse(keywordList, documentList);
    }

    private void registerExistingFile(Path filePath) {
        String fileName = filePath.getFileName().toString();
        if (documents.containsKey(fileName)) {
            return;
        }
        String displayName = KeywordExtractor.displayNameFromPath(fileName);
        int chunkCount = vectorStore.countBySource(fileName);
        documents.put(fileName, new KnowledgeDocumentItem(
                fileName,
                displayName,
                chunkCount,
                filePath.toFile().lastModified()
        ));
        keywords.add(displayName);
    }
}
