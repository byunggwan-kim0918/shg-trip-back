package com.shg.trip.shgtrip.domain.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공급자 장애 차단. 연속 실패가 기준을 넘으면 정해진 동안 묻지 않고, 그 뒤 한 번 다시 묻는다.
 * 장애 중인 공급자에 계속 물으면 응답이 늦어지고 유료 공급자는 실패 호출에도 한도를 쓴다.
 */
public class ProviderHealth {

    private record State(int failures, Instant openUntil) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();
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
        State s = states.get(providerId);
        if (s == null || s.openUntil() == null) return true;
        Instant now = clock.instant();
        if (now.isBefore(s.openUntil())) return false;
        return states.replace(providerId, s, new State(s.failures(), now.plus(openFor)));
    }

    /** UNAVAILABLE만 실패로 센다. 연결 없음·데이터 밖은 공급자가 정상으로 답한 것이다. */
    public void record(String providerId, RouteStatus status) {
        if (status != RouteStatus.UNAVAILABLE) {
            states.remove(providerId);
            return;
        }
        states.compute(providerId, (k, s) -> {
            int failures = (s == null ? 0 : s.failures()) + 1;
            return new State(failures, failures >= failureThreshold ? clock.instant().plus(openFor) : null);
        });
    }
}
