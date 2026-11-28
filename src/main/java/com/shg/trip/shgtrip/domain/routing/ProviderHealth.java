package com.shg.trip.shgtrip.domain.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공급자 장애 차단과 상태 기록. 연속 실패가 기준을 넘으면 정해진 동안 묻지 않고, 그 뒤 한 번 다시 묻는다.
 * 장애 중인 공급자에 계속 물으면 응답이 늦어지고 유료 공급자는 실패 호출에도 한도를 쓴다.
 * 인증 실패·호출 제한 응답은 다시 물어도 같은 답이라 한 번에 차단한다.
 */
public class ProviderHealth {

    /** 실패 종류. 인증 실패는 키를 고치기 전에는 풀리지 않는다. */
    public enum FailureKind { AUTH, RATE_LIMITED, TIMEOUT, ERROR }

    /** OK: 마지막 호출 성공, DEGRADED: 연속 실패 중, OPEN: 차단 중, AUTH_FAILED: 마지막 실패가 인증 실패. */
    public enum State { UNKNOWN, OK, DEGRADED, OPEN, AUTH_FAILED }

    private record Breaker(int failures, Instant openUntil) {}

    private record Stats(Instant lastSuccessAt, Instant lastFailureAt, FailureKind lastFailure, String lastFailureReason,
                         long successes, long failures) {
        static final Stats EMPTY = new Stats(null, null, null, null, 0, 0);
    }

    /** 앞 공급자가 답하지 못해 뒤 공급자가 경로를 낸 기록. */
    public record Fallback(String from, String to, String reason, Instant at, long count) {}

    /** 공급자 하나의 지금 상태. */
    public record Snapshot(String providerId, State state, int consecutiveFailures, Instant openUntil,
                           Instant lastSuccessAt, Instant lastFailureAt, FailureKind lastFailure, String lastFailureReason,
                           long successes, long failures, Fallback lastFallback) {}

    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();
    private final Map<String, Fallback> fallbacks = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    public ProviderHealth(int failureThreshold, Duration openFor, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    /** 차단 시간이 지나면 처음 온 요청 하나만 통과시키고, 그 결과가 나올 때까지 나머지는 다시 차단 시간만큼 막는다. */
    public boolean allows(String providerId) {
        Breaker s = breakers.get(providerId);
        if (s == null || s.openUntil() == null) return true;
        Instant now = clock.instant();
        if (now.isBefore(s.openUntil())) return false;
        return breakers.replace(providerId, s, new Breaker(s.failures(), now.plus(openFor)));
    }

    public void record(String providerId, RouteStatus status) {
        record(providerId, status, null);
    }

    /** UNAVAILABLE만 실패로 센다. 연결 없음·데이터 밖은 공급자가 정상으로 답한 것이다. */
    public void record(String providerId, RouteStatus status, String reason) {
        Instant now = clock.instant();
        if (status != RouteStatus.UNAVAILABLE) {
            breakers.remove(providerId);
            stats.merge(providerId, new Stats(now, null, null, null, 1, 0), (old, x) ->
                    new Stats(now, old.lastFailureAt(), old.lastFailure(), old.lastFailureReason(), old.successes() + 1,
                            old.failures()));
            return;
        }
        FailureKind kind = classify(reason);
        boolean immediate = kind == FailureKind.AUTH || kind == FailureKind.RATE_LIMITED;
        breakers.compute(providerId, (k, s) -> {
            int failures = (s == null ? 0 : s.failures()) + 1;
            return new Breaker(failures, immediate || failures >= failureThreshold ? now.plus(openFor) : null);
        });
        String safeReason = reason == null ? null : reason.length() > 120 ? reason.substring(0, 120) : reason;
        stats.merge(providerId, new Stats(null, now, kind, safeReason, 0, 1), (old, x) ->
                new Stats(old.lastSuccessAt(), now, kind, safeReason, old.successes(), old.failures() + 1));
    }

    /**
     * 공급자 응답 사유를 실패 종류로 나눈다. 어댑터는 HTTP 오류를 "http 401"처럼, 통신 실패를 "timeout"처럼 남긴다.
     */
    static FailureKind classify(String reason) {
        if (reason == null) return FailureKind.ERROR;
        if (reason.matches("^http 40[13]\\b.*")) return FailureKind.AUTH;
        if (reason.matches("^http 429\\b.*")) return FailureKind.RATE_LIMITED;
        if (reason.startsWith("timeout") || reason.contains("deadline")) return FailureKind.TIMEOUT;
        return FailureKind.ERROR;
    }

    public void recordFallback(String from, String to, String reason) {
        Instant now = clock.instant();
        fallbacks.merge(to, new Fallback(from, to, reason, now, 1),
                (old, x) -> new Fallback(from, to, reason, now, old.count() + 1));
    }

    public Snapshot snapshot(String providerId) {
        Breaker b = breakers.get(providerId);
        Stats s = stats.getOrDefault(providerId, Stats.EMPTY);
        Instant now = clock.instant();
        boolean open = b != null && b.openUntil() != null && now.isBefore(b.openUntil());
        State state = s.lastSuccessAt() == null && s.lastFailureAt() == null ? State.UNKNOWN
                : b == null ? State.OK
                : s.lastFailure() == FailureKind.AUTH ? State.AUTH_FAILED
                : open ? State.OPEN : State.DEGRADED;
        return new Snapshot(providerId, state, b == null ? 0 : b.failures(), open ? b.openUntil() : null,
                s.lastSuccessAt(), s.lastFailureAt(), s.lastFailure(), s.lastFailureReason(), s.successes(), s.failures(),
                fallbacks.get(providerId));
    }
}
