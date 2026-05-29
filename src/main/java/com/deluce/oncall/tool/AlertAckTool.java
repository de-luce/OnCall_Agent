package com.deluce.oncall.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 告警确认工具（模拟），供运维 Agent 执行标准化操作。
 */
@Component
public class AlertAckTool {

    @Tool(description = "确认并记录告警处理动作，用于标准化 OnCall 响应流程")
    public String acknowledgeAlert(
            @ToolParam(description = "告警 ID 或告警摘要") String alertId,
            @ToolParam(description = "处理动作描述") String action) {
        return "告警 [" + alertId + "] 已记录处理动作: " + action;
    }
}
