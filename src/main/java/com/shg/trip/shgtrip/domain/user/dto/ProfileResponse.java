package com.shg.trip.shgtrip.domain.user.dto;

import com.shg.trip.shgtrip.domain.user.entity.User;

public record ProfileResponse(
        Long id,
        String email,
        String nickname,
        String profileImage
) {
    public static ProfileResponse from(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImage()
        );
    }
}
