package com.shg.trip.shgtrip.domain.user.dto;

import com.shg.trip.shgtrip.domain.user.NicknamePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = NicknamePolicy.MIN_LENGTH, max = NicknamePolicy.MAX_LENGTH, message = "닉네임은 2~20자여야 합니다.")
        // NUL은 Postgres가 거부해 500이 되고, 보이지 않는 문자는 같은 모양의 다른 닉네임을 만들어 사칭에 쓰인다
        @Pattern(regexp = NicknamePolicy.ALLOWED_REGEX,
                message = "닉네임에는 글자나 숫자가 있어야 하고 보이지 않는 문자는 쓸 수 없습니다.")
        String nickname
) {
    /** 중복 검사가 같은 값끼리 비교되도록 저장 형태로 맞춘다. */
    public ProfileUpdateRequest {
        nickname = NicknamePolicy.normalize(nickname);
    }
}
