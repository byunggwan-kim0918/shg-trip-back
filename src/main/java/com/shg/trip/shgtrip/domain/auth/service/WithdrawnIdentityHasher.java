package com.shg.trip.shgtrip.domain.auth.service;

import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.global.config.WithdrawalProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 탈퇴 후에도 같은 소셜 계정을 알아보되 providerId 원문은 남기지 않는다.
 * 키 없이 해시만 쓰면 providerId 공간이 작아 역산된다.
 */
@Component
@RequiredArgsConstructor
public class WithdrawnIdentityHasher {

    private final WithdrawalProperties properties;

    public Optional<String> hash(OAuthProvider provider, String providerId) {
        if (!StringUtils.hasText(properties.identityHmacKey())) {
            return Optional.empty();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.identityHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((provider.name() + ":" + providerId).getBytes(StandardCharsets.UTF_8));
            return Optional.of(HexFormat.of().formatHex(digest));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 사용 불가", e);
        }
    }
}
