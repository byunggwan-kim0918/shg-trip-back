package com.shg.trip.shgtrip.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuthCallbackRequest(
        @NotBlank String provider,
        @NotBlank String code
) {
}
