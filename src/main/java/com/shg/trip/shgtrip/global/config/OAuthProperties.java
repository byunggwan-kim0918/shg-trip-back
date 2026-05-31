package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth")
public record OAuthProperties(
        ProviderProperties kakao,
        ProviderProperties google,
        ProviderProperties naver
) {
    public record ProviderProperties(
            String clientId,
            String clientSecret,
            String tokenUri,
            String userInfoUri,
            String redirectUri
    ) {
    }
}
