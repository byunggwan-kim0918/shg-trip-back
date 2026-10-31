package com.shg.trip.shgtrip.domain.auth.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthUserInfoTest {

    @Test
    @DisplayName("http 프로필 이미지는 https로 바꿔 저장한다")
    void upgradesHttpProfileImage() {
        OAuthUserInfo info = new OAuthUserInfo("1", "a@b.c", "nick", "http://k.kakaocdn.net/img.jpg");
        assertThat(info.profileImage()).isEqualTo("https://k.kakaocdn.net/img.jpg");
    }

    @Test
    @DisplayName("https·null은 그대로 둔다")
    void keepsHttpsAndNull() {
        assertThat(OAuthUserInfo.toHttps("https://lh3.googleusercontent.com/a")).isEqualTo("https://lh3.googleusercontent.com/a");
        assertThat(OAuthUserInfo.toHttps(null)).isNull();
    }
}
