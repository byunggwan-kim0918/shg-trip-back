package com.shg.trip.shgtrip.global.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic Java SDK 클라이언트 빈 설정.
 * 환경변수 ANTHROPIC_API_KEY로 API 키를 주입받습니다.
 * 모델 선택은 호출 시점에 MessageCreateParams에서 지정합니다.
 */
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
