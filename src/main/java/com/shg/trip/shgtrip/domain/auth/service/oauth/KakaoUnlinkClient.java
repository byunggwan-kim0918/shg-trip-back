package com.shg.trip.shgtrip.domain.auth.service.oauth;

import com.shg.trip.shgtrip.global.config.WithdrawalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 사용자 토큰을 저장하지 않으므로 Admin 키 + 회원번호 방식으로 연결을 끊는다.
 * 실패는 삼킨다. 우리 쪽 파기가 우선이고, 남은 연결은 다음 로그인 때 동의 화면으로 드러난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkClient {

    private final RestClient restClient;
    private final WithdrawalProperties properties;

    public void unlink(String kakaoUserId) {
        if (!StringUtils.hasText(properties.kakaoAdminKey())) {
            log.warn("KAKAO_ADMIN_KEY 미설정, 카카오 연결 해제 건너뜀");
            return;
        }
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("target_id_type", "user_id");
        body.add("target_id", kakaoUserId);
        try {
            restClient.post()
                    .uri(properties.kakaoUnlinkUri())
                    .header("Authorization", "KakaoAK " + properties.kakaoAdminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("카카오 연결 해제 실패: {}", e.getMessage());
        }
    }
}
