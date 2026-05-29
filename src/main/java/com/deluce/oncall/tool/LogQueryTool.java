package com.deluce.oncall.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 日志查询工具（内置示例数据），供运维 Agent 排查故障时调用。
 */
@Component
public class LogQueryTool {

    private static final Map<String, String> SAMPLE_LOGS = Map.of(
            "payment-service", """
                    [ERROR] 2026-05-29 09:12:01 Connection pool exhausted, active=50 max=50
                    [WARN]  2026-05-29 09:11:58 Slow query detected: SELECT * FROM orders WHERE status=?
                    [INFO]  2026-05-29 09:10:00 Traffic spike detected, QPS=3200
                    """,
            "order-service", """
                    [ERROR] 2026-05-29 09:12:05 Timeout calling payment-service after 3000ms
                    [WARN]  2026-05-29 09:11:50 Circuit breaker half-open for payment-service
                    """,
            "default", """
                    [INFO] 2026-05-29 09:00:00 Service started successfully
                    [WARN] 2026-05-29 09:05:00 CPU usage above 80%
                    """
    );

    @Tool(description = "查询指定服务最近的关键日志，用于故障根因分析")
    public String queryLogs(
            @ToolParam(description = "服务名称，如 payment-service、order-service") String serviceName,
            @ToolParam(description = "日志级别过滤：ERROR、WARN、INFO，留空表示全部") String level) {
        String logs = SAMPLE_LOGS.getOrDefault(serviceName, SAMPLE_LOGS.get("default"));
        if (level != null && !level.isBlank()) {
            return logs.lines()
                    .filter(line -> line.contains("[" + level.toUpperCase() + "]"))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("未找到 " + level + " 级别日志");
        }
        return "查询时间: " + Instant.now() + "\n服务: " + serviceName + "\n" + logs;
    }
}
