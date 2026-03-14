package com.shg.trip.shgtrip.domain.auth.service.oauth;

import com.shg.trip.shgtrip.domain.auth.dto.OAuthUserInfo;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;

public interface OAuthProviderStrategy {

    OAuthProvider getProvider();

    OAuthUserInfo getUserInfo(String authorizationCode);
}
