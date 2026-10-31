package com.shg.trip.shgtrip.domain.planning.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 일정 생성 완료 결과(itineraryId) 임시 저장소.
 * Redis에 TTL 10분으로 저장 — 서버 재시작/멀티 인스턴스 환경에서도 안전.
 * 인증된 owner가 GET /generate/{jobId}/result 로 조회한다(새로고침 재조회를 위해 TTL 동안 삭제하지 않음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationResultStore {

    private static final String KEY_PREFIX = "gen:result:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    /** 값은 "itineraryId:userId" — 조회 시 jobId만 아는 다른 사용자를 걸러낸다. */
    public void save(String jobId, Long itineraryId, Long userId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jobId, itineraryId + ":" + userId, TTL);
    }

    /** 결과가 없거나 owner가 다르면 null. */
    public Long findForOwner(String jobId, Long userId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + jobId);
        if (value == null) return null;
        int sep = value.indexOf(':');
        if (sep < 0 || !value.substring(sep + 1).equals(String.valueOf(userId))) return null;
        return Long.parseLong(value.substring(0, sep));
    }
}
