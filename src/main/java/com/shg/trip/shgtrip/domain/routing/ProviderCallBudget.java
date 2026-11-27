package com.shg.trip.shgtrip.domain.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 공급자 호출 한도. 여러 서버가 한 한도를 나눠 쓰도록 Valkey 카운터로 센다.
 * 카운터를 못 읽으면 호출하지 않는다. 무료 한도를 넘으면 과금되는 공급자가 있다.
 */
@Slf4j
@Component
public class ProviderCallBudget {

    private static final String PREFIX = "route:budget:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public ProviderCallBudget(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * 한 번 쓸 수 있으면 세고 true. 한도가 없는 공급자는 세지 않는다.
     *
     * @param zone 공급자 일일 한도가 풀리는 자정의 시간대
     */
    public boolean tryAcquire(String providerId, int dailyLimit, int perMinuteLimit, ZoneId zone) {
        return tryAcquire(providerId, dailyLimit, perMinuteLimit, 0, zone);
    }

    /** 분·일·월 한도를 모두 센다. 하나라도 넘으면 false이고, 넘은 뒤 단계는 세지 않는다. */
    public boolean tryAcquire(String providerId, int dailyLimit, int perMinuteLimit, int monthlyLimit, ZoneId zone) {
        if (dailyLimit <= 0 && perMinuteLimit <= 0 && monthlyLimit <= 0) return true;
        try {
            if (perMinuteLimit > 0 && !take(PREFIX + providerId + ":m:" + clock.millis() / 60_000,
                    perMinuteLimit, Duration.ofMinutes(2))) {
                return false;
            }
            if (dailyLimit > 0 && !take(PREFIX + providerId + ":d:" + LocalDate.now(clock.withZone(zone)),
                    dailyLimit, Duration.ofDays(2))) {
                return false;
            }
            return monthlyLimit <= 0 || take(PREFIX + providerId + ":mo:" + YearMonth.now(clock.withZone(zone)),
                    monthlyLimit, Duration.ofDays(40));
        } catch (RuntimeException e) {
            log.warn("route budget unavailable provider={} cause={}", providerId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** 공급자 사용량(오늘·이번 달). 카운터가 없으면 0이다. */
    public Usage usage(String providerId, ZoneId zone) {
        try {
            return new Usage(count(PREFIX + providerId + ":d:" + LocalDate.now(clock.withZone(zone))),
                    count(PREFIX + providerId + ":mo:" + YearMonth.now(clock.withZone(zone))));
        } catch (RuntimeException e) {
            return new Usage(-1, -1);
        }
    }

    /** @param today -1이면 카운터를 읽지 못했다 */
    public record Usage(long today, long thisMonth) {}

    private long count(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    private boolean take(String key, int limit, Duration ttl) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) redis.expire(key, ttl);
        return n != null && n <= limit;
    }
}
