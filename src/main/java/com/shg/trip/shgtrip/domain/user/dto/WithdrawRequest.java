package com.shg.trip.shgtrip.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

public record WithdrawRequest(@NotBlank String confirm) {
}
