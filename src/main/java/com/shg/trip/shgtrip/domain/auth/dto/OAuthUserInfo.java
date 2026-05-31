package com.shg.trip.shgtrip.domain.auth.dto;

public record OAuthUserInfo(
        String providerId,
        String email,
        String nickname,
        String profileImage
) {
}
