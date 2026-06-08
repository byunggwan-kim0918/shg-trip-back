package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI API 설정.
 * application.yml의 openai 하위 프로퍼티를 바인딩합니다.
 */
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
