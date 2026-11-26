package com.shg.trip.shgtrip.domain.place.enrich;

import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 승격 장소 즉시 보강 대기열(place_enrich_requests). 한 요청은 한 작업자만 맡고, 맡은 작업자가 죽으면 맡은 시한이 지나 다른
 * 작업자가 다시 맡는다. 요청은 보강되거나 보강할 필요가 없어질 때만 지운다.
 */
@Component
@RequiredArgsConstructor
public class PlaceEnrichRequests {

    private static final int MAX_ERROR_CHARS = 200;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 보강이 필요한 활성 장소만 대기열에 넣는다. 조건은 배치 보강 대상(ENRICH_TARGETS)에서 Wikidata 매칭 대기만 뺀 것이다.
     *
     * @return 새로 넣었으면 true. 이미 있거나 보강이 필요 없으면 false
     */
    public boolean add(long placeId) {
        return jdbc.update("""
                INSERT INTO place_enrich_requests (place_id)
                SELECT id FROM places
                 WHERE id = :id AND active AND enriched_at IS NULL AND enrich_attempts < :maxAttempts
                   AND (place_role IS NULL OR place_role <> 'NOT_VISITABLE')
                ON CONFLICT (place_id) DO NOTHING
                """, new MapSqlParameterSource("id", placeId)
                .addValue("maxAttempts", PlaceRepository.MAX_ENRICH_ATTEMPTS)) > 0;
    }

    /**
     * 요청을 맡고 다음 차례를 backoff만큼 미룬다. 맡은 작업자가 끝내지 못하고 죽어도 같은 요청이 곧바로 다시 맡겨지지 않는다.
     *
     * @param backoff n번째 차례 뒤 기다릴 시간. 횟수가 목록보다 많으면 마지막 값을 쓴다
     * @return 맡은 차례의 시도 횟수(1부터). 다른 작업자가 맡았거나 아직 때가 아니거나 요청이 없으면 비어 있다
     */
    public Optional<Integer> claim(long placeId, Duration lease, List<Duration> backoff) {
        String delays = backoff.stream().map(d -> String.valueOf(d.toSeconds()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        List<Integer> attempts = jdbc.queryForList("""
                UPDATE place_enrich_requests
                   SET claimed_until = now() + make_interval(secs => :lease), attempts = attempts + 1,
                       next_attempt_at = now() + make_interval(secs => (CAST(:delays AS int[]))[least(attempts + 1, :steps)])
                 WHERE place_id = :id AND next_attempt_at <= now()
                   AND (claimed_until IS NULL OR claimed_until < now())
                RETURNING attempts
                """, new MapSqlParameterSource("id", placeId).addValue("lease", lease.toSeconds())
                .addValue("delays", delays).addValue("steps", backoff.size()), Integer.class);
        return attempts.stream().findFirst();
    }

    /** 맡을 수 있는 요청. 오래 기다린 것부터다. */
    public List<Long> due(int limit) {
        return jdbc.queryForList("""
                SELECT place_id FROM place_enrich_requests
                 WHERE next_attempt_at <= now() AND (claimed_until IS NULL OR claimed_until < now())
                 ORDER BY next_attempt_at, place_id
                 LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), Long.class);
    }

    public void remove(long placeId) {
        jdbc.update("DELETE FROM place_enrich_requests WHERE place_id = :id", new MapSqlParameterSource("id", placeId));
    }

    /** 실패한 차례를 놓는다. 다음 차례는 맡을 때 미룬 시각에 온다. */
    public void release(long placeId, String error) {
        jdbc.update("""
                UPDATE place_enrich_requests SET claimed_until = NULL, last_error = :error WHERE place_id = :id
                """, new MapSqlParameterSource("id", placeId).addValue("error", truncate(error)));
    }

    /** 호출 상한으로 미룬다. 시작하지 못한 차례라 시도 횟수에서 뺀다. */
    public void postpone(long placeId, Duration delay) {
        jdbc.update("""
                UPDATE place_enrich_requests
                   SET claimed_until = NULL, attempts = greatest(attempts - 1, 0),
                       next_attempt_at = now() + make_interval(secs => :delay), last_error = 'call budget exhausted'
                 WHERE place_id = :id
                """, new MapSqlParameterSource("id", placeId).addValue("delay", delay.toSeconds()));
    }

    /** 지금 맡고 있거나 곧 맡을 장소. 화면은 이 장소의 소개 자리에 준비 중 표시를 한다. */
    public List<Long> pendingAmong(Collection<Long> placeIds, Duration within) {
        if (placeIds == null || placeIds.isEmpty()) return List.of();
        return jdbc.queryForList("""
                SELECT place_id FROM place_enrich_requests
                 WHERE place_id IN (:ids)
                   AND (claimed_until > now() OR next_attempt_at <= now() + make_interval(secs => :within))
                """, new MapSqlParameterSource("ids", placeIds).addValue("within", within.toSeconds()), Long.class);
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS);
    }
}
