package com.shg.trip.shgtrip.global.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * access token은 서명만 검증해 탈퇴 뒤에도 만료 전까지 통한다. 남은 수명 동안만 Redis에 탈퇴 표시를 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnUserRegistry {

    private static final String KEY_PREFIX = "auth:withdrawn:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public void mark(Long userId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, "1",
                Duration.ofMillis(jwtProperties.accessExpiration()));
    }

    /** Redis 장애 시 통과시킨다. refresh token은 이미 지워져 있어 남는 위험은 access 수명까지다. */
    public boolean isWithdrawn(Long userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
        } catch (Exception e) {
            log.warn("탈퇴 표시 조회 실패(fail-open, user {}): {}", userId, e.getMessage());
            return false;
        }
    }
}
