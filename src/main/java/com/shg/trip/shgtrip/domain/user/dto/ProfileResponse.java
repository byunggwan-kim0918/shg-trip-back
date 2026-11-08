package com.shg.trip.shgtrip.domain.user.dto;

import com.shg.trip.shgtrip.domain.auth.dto.OAuthUserInfo;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.domain.user.entity.User;

public record ProfileResponse(
        Long id,
        String email,
        String nickname,
        String profileImage,
        OAuthProvider provider
) {
    public static ProfileResponse from(User user) {
        return from(user, null);
    }

    /** provider는 탈퇴 재로그인 대상을 고르는 데 쓴다. */
    public static ProfileResponse from(User user, OAuthProvider provider) {
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                // 이미 http://로 저장된 기존 가입자 행
                OAuthUserInfo.toHttps(user.getProfileImage()),
                provider
        );
    }
}
