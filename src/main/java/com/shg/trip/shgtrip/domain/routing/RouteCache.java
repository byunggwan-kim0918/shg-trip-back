package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 공급자별 경로 결과 캐시. 출발 시각은 공급자 설정의 간격으로 묶고, 실제로 물어본 시각을 함께 둔다.
 * 묶인 결과를 다른 출발 시각에 그대로 쓰지 않도록 꺼낼 때 {@link #fitTo}로 맞춘다.
 */
@Slf4j
@Component
public class RouteCache {

    private static final String PREFIX = "route:v2:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /** 저장 단위. queriedAt은 공급자에 실제로 넘긴 출발 시각이다. */
    record Entry(OffsetDateTime queriedAt, RouteResult result) {}

    public RouteCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        // 기본 설정은 읽을 때 시각을 UTC로 바꿔 현지 시각 표시가 어긋난다.
        this.mapper = objectMapper.copy().disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    public Optional<RouteResult> get(RouteProvider provider, RouteQuery q) {
        try {
            String json = redis.opsForValue().get(key(provider, q));
            if (json == null) return Optional.empty();
            Entry e = mapper.readValue(json, Entry.class);
            return fitTo(e.result(), e.queriedAt(), q);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("route cache read failed provider={} cause={}", provider.id(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void put(RouteProvider provider, RouteQuery q, RouteResult result) {
        Duration ttl = switch (result.status()) {
            case FOUND -> provider.settings().cacheTtl();
            case NO_CONNECTION, NOT_COVERED -> provider.settings().noConnectionTtl();
            case UNAVAILABLE -> null;
        };
        if (ttl == null || ttl.isZero() || ttl.isNegative()) return;
        try {
            Entry e = new Entry(q.departAt().toOffsetDateTime(), result.withAttempts(List.of()));
            redis.opsForValue().set(key(provider, q), mapper.writeValueAsString(e), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("route cache write failed provider={} cause={}", provider.id(), e.getClass().getSimpleName());
        }
    }

    static String key(RouteProvider provider, RouteQuery q) {
        long bucketSeconds = Math.max(60, provider.settings().cacheBucket().toSeconds());
        long bucket = q.departAt().toEpochSecond() / bucketSeconds;
        return PREFIX + provider.id() + ":" + q.mode() + ":" + q.origin().lat() + "," + q.origin().lng()
                + ">" + q.destination().lat() + "," + q.destination().lng() + ":" + bucket;
    }

    /**
     * 대중교통 결과는 물어본 시각 뒤의 시간표라 그보다 이른 요청에 쓰면 그 사이에 떠나는 편을 놓친다.
     * 도로 경로는 소요 시간이 출발 시각과 무관하다고 보고 요청 시각으로 옮긴다.
     */
    static Optional<RouteResult> fitTo(RouteResult cached, OffsetDateTime queriedAt, RouteQuery q) {
        if (q.mode() == TravelMode.TRANSIT && q.departAt().toInstant().isBefore(queriedAt.toInstant())) {
            return Optional.empty();
        }
        if (cached.status() != RouteStatus.FOUND) return Optional.of(cached);
        List<RouteItinerary> fitted = cached.itineraries().stream()
                .map(it -> it.usesTransit() ? it : shift(it, q))
                .filter(it -> !it.departure().toInstant().isBefore(q.departAt().toInstant()))
                .toList();
        if (fitted.isEmpty()) return Optional.empty();
        return Optional.of(new RouteResult(RouteStatus.FOUND, fitted, cached.providerId(), cached.reason(), List.of()));
    }

    private static RouteItinerary shift(RouteItinerary it, RouteQuery q) {
        Duration d = Duration.between(it.departure().toInstant(), q.departAt().toInstant());
        if (d.isZero()) return it;
        List<RouteLeg> legs = it.legs().stream().map(l -> l.shifted(l.departure().plus(d), l.arrival().plus(d))).toList();
        return new RouteItinerary(it.departure().plus(d), it.arrival().plus(d), it.durationSeconds(), it.transfers(),
                legs, it.fares(), it.source(), it.endpointGapMeters());
    }
}
