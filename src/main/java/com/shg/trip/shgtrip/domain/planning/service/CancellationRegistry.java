package com.shg.trip.shgtrip.domain.planning.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TravelPlannerService ↔ ItineraryGenerationExecutor 순환 의존성을 끊으려고 분리했다.
 * <p>
 * 취소 플래그를 Redis에 둬야 "유저당 동시 1개" 불변식이 인스턴스를 넘어 유지된다.
 * 진실의 원천은 Redis 키이고, 로컬 캐시는 매 폴링마다 Redis를 때리지 않기 위한 것이다.
 */
@Component
@RequiredArgsConstructor
public class CancellationRegistry {

    private static final String KEY_PREFIX = "cancel:job:";
    // SSE 타임아웃(10분)보다 약간 길게 두어 진행 중 job이 조기 만료되지 않게 한다.
    private static final Duration TTL = Duration.ofMinutes(12);

    private final StringRedisTemplate redisTemplate;

    /** 진실의 원천은 Redis 키다. */
    private final ConcurrentHashMap<String, Boolean> localCache = new ConcurrentHashMap<>();

    public void cancel(String jobId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jobId, "1", TTL);
        localCache.put(jobId, Boolean.TRUE);
    }

    /** 로컬에 없으면 Redis 키로 다른 인스턴스의 취소를 감지해 로컬에도 반영한다. */
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

    public void remove(String jobId) {
        localCache.remove(jobId);
        redisTemplate.delete(KEY_PREFIX + jobId);
    }
}
