package com.shg.trip.shgtrip.domain.auth.dto;

/** 제공자 access token은 탈퇴 연결 해제에만 쓰고 저장하지 않는다. */
public record OAuthSession(OAuthUserInfo userInfo, String accessToken) {
}
