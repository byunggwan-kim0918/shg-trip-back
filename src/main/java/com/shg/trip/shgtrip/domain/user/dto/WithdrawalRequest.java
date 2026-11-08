package com.shg.trip.shgtrip.domain.user.dto;

import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 탈퇴 직전 소셜 재로그인으로 받은 authorization code. */
public record WithdrawalRequest(@NotNull OAuthProvider provider, @NotBlank String code) {
}
