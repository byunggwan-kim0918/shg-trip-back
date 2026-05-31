package com.shg.trip.shgtrip.domain.auth.dto;

import com.shg.trip.shgtrip.domain.user.dto.ProfileResponse;

public record OAuthLoginResult(
        String accessToken,
        String refreshToken,
        boolean isNewUser,
        long refreshMaxAge,
        ProfileResponse profile
) {
}
