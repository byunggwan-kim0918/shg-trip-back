package com.shg.trip.shgtrip.domain.auth.dto;

public record TokenRefreshResult(
        String accessToken,
        String refreshToken
) {
}
