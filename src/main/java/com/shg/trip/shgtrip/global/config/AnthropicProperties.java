package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
