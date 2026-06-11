package com.deluce.oncall.agent;

import com.deluce.oncall.tool.AlertAckTool;
import com.deluce.oncall.tool.LogQueryTool;
import com.deluce.oncall.tool.MetricsQueryTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运维 Agent：Plan-Execute-Replan 模式，自动接收告警、分步排查、分析根因。
 */
@Service
public class OpsAgent {

    private static final String PLAN_PROMPT = """
            你是资深 SRE 工程师。根据告警信息制定排查计划。
            输出 JSON 数组，每个元素包含 step（步骤描述）和 tool（可选工具名：queryLogs/queryMetrics/acknowledgeAlert/none）。
            示例：[{"step":"查看 payment-service 错误日志","tool":"queryLogs"},{"step":"检查 CPU 和连接池指标","tool":"queryMetrics"}]
            只输出 JSON，不要其他文字。
            """;

    private static final String REPLAN_PROMPT = """
            你是资深 SRE 工程师。根据当前排查结果，判断是否需要调整计划。
            如果需要追加步骤，输出新的 JSON 数组；如果已足够，输出空数组 []。
            只输出 JSON，不要其他文字。
            """;

    private static final String REPORT_PROMPT = """
            你是资深 SRE 工程师。根据告警、排查步骤和工具返回结果，生成故障分析报告。
            报告需包含：根因分析、影响范围、处理建议、后续优化措施。
            """;

    private final ChatClient chatClient;
    private final LogQueryTool logQueryTool;
    private final MetricsQueryTool metricsQueryTool;
    private final AlertAckTool alertAckTool;
    private final ObjectMapper objectMapper;

    public OpsAgent(
            @Qualifier("llmChatClient") ChatClient chatClient,
            LogQueryTool logQueryTool,
            MetricsQueryTool metricsQueryTool,
            AlertAckTool alertAckTool,
            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.logQueryTool = logQueryTool;
        this.metricsQueryTool = metricsQueryTool;
        this.alertAckTool = alertAckTool;
        this.objectMapper = objectMapper;
    }

    public OpsResult investigate(String alertMessage, String serviceName) {
        String service = serviceName != null && !serviceName.isBlank() ? serviceName : "payment-service";
        List<String> executedSteps = new ArrayList<>();
        StringBuilder observations = new StringBuilder();

        // Plan
        List<PlanStep> plan = parsePlan(chatClient.prompt()
                .system(PLAN_PROMPT)
                .user("告警：" + alertMessage + "\n服务：" + service)
                .call()
                .content());
        executedSteps.add("制定排查计划，共 " + plan.size() + " 步");

        // Execute
        for (PlanStep step : plan) {
            String result = executeStep(step, service);
            executedSteps.add("执行: " + step.step());
            observations.append("步骤: ").append(step.step()).append("\n结果:\n").append(result).append("\n\n");
        }

        // Replan
        List<PlanStep> replan = parsePlan(chatClient.prompt()
                .system(REPLAN_PROMPT)
                .user("告警：" + alertMessage + "\n已有结果：\n" + observations)
                .call()
                .content());

        for (PlanStep step : replan) {
            String result = executeStep(step, service);
            executedSteps.add("追加: " + step.step());
            observations.append("追加步骤: ").append(step.step()).append("\n结果:\n").append(result).append("\n\n");
        }

        // Report
        String report = chatClient.prompt()
                .system(REPORT_PROMPT)
                .user("""
                        告警信息：%s
                        服务：%s
                        排查结果：
                        %s
                        """.formatted(alertMessage, service, observations))
                .call()
                .content();

        String rootCause = extractSection(report, "根因");
        String recommendation = extractSection(report, "处理建议");

        alertAckTool.acknowledgeAlert(alertMessage, "自动排查完成");

        return new OpsResult(rootCause, recommendation, executedSteps, report);
    }

    private String executeStep(PlanStep step, String service) {
        String tool = step.tool();
        if (tool == null || tool.isBlank() || "none".equalsIgnoreCase(tool)) {
            return "（无需工具，已完成分析步骤）";
        }
        return switch (tool) {
            case "queryLogs" -> logQueryTool.queryLogs(service, "ERROR");
            case "queryMetrics" -> metricsQueryTool.queryMetrics(service);
            case "acknowledgeAlert" -> alertAckTool.acknowledgeAlert(service, step.step());
            default -> "（无需工具，已完成分析步骤）";
        };
    }

    private List<PlanStep> parsePlan(String json) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json?", "").replace("```", "").trim();
            }
            List<PlanStep> steps = objectMapper.readValue(cleaned, new TypeReference<>() {});
            return steps.stream()
                    .map(s -> new PlanStep(
                            s.step() != null ? s.step() : "未命名步骤",
                            s.tool() != null && !s.tool().isBlank() ? s.tool() : "none"))
                    .toList();
        } catch (Exception e) {
            return List.of(
                    new PlanStep("查看 " + "payment-service" + " 错误日志", "queryLogs"),
                    new PlanStep("检查服务监控指标", "queryMetrics")
            );
        }
    }

    private String extractSection(String report, String keyword) {
        for (String line : report.split("\n")) {
            if (line.contains(keyword)) {
                return line.replaceAll(".*[:：]", "").trim();
            }
        }
        return report.length() > 200 ? report.substring(0, 200) + "..." : report;
    }

    public record PlanStep(String step, String tool) {
    }

    public record OpsResult(String rootCause, String recommendation, List<String> executedSteps, String report) {
    }
}
