package com.shg.trip.shgtrip.domain.planning.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비동기 생성 작업의 취소 플래그 레지스트리.
 * TravelPlannerService ↔ ItineraryGenerationExecutor 순환 의존성을 방지하기 위해 분리.
 * <p>
 * 다중 인스턴스 대응: 취소 플래그를 Redis에 저장해 인스턴스 간 공유한다. 유저가 다른
 * 인스턴스로 붙어 새 생성을 시작하면, 기존 인스턴스에서 도는 executor가 다음 폴링
 * 시점에 Redis 키를 읽어 취소를 감지한다("유저당 동시 1개" 불변식이 인스턴스를 넘어 유지됨).
 * <p>
 * 진실의 원천은 Redis 키(at-least-once). 로컬 캐시는 정상 경로(취소 없음)에서 매 폴링마다
 * Redis를 때리지 않도록 하는 최적화일 뿐이다 — 로컬에 취소 표시가 있으면 즉시 true를 반환하고,
 * 없을 때만 Redis를 확인한다.
 */
@Component
@RequiredArgsConstructor
public class CancellationRegistry {

    private static final String KEY_PREFIX = "cancel:job:";
    // SSE 타임아웃(10분)보다 약간 길게 두어 진행 중 job이 조기 만료되지 않게 한다.
    private static final Duration TTL = Duration.ofMinutes(12);

    private final StringRedisTemplate redisTemplate;

    /** 로컬 캐시 — Redis 왕복을 줄이기 위한 최적화(진실의 원천은 Redis 키). */
    private final ConcurrentHashMap<String, Boolean> localCache = new ConcurrentHashMap<>();

    /** 작업 취소 플래그 설정 */
    public void cancel(String jobId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jobId, "1", TTL);
        localCache.put(jobId, Boolean.TRUE);
    }

    /**
     * 취소 여부 확인.
     * 로컬 캐시에 취소 표시가 있으면 즉시 true. 없으면 Redis 키를 확인해
     * 다른 인스턴스에서 발생한 취소를 감지하고, 확인되면 로컬 캐시에도 반영한다.
     */
    public boolean isCancelled(String jobId) {
        if (Boolean.TRUE.equals(localCache.get(jobId))) {
            return true;
        }
        boolean cancelled = Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jobId));
        if (cancelled) {
            localCache.put(jobId, Boolean.TRUE);
        }
        return cancelled;
    }

    /** 작업 완료 후 플래그 정리 */
    public void remove(String jobId) {
        localCache.remove(jobId);
        redisTemplate.delete(KEY_PREFIX + jobId);
    }
}
