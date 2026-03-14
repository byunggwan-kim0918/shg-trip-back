package com.shg.trip.shgtrip.domain.auth.service.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.auth.dto.OAuthUserInfo;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.global.config.OAuthProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthStrategy implements OAuthProviderStrategy {

    private final RestClient restClient;
    private final OAuthProperties oAuthProperties;

    @Override
    public OAuthProvider getProvider() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public OAuthUserInfo getUserInfo(String authorizationCode) {
        String accessToken = exchangeToken(authorizationCode);
        return fetchUserInfo(accessToken);
    }

    private String exchangeToken(String code) {
        OAuthProperties.ProviderProperties props = oAuthProperties.kakao();

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", props.clientId());
        body.add("client_secret", props.clientSecret());
        body.add("redirect_uri", props.redirectUri());
        body.add("code", code);

        try {
            JsonNode response = restClient.post()
                    .uri(props.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return response.get("access_token").asText();
        } catch (Exception e) {
            log.error("카카오 토큰 교환 실패", e);
            throw new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }

    private OAuthUserInfo fetchUserInfo(String accessToken) {
        try {
            JsonNode response = restClient.get()
                    .uri(oAuthProperties.kakao().userInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);

            String providerId = response.get("id").asText();

            JsonNode kakaoAccount = response.get("kakao_account");
            String email = kakaoAccount != null && kakaoAccount.has("email")
                    ? kakaoAccount.get("email").asText() : null;

            if (email == null || email.isBlank()) {
                throw new BusinessException(ErrorCode.EMAIL_NOT_PROVIDED);
            }

            JsonNode profile = kakaoAccount.get("profile");
            String nickname = profile != null && profile.has("nickname")
                    ? profile.get("nickname").asText() : null;
            String profileImage = profile != null && profile.has("profile_image_url")
                    ? profile.get("profile_image_url").asText() : null;

            return new OAuthUserInfo(providerId, email, nickname, profileImage);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("카카오 사용자 정보 조회 실패", e);
            throw new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }
}
