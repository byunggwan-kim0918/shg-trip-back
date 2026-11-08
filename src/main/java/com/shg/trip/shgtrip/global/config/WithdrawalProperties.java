package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param identityHmacKey 비어 있으면 탈퇴 후 재가입 시 생성 쿼터를 이어받지 않는다
 */
@ConfigurationProperties(prefix = "withdrawal")
public record WithdrawalProperties(String identityHmacKey) {
}
