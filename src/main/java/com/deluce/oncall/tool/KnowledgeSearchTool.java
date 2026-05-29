package com.deluce.oncall.tool;

import com.deluce.oncall.rag.DocumentRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具，供对话 Agent 在 ReAct 循环中调用。
 */
@Component
public class KnowledgeSearchTool {

    private final DocumentRetriever documentRetriever;

    public KnowledgeSearchTool(DocumentRetriever documentRetriever) {
        this.documentRetriever = documentRetriever;
    }

    @Tool(description = "从企业运维知识库中检索与问题相关的文档片段，用于回答 OnCall 咨询类问题")
    public String searchKnowledge(@ToolParam(description = "用户问题或检索关键词") String query) {
        return documentRetriever.buildContext(query);
    }
}
