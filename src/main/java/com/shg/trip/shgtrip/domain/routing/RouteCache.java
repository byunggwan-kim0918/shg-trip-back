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
    /**
     * 요청 시각 칸에 없으면 앞 칸 몇 개도 본다. 일정을 다시 맞추면 출발이 수십 분 늦어지는데, 앞서 물은 시각의 가장 빨리 닿는
     * 편이 요청 시각 뒤에 떠나면 그 시각에 다시 물어도 같은 편이라 그대로 쓸 수 있다({@link #fitTo}).
     */
    static final int LOOKBACK_BUCKETS = 3;
    /** 끝점을 정류장으로 옮긴 거리를 걷는 속도(OTP 기본 보행 속도, m/s). */
    private static final double GAP_WALK_METERS_PER_SECOND = 1.33;

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
        return get(provider, q, provider.dataVersion());
    }

    /** @param version 지금 공급자가 답하는 데이터의 판. 다른 판에서 낸 결과는 꺼내지 않는다 */
    public Optional<RouteResult> get(RouteProvider provider, RouteQuery q, String version) {
        try {
            List<String> keys = new java.util.ArrayList<>();
            for (int back = 0; back <= LOOKBACK_BUCKETS; back++) keys.add(key(provider, q, version, back));
            List<String> found = redis.opsForValue().multiGet(keys);
            if (found == null) return Optional.empty();
            for (int back = 0; back < found.size(); back++) {
                String json = found.get(back);
                if (json == null) continue;
                Entry e = mapper.readValue(json, Entry.class);
                Optional<RouteResult> fitted = fitTo(e.result(), e.queriedAt(), q);
                if (fitted.isPresent()) return fitted;
            }
            return Optional.empty();
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("route cache read failed provider={} cause={}", provider.id(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void put(RouteProvider provider, RouteQuery q, RouteResult result) {
        put(provider, q, result, provider.dataVersion());
    }

    /** @param version 조회를 시작할 때의 데이터 판. 조회 중 그래프가 바뀌어도 옛 결과를 새 판 키로 두지 않는다 */
    public void put(RouteProvider provider, RouteQuery q, RouteResult result, String version) {
        Duration ttl = switch (result.status()) {
            case FOUND -> provider.settings().cacheTtl();
            case NO_CONNECTION, NOT_COVERED -> provider.settings().noConnectionTtl();
            case UNAVAILABLE -> null;
        };
        if (ttl == null || ttl.isZero() || ttl.isNegative()) return;
        try {
            Entry e = new Entry(q.departAt().toOffsetDateTime(), result.withAttempts(List.of()));
            redis.opsForValue().set(key(provider, q, version), mapper.writeValueAsString(e), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("route cache write failed provider={} cause={}", provider.id(), e.getClass().getSimpleName());
        }
    }

    static String key(RouteProvider provider, RouteQuery q) {
        return key(provider, q, provider.dataVersion());
    }

    static String key(RouteProvider provider, RouteQuery q, String version) {
        return key(provider, q, version, 0);
    }

    /** @param back 요청 시각 칸에서 몇 칸 앞인지 */
    private static String key(RouteProvider provider, RouteQuery q, String version, int back) {
        long bucketSeconds = Math.max(60, provider.settings().cacheBucket().toSeconds());
        long bucket = q.departAt().toEpochSecond() / bucketSeconds - back;
        return PREFIX + provider.id() + (version == null ? "" : "@" + version) + ":" + q.mode() + ":"
                + q.origin().lat() + "," + q.origin().lng()
                + ">" + q.destination().lat() + "," + q.destination().lng() + ":" + bucket;
    }

    /**
     * 도로 경로는 소요 시간이 출발 시각과 무관하다고 보고 요청 시각으로 옮긴다. 대중교통은 시간표라 옮기지 않고, 다른 시각에
     * 물은 결과는 {@link #provenFor}일 때만 쓴다.
     */
    static Optional<RouteResult> fitTo(RouteResult cached, OffsetDateTime queriedAt, RouteQuery q) {
        if (q.mode() != TravelMode.TRANSIT) {
            if (cached.status() != RouteStatus.FOUND) return Optional.of(cached);
            return Optional.of(new RouteResult(RouteStatus.FOUND,
                    cached.itineraries().stream().map(it -> it.usesTransit() ? it : shift(it, q)).toList(),
                    cached.providerId(), cached.reason(), List.of()));
        }
        boolean sameTime = q.departAt().toInstant().equals(queriedAt.toInstant());
        if (sameTime) return Optional.of(cached);
        if (q.departAt().toInstant().isBefore(queriedAt.toInstant())) return Optional.empty();
        // 연결 없음·데이터 밖(운행 기간 밖 날짜를 빌린 시각의 답 포함)은 물은 시각에 따라 달라 그 시각에만 쓴다.
        if (cached.status() != RouteStatus.FOUND || !provenFor(cached, queriedAt, q)) return Optional.empty();
        List<RouteItinerary> fitted = cached.itineraries().stream().filter(it -> catchable(it, q)).toList();
        return Optional.of(new RouteResult(RouteStatus.FOUND, fitted, cached.providerId(), cached.reason(), List.of()));
    }

    /**
     * 앞선 시각 t0에 가장 빨리 닿는 편이 요청 시각 t 뒤에 떠나면 그 편은 t에도 탈 수 있고, 가장 빨리 닿는 탐색이라면 t의 답도
     * 같다. OTP는 비용 필터로 거른 몇 개만 주므로 최선이라는 보장은 OTP가 t0에 준 답과 같은 수준이다. 그 편이 t보다 먼저
     * 떠났으면 t의 답을 알 수 없다. 날짜가 바뀌면 운행 달력이 달라 쓰지 않는다.
     */
    static boolean provenFor(RouteResult cached, OffsetDateTime queriedAt, RouteQuery q) {
        java.time.ZoneId zone = q.departAt().getZone();
        if (!queriedAt.atZoneSameInstant(zone).toLocalDate().equals(q.departAt().toLocalDate())) return false;
        RouteItinerary best = cached.earliestArrival().orElse(null);
        return best != null && catchable(best, q);
    }

    /**
     * 요청 시각에 떠나 그 편의 출발에 닿는지. 끝점을 정류장으로 옮긴 경로는 출발이 정류장 시각이라, 옮긴 거리를 걷는 시간을
     * 더해도 늦지 않아야 탈 수 있다. 간격은 출발·도착 끝점 합이라 출발 쪽만큼보다 길게 잡는다.
     */
    private static boolean catchable(RouteItinerary it, RouteQuery q) {
        long walkSeconds = Math.round(it.endpointGapMeters() / GAP_WALK_METERS_PER_SECOND);
        return !it.departure().toInstant().isBefore(q.departAt().toInstant().plusSeconds(walkSeconds));
    }

    private static RouteItinerary shift(RouteItinerary it, RouteQuery q) {
        Duration d = Duration.between(it.departure().toInstant(), q.departAt().toInstant());
        if (d.isZero()) return it;
        List<RouteLeg> legs = it.legs().stream().map(l -> l.shifted(l.departure().plus(d), l.arrival().plus(d))).toList();
        return new RouteItinerary(it.departure().plus(d), it.arrival().plus(d), it.durationSeconds(), it.transfers(),
                legs, it.fares(), it.source(), it.endpointGapMeters());
    }
}
