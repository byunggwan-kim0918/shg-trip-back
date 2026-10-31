package com.shg.trip.shgtrip.domain.auth.dto;

public record OAuthUserInfo(
        String providerId,
        String email,
        String nickname,
        String profileImage
) {
    public OAuthUserInfo {
        profileImage = toHttps(profileImage);
    }

    /** 카카오 기본 프로필 이미지가 http://로 내려와 https 페이지에서 Mixed Content가 된다. 세 provider CDN 모두 https를 지원한다. */
    public static String toHttps(String url) {
        if (url != null && url.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + url.substring(7);
        }
        return url;
    }
}
