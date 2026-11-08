package com.shg.trip.shgtrip.domain.auth.service.oauth;

import com.shg.trip.shgtrip.domain.auth.dto.OAuthSession;
import com.shg.trip.shgtrip.domain.auth.dto.OAuthUserInfo;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;

public interface OAuthProviderStrategy {

    OAuthProvider getProvider();

    OAuthSession authenticate(String authorizationCode);

    /** 가입·로그인용. 본인 확인만 하는 탈퇴 재인증은 이메일 동의를 요구하지 않는다. */
    default OAuthUserInfo getUserInfo(String authorizationCode) {
        OAuthUserInfo userInfo = authenticate(authorizationCode).userInfo();
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_PROVIDED);
        }
        return userInfo;
    }

    /** 실패해도 예외를 던지지 않는다. 탈퇴는 제공자 응답과 무관하게 끝나야 한다. */
    void revoke(String accessToken);
}
