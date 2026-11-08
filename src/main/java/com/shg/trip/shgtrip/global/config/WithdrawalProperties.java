package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param kakaoAdminKey   비어 있으면 카카오 연결 해제를 건너뛴다
 * @param identityHmacKey 비어 있으면 탈퇴 후 재가입 시 생성 쿼터를 이어받지 않는다
 */
@ConfigurationProperties(prefix = "withdrawal")
public record WithdrawalProperties(
        String kakaoAdminKey,
        String identityHmacKey,
        String kakaoUnlinkUri
) {
}
