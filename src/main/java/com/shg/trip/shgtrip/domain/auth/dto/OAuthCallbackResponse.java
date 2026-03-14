package com.shg.trip.shgtrip.domain.auth.dto;

import com.shg.trip.shgtrip.domain.user.dto.ProfileResponse;

public record OAuthCallbackResponse(
        String accessToken,
        boolean isNewUser,
        ProfileResponse user
) {
}
