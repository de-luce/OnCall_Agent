package com.deluce.oncall.agent;

import com.deluce.oncall.rag.DocumentLoader;
import com.deluce.oncall.rag.DocumentRetriever;
import com.deluce.oncall.rag.DocumentTransformer;
import com.deluce.oncall.rag.KnowledgeCatalog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 知识库 Agent：RAG 检索增强生成，负责领域知识问答。
 */
@Service
public class KnowledgeAgent {

    private static final String SYSTEM_PROMPT = """
            你是企业 OnCall 知识库助手，专门回答运维、故障排查、SOP 等领域问题。
            请严格基于提供的知识库片段回答，不要编造不存在的信息。
            如果知识库中没有相关内容，请明确说明并给出通用排查建议。
            """;

    private final DocumentLoader documentLoader;
    private final DocumentTransformer documentTransformer;
    private final VectorStore vectorStore;
    private final DocumentRetriever documentRetriever;
    private final KnowledgeCatalog knowledgeCatalog;
    private final ChatClient llmChatClient;

    public KnowledgeAgent(
            DocumentLoader documentLoader,
            DocumentTransformer documentTransformer,
            VectorStore vectorStore,
            DocumentRetriever documentRetriever,
            KnowledgeCatalog knowledgeCatalog,
            @Qualifier("llmChatClient") ChatClient llmChatClient) {
        this.documentLoader = documentLoader;
        this.documentTransformer = documentTransformer;
        this.vectorStore = vectorStore;
        this.documentRetriever = documentRetriever;
        this.knowledgeCatalog = knowledgeCatalog;
        this.llmChatClient = llmChatClient;
    }

    public int ingestDocument(Path filePath) throws Exception {
        List<Document> rawDocuments = documentLoader.load(filePath);
        List<Document> chunks = documentTransformer.transform(rawDocuments);
        vectorStore.add(chunks);
        knowledgeCatalog.registerIngested(filePath, chunks, chunks.size());
        return chunks.size();
    }

    public String answer(String question) {
        String context = documentRetriever.buildContext(question);
        if (context.isBlank()) {
            return llmChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .content();
        }
        return llmChatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(u -> u.text("""
                        用户问题：{question}

                        知识库参考：
                        {context}

                        请基于以上参考回答用户问题。
                        """)
                        .param("question", question)
                        .param("context", context))
                .call()
                .content();
    }
}
