package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cookie")
public record CookieProperties(Boolean secure) {

    public CookieProperties {
        if (secure == null) {
            throw new IllegalStateException("cookie.secure 프로퍼티가 설정되지 않았습니다. 프로파일별 설정을 확인하세요.");
        }
    }
}
