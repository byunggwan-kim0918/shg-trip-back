package com.shg.trip.shgtrip.domain.auth.service.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.auth.dto.OAuthSession;
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
public class NaverOAuthStrategy implements OAuthProviderStrategy {

    private final RestClient restClient;
    private final OAuthProperties oAuthProperties;

    @Override
    public OAuthProvider getProvider() {
        return OAuthProvider.NAVER;
    }

    @Override
    public OAuthSession authenticate(String authorizationCode) {
        String accessToken = exchangeToken(authorizationCode);
        return new OAuthSession(fetchUserInfo(accessToken), accessToken);
    }

    @Override
    public void revoke(String accessToken) {
        try {
            OAuthProperties.ProviderProperties props = oAuthProperties.naver();
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "delete");
            body.add("client_id", props.clientId());
            body.add("client_secret", props.clientSecret());
            body.add("access_token", accessToken);
            body.add("service_provider", "NAVER");
            JsonNode response = restClient.post()
                    .uri(props.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            // 실패도 200으로 오고 본문의 error로 구분된다.
            if (response == null || response.has("error")) {
                log.warn("네이버 연결 해제 실패: {}", response == null ? "빈 응답" : response.path("error").asText());
            }
        } catch (Exception e) {
            log.warn("네이버 연결 해제 실패: {}", e.getMessage());
        }
    }

    private String exchangeToken(String code) {
        OAuthProperties.ProviderProperties props = oAuthProperties.naver();

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
            log.error("네이버 토큰 교환 실패", e);
            throw new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }

    private OAuthUserInfo fetchUserInfo(String accessToken) {
        try {
            JsonNode response = restClient.get()
                    .uri(oAuthProperties.naver().userInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode responseBody = response.get("response");
            if (responseBody == null) {
                throw new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
            }

            String providerId = responseBody.get("id").asText();
            String email = responseBody.has("email") ? responseBody.get("email").asText() : null;

            String nickname = responseBody.has("nickname") ? responseBody.get("nickname").asText() : null;
            String profileImage = responseBody.has("profile_image") ? responseBody.get("profile_image").asText() : null;

            return new OAuthUserInfo(providerId, email, nickname, profileImage);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("네이버 사용자 정보 조회 실패", e);
            throw new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }
}
