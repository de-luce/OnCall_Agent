package com.deluce.oncall.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RagProperties.class, MemoryProperties.class})
public class OnCallPropertiesConfiguration {
}
