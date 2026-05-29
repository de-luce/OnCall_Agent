package com.deluce.oncall.config;

import com.deluce.oncall.embedding.LocalHashEmbeddingModel;
import com.deluce.oncall.embedding.LmStudioEmbeddingClient;
import com.deluce.oncall.embedding.LmStudioEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(name = "oncall.embedding.provider", havingValue = "lmstudio", matchIfMissing = true)
    LmStudioEmbeddingClient lmStudioEmbeddingClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
        return new LmStudioEmbeddingClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "oncall.embedding.provider", havingValue = "lmstudio", matchIfMissing = true)
    EmbeddingModel lmStudioEmbeddingModel(LmStudioEmbeddingClient client, EmbeddingProperties properties) {
        return new LmStudioEmbeddingModel(client, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "oncall.embedding.provider", havingValue = "local")
    EmbeddingModel localEmbeddingModel(EmbeddingProperties properties) {
        int dimensions = properties.dimensions() != null ? properties.dimensions() : LocalHashEmbeddingModel.DEFAULT_DIMENSIONS;
        return new LocalHashEmbeddingModel(dimensions);
    }
}
