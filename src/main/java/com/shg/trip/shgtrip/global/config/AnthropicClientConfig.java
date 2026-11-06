package com.shg.trip.shgtrip.global.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 모델은 빈이 아니라 호출 시점에 MessageCreateParams에서 지정한다. */
@Configuration
public class AnthropicClientConfig {

    @Bean
    public AnthropicClient anthropicClient(
            @Value("${ANTHROPIC_API_KEY:}") String apiKey) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }
}
