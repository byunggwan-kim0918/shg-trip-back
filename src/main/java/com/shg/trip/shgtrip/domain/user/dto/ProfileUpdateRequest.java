package com.shg.trip.shgtrip.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
        // NUL은 Postgres가 거부해 500이 되므로 저장 전에 400으로 막는다
        @Pattern(regexp = "^\\P{Cc}*$", message = "닉네임에 줄바꿈·제어 문자를 쓸 수 없습니다.")
        String nickname
) {
    public ProfileUpdateRequest {
        nickname = nickname == null ? null : nickname.strip();
    }
}
