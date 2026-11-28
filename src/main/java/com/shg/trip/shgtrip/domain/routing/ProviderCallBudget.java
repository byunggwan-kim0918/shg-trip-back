package com.shg.trip.shgtrip.domain.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공급자 호출 한도. 여러 서버가 한 한도를 나눠 쓰도록 Valkey 카운터로 센다.
 * 카운터를 못 읽으면 호출하지 않는다. 무료 한도를 넘으면 과금되는 공급자가 있다.
 */
@Slf4j
@Component
public class ProviderCallBudget {

    private static final String PREFIX = "route:budget:";
    /** 무료 한도의 이 비율에 닿는 호출에서 한 번 경고한다. 로그 문구 "route budget warning"을 알림이 센다. */
    public static final double WARN_RATIO = 0.8;
    private static final List<String> WINDOWS = List.of("minute", "day", "month");
    private static final List<Duration> TTLS = List.of(Duration.ofMinutes(2), Duration.ofDays(2), Duration.ofDays(40));

    /**
     * 분·일·월 카운터를 한 번에 확인하고 모두 남아 있을 때만 모두 올린다. 따로 올리면 한 단계가 막혀도 앞 단계가 이미 늘고,
     * INCR 뒤 EXPIRE 전에 끊기면 만료 없는 카운터가 남아 한도가 영영 풀리지 않는다.
     * 돌려주는 값: {0, 분, 일, 월 카운터} 또는 {막힌 창(1~3), 그 카운터}.
     */
    private static final RedisScript<List> TAKE = new DefaultRedisScript<>("""
            for i = 1, 3 do
              local limit = tonumber(ARGV[i])
              if limit > 0 then
                local n = tonumber(redis.call('GET', KEYS[i]) or '0')
                if n >= limit then return {i, n} end
              end
            end
            local out = {0}
            for i = 1, 3 do
              if tonumber(ARGV[i]) > 0 then
                local n = redis.call('INCR', KEYS[i])
                if n == 1 then redis.call('EXPIRE', KEYS[i], ARGV[i + 3]) end
                out[#out + 1] = n
              else
                out[#out + 1] = 0
              end
            end
            return out""", List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    /** 서버마다 한도 소진을 창 하나에 한 번만 남긴다. */
    private final Map<String, Instant> exhaustedLogged = new ConcurrentHashMap<>();
    private final Map<String, Event> lastEvents = new ConcurrentHashMap<>();

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

    /** 분·일·월 한도를 모두 센다. 하나라도 넘으면 false이고 어느 카운터도 올리지 않는다. */
    public boolean tryAcquire(String providerId, int dailyLimit, int perMinuteLimit, int monthlyLimit, ZoneId zone) {
        if (dailyLimit <= 0 && perMinuteLimit <= 0 && monthlyLimit <= 0) return true;
        List<String> keys = keys(providerId, zone);
        int[] limits = {perMinuteLimit, dailyLimit, monthlyLimit};
        try {
            List<Long> r = take(keys, String.valueOf(limits[0]), String.valueOf(limits[1]), String.valueOf(limits[2]),
                    seconds(0), seconds(1), seconds(2));
            if (r == null || r.isEmpty()) return false;
            int refused = r.get(0).intValue();
            if (refused > 0) {
                exhausted(providerId, keys.get(refused - 1), WINDOWS.get(refused - 1), limits[refused - 1]);
                return false;
            }
            for (int i = 0; i < 3; i++) {
                if (limits[i] > 0 && r.get(i + 1) == warnAt(limits[i])) warn(providerId, WINDOWS.get(i), r.get(i + 1), limits[i]);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("route budget unavailable provider={} cause={}", providerId, e.getClass().getSimpleName());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    List<Long> take(List<String> keys, String... args) {
        return redis.execute(TAKE, keys, (Object[]) args);
    }

    /** 경고를 보낼 카운터 값. 한도가 작아도 한도 전에 한 번은 울린다. */
    static long warnAt(int limit) {
        return Math.max(1, (long) Math.ceil(limit * WARN_RATIO));
    }

    private void warn(String providerId, String window, long used, int limit) {
        log.warn("route budget warning provider={} window={} used={} limit={}", providerId, window, used, limit);
        lastEvents.put(providerId, new Event("warning", window, used, limit, clock.instant()));
    }

    private void exhausted(String providerId, String key, String window, int limit) {
        lastEvents.put(providerId, new Event("exhausted", window, limit, limit, clock.instant()));
        if (exhaustedLogged.putIfAbsent(key, clock.instant()) == null) {
            log.warn("route budget exhausted provider={} window={} limit={}", providerId, window, limit);
            if (exhaustedLogged.size() > 1000) exhaustedLogged.clear();
        }
    }

    private static String seconds(int window) {
        return String.valueOf(TTLS.get(window).toSeconds());
    }

    private List<String> keys(String providerId, ZoneId zone) {
        // 해시 태그로 한 공급자의 카운터를 같은 슬롯에 둔다. 클러스터 모드에서도 스크립트 하나로 센다.
        String base = PREFIX + "{" + providerId + "}:";
        return List.of(base + "m:" + clock.millis() / 60_000, base + "d:" + LocalDate.now(clock.withZone(zone)),
                base + "mo:" + YearMonth.now(clock.withZone(zone)));
    }

    /** 공급자 사용량(이번 분·오늘·이번 달). 카운터가 없으면 0, 읽지 못하면 모두 -1이다. */
    public Usage usage(String providerId, ZoneId zone) {
        try {
            List<String> keys = keys(providerId, zone);
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null) return new Usage(-1, -1, -1);
            return new Usage(parse(values.get(0)), parse(values.get(1)), parse(values.get(2)));
        } catch (RuntimeException e) {
            return new Usage(-1, -1, -1);
        }
    }

    /** 마지막 한도 경고·소진 기록. 이 서버가 본 것만 있다. */
    public java.util.Optional<Event> lastEvent(String providerId) {
        return java.util.Optional.ofNullable(lastEvents.get(providerId));
    }

    private static long parse(String v) {
        return v == null ? 0 : Long.parseLong(v);
    }

    /** @param today -1이면 카운터를 읽지 못했다 */
    public record Usage(long thisMinute, long today, long thisMonth) {}

    /** @param kind warning(WARN_RATIO 도달) 또는 exhausted(한도 소진으로 거절) */
    public record Event(String kind, String window, long used, int limit, Instant at) {}
}
