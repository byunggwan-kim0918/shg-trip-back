package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAIProperties(
        String apiKey,
        String embeddingModel,
        int embeddingDimensions
) {
    public OpenAIProperties {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "text-embedding-3-small";
        }
        if (embeddingDimensions <= 0) {
            embeddingDimensions = 1536;
        }
    }
}
