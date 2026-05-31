package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 일정 생성 완료 결과(itineraryId) 임시 저장소.
 * Redis에 TTL 10분으로 저장 — 서버 재시작/멀티 인스턴스 환경에서도 안전.
 * SSE complete 이벤트에 itineraryId를 노출하지 않고,
 * 인증된 클라이언트가 GET /generate/{jobId}/result 로 1회 조회하도록 분리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationResultStore {

    private static final String KEY_PREFIX = "gen:result:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public void save(String jobId, Long itineraryId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jobId, itineraryId.toString(), TTL);
    }

    /**
     * 조회 후 즉시 제거 — 1회성 토큰처럼 동작하여 재사용 방지.
     */
    public Long getAndRemove(String jobId) {
        String key = KEY_PREFIX + jobId;
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "완료된 생성 결과를 찾을 수 없습니다. 이미 조회했거나 만료된 jobId입니다: " + jobId);
        }
        return Long.parseLong(value);
    }
}
