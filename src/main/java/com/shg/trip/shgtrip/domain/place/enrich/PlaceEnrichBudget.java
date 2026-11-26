package com.shg.trip.shgtrip.domain.place.enrich;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 승격 장소 즉시 보강의 시간당·일일 Haiku 호출 상한. 인스턴스가 여럿이어도 Valkey 한 카운터로 센다. */
@Slf4j
@Component
public class PlaceEnrichBudget {

    private static final String KEY_PREFIX = "place-enrich:calls:";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final long maxPerHour;
    private final long maxPerDay;

    @Autowired
    public PlaceEnrichBudget(StringRedisTemplate redis,
                             @Value("${place.promote-enrich.max-per-hour:60}") long maxPerHour,
                             @Value("${place.promote-enrich.max-per-day:300}") long maxPerDay) {
        this(redis, Clock.systemUTC(), maxPerHour, maxPerDay);
    }

    PlaceEnrichBudget(StringRedisTemplate redis, Clock clock, long maxPerHour, long maxPerDay) {
        this.redis = redis;
        this.clock = clock;
        this.maxPerHour = maxPerHour;
        this.maxPerDay = maxPerDay;
    }

    /** 호출 한 번을 쓴다. 상한을 넘었거나 카운터를 읽지 못하면 쓰지 않고 false다. */
    public boolean tryAcquire() {
        String hourKey = KEY_PREFIX + "h:" + HOUR.format(clock.instant());
        String dayKey = KEY_PREFIX + "d:" + DAY.format(clock.instant());
        try {
            long hour = increment(hourKey, Duration.ofHours(2));
            long day = increment(dayKey, Duration.ofDays(2));
            if (hour <= maxPerHour && day <= maxPerDay) return true;
            redis.opsForValue().decrement(hourKey);
            redis.opsForValue().decrement(dayKey);
            log.warn("승격 장소 보강 호출 상한 도달 - 이번 시간 {}/{}, 오늘 {}/{}", hour - 1, maxPerHour, day - 1, maxPerDay);
            return false;
        } catch (RuntimeException e) {
            // 카운터 없이 부르면 상한이 꺼진 채 호출이 나간다.
            log.warn("승격 장소 보강 호출 상한 확인 실패, 이번 차례를 미룸: {}", e.getMessage());
            return false;
        }
    }

    private long increment(String key, Duration ttl) {
        Long value = redis.opsForValue().increment(key);
        if (value == null) throw new IllegalStateException("카운터 응답 없음: " + key);
        if (value == 1L) redis.expire(key, ttl);
        return value;
    }
}
