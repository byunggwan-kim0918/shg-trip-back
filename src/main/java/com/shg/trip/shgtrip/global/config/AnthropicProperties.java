package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic Claude 모델 설정.
 * application.yml의 anthropic.models 하위 프로퍼티를 바인딩합니다.
 */
@ConfigurationProperties(prefix = "anthropic.models")
public record AnthropicProperties(
        String haiku,
        String sonnet,
        int maxOutputTokens
) {
    public AnthropicProperties {
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 64000;
        }
    }
}
