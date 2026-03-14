package com.shg.trip.shgtrip.domain.auth.dto;

import com.shg.trip.shgtrip.domain.user.entity.User;

public record OAuthLoginResult(
        String accessToken,
        String refreshToken,
        boolean isNewUser,
        User user
) {
}
