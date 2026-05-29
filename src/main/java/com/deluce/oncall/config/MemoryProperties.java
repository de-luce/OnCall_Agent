package com.deluce.oncall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oncall.memory")
public record MemoryProperties(int maxMessages, int summaryThreshold) {
}
