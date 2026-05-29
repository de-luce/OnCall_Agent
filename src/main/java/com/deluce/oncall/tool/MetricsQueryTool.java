package com.deluce.oncall.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 监控指标查询工具（内置示例数据），供运维 Agent 排查故障时调用。
 */
@Component
public class MetricsQueryTool {

    private static final Map<String, String> SAMPLE_METRICS = Map.of(
            "payment-service", """
                    CPU: 92%
                    Memory: 78%
                    QPS: 3200 (baseline: 800)
                    Error Rate: 12.5%
                    DB Connection Pool: 50/50 (100%)
                    P99 Latency: 2800ms
                    """,
            "order-service", """
                    CPU: 45%
                    Memory: 55%
                    QPS: 1500
                    Error Rate: 8.2%
                    Downstream Timeout Rate: 15%
                    P99 Latency: 1200ms
                    """,
            "default", """
                    CPU: 60%
                    Memory: 50%
                    QPS: 500
                    Error Rate: 0.5%
                    """
    );

    @Tool(description = "查询指定服务的核心监控指标，包括 CPU、内存、QPS、错误率、延迟等")
    public String queryMetrics(
            @ToolParam(description = "服务名称") String serviceName) {
        return SAMPLE_METRICS.getOrDefault(serviceName, SAMPLE_METRICS.get("default"));
    }
}
