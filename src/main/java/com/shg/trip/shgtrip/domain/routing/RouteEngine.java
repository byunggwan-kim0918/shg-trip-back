package com.shg.trip.shgtrip.domain.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.shg.trip.shgtrip.global.util.GeoUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 좌표 두 개 사이 실제 경로를 공급자 우선순위대로 묻는다.
 * 실제 경로를 못 받으면 추정하지 않고 상태로 돌려준다. 추정 이동시간은 호출부의 몫이다.
 */
@Slf4j
@Service
public class RouteEngine {

    /** 이만큼 가까우면 접근·도착 구간 없이 같은 곳으로 본다. */
    private static final double SAME_PLACE_METERS = 80;
    /** 접근·열차·도착을 이어 붙이는 도시 간 경로까지 한 번의 조회가 끝나야 하는 시간. 넘으면 남은 공급자는 묻지 않는다. */
    private static final Duration ROUTE_DEADLINE = Duration.ofSeconds(40);

    /** 조회 한 번 동안 공유하는 외부 호출 수와 기한. */
    private record Call(CallAllowance allowance, java.time.Instant deadline) {}

    private final RouteProviderRegistry registry;
    private final RouteCache cache;
    private final ProviderCallBudget budget;
    private final ProviderHealth health;
    private final Clock clock;
    private final MeterRegistry meters;
    private final int coordinateDecimals;
    private final boolean enabled;
    private final Map<String, CompletableFuture<RouteResult>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, ScheduleVerifier> verifiers;

    @Autowired
    public RouteEngine(RouteProviderRegistry registry, RouteCache cache, ProviderCallBudget budget,
                       RoutingProperties properties, ObjectProvider<MeterRegistry> meters, Clock clock,
                       List<ScheduleVerifier> verifiers) {
        this.verifiers = verifiers.stream().collect(java.util.stream.Collectors.toMap(ScheduleVerifier::id, v -> v));
        this.registry = registry;
        this.cache = cache;
        this.budget = budget;
        this.health = new ProviderHealth(properties.breakerFailures(), properties.breakerOpen(), clock);
        this.clock = clock;
        this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
        this.coordinateDecimals = properties.coordinateDecimals();
        this.enabled = properties.enabled();
    }

    RouteEngine(RouteProviderRegistry registry, RouteCache cache, ProviderCallBudget budget,
                RoutingProperties properties) {
        this(registry, cache, budget, properties, new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(MeterRegistry.class), Clock.systemUTC(), List.of());
    }

    public RouteResult route(RouteQuery query) {
        return route(query, CallAllowance.unlimited());
    }

    /**
     * @param allowance 일정 하나에서 쓸 외부 API 호출 수. 다 쓰면 외부 공급자는 UNAVAILABLE이다
     */
    public RouteResult route(RouteQuery query, CallAllowance allowance) {
        if (!enabled) return RouteResult.of(RouteStatus.NOT_COVERED, null, "routing disabled");
        RouteQuery q = query.snapped(coordinateDecimals);
        List<RouteProvider> candidates = registry.providersFor(q);
        if (candidates.isEmpty()) return RouteResult.of(RouteStatus.NOT_COVERED, null, "no provider for area and mode");
        return routeVia(q, candidates, true, new Call(allowance, clock.instant().plus(ROUTE_DEADLINE)));
    }

    private RouteResult routeVia(RouteQuery q, List<RouteProvider> candidates, boolean connectStops, Call c) {
        List<ProviderAttempt> attempts = new ArrayList<>();
        for (RouteProvider p : candidates) {
            Optional<RouteResult> cached = cache.get(p, q);
            boolean fromCache = cached.isPresent();
            RouteResult r = fromCache ? cached.get() : call(p, q, c);
            attempts.add(new ProviderAttempt(p.id(), r.status(), r.reason(), fromCache));
            if (r.status() == RouteStatus.FOUND) {
                fallbackFrom(attempts).ifPresent(a -> fallback(a, p));
                if (connectStops && p.settings().stopToStop() && q.mode() == TravelMode.TRANSIT) {
                    r = connect(q, p, r, c);
                    if (r.status() != RouteStatus.FOUND) return r.withAttempts(attempts);
                }
                return verify(p, r).withAttempts(attempts);
            }
        }
        return summarize(attempts);
    }

    /** 앞 공급자가 답하지 못해(UNAVAILABLE) 뒤 공급자가 답한 경우. 데이터 밖(NOT_COVERED)은 원래 다음 공급자 몫이다. */
    private static Optional<ProviderAttempt> fallbackFrom(List<ProviderAttempt> attempts) {
        return attempts.subList(0, attempts.size() - 1).stream()
                .filter(a -> a.status() == RouteStatus.UNAVAILABLE).findFirst();
    }

    private void fallback(ProviderAttempt from, RouteProvider to) {
        health.recordFallback(from.providerId(), to.id(), from.reason());
        meters.counter("routing.provider.fallback", "from", from.providerId(), "to", to.id()).increment();
    }

    /** 공급자 장애·인증 실패·대체 전환 상태. */
    public ProviderHealth.Snapshot health(String providerId) {
        return health.snapshot(providerId);
    }

    private RouteResult cachedOrCall(RouteProvider p, RouteQuery q, Call c) {
        return cache.get(p, q).orElseGet(() -> call(p, q, c));
    }

    /** 같은 공급자·같은 캐시 칸 요청이 동시에 오면 한 번만 부르고 결과를 나눈다. */
    private RouteResult call(RouteProvider p, RouteQuery q, Call c) {
        if (clock.instant().isAfter(c.deadline())) {
            return RouteResult.of(RouteStatus.UNAVAILABLE, p.id(), "route deadline exceeded");
        }
        if (!health.allows(p.id())) return RouteResult.of(RouteStatus.UNAVAILABLE, p.id(), "circuit open");
        String key = RouteCache.key(p, q) + "@" + q.departAt().toEpochSecond();
        CompletableFuture<RouteResult> mine = new CompletableFuture<>();
        CompletableFuture<RouteResult> running = inFlight.putIfAbsent(key, mine);
        if (running != null) return running.join();
        try {
            RouteResult r = callOnce(p, q, c.allowance());
            mine.complete(r);
            return r;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private RouteResult callOnce(RouteProvider p, RouteQuery q, CallAllowance allowance) {
        RoutingProperties.ProviderSettings s = p.settings();
        if (s.external() && !allowance.take()) {
            return RouteResult.of(RouteStatus.UNAVAILABLE, p.id(), "trip call allowance exhausted");
        }
        if (!budget.tryAcquire(p.id(), s.dailyLimit(), s.perMinuteLimit(), s.monthlyLimit(), s.zone())) {
            return RouteResult.of(RouteStatus.UNAVAILABLE, p.id(), "call budget exhausted");
        }
        long started = System.nanoTime();
        RouteResult r = callSafely(p, q);
        Timer.builder("routing.provider.call").tag("provider", p.id()).tag("status", r.status().name())
                .register(meters).record(Duration.ofNanos(System.nanoTime() - started));
        health.record(p.id(), r.status(), r.reason());
        cache.put(p, q, r);
        return r;
    }

    /**
     * 역→역 그래프 경로에 출발지→첫 역, 마지막 역→목적지 구간을 지역 공급자로 붙인다.
     * 열차는 접근 구간이 역에 닿은 뒤 갈아타기 여유를 두고 다시 고르므로 시각이 이어진다.
     * 접근 구간을 못 구하면 그 열차를 탈 수 있는지 모르므로 추정으로 낮춘다. 도착 구간만 못 구하면 끝점 간격으로 알린다.
     */
    private RouteResult connect(RouteQuery q, RouteProvider rail, RouteResult railResult, Call c) {
        RouteItinerary sample = railResult.earliestArrival().orElseThrow();
        GeoPoint firstStop = sample.legs().getFirst().from();
        GeoPoint lastStop = sample.legs().getLast().to();
        Optional<RouteItinerary> access = local(new RouteQuery(q.origin(), firstStop, q.departAt(), TravelMode.TRANSIT), c);
        if (access.isEmpty()) return estimated(railResult, "access leg to " + sample.legs().getFirst().fromName()
                + " not found; train not checked against arrival at station");
        ZonedDateTime railStart = access.get().arrival().plusMinutes(rail.settings().transferMinutes())
                .atZoneSameInstant(q.departAt().getZone());
        RouteQuery railQuery = new RouteQuery(firstStop, lastStop, railStart, TravelMode.TRANSIT).snapped(coordinateDecimals);
        RouteResult again = cachedOrCall(rail, railQuery, c);
        // 앞서 찾은 열차는 접근 구간 도착 전에 떠나므로 탈 수 없다.
        if (again.status() != RouteStatus.FOUND) {
            return RouteResult.of(again.status(), rail.id(), "no train after access arrival: " + again.reason());
        }
        Optional<RouteItinerary> train = again.earliestArrival();
        Optional<RouteItinerary> egress = local(new RouteQuery(lastStop, q.destination(),
                train.get().arrival().atZoneSameInstant(q.departAt().getZone()), TravelMode.TRANSIT), c);
        if (egress.isEmpty()) {
            return RouteResult.found(rail.id(), List.of(join(access.get(), train.get())
                    .withEndpointGap(meters(lastStop, q.destination()))))
                    .withReason("egress leg from " + train.get().legs().getLast().toName() + " not found");
        }
        return RouteResult.found(rail.id(), List.of(join(access.get(), train.get(), egress.get())))
                .withReason("connected access " + access.get().source().providerId() + " + egress "
                        + egress.get().source().providerId());
    }

    private static RouteResult estimated(RouteResult r, String note) {
        List<RouteItinerary> out = r.itineraries().stream().map(it -> {
            RouteSource s = it.source();
            return new RouteItinerary(it.departure(), it.arrival(), it.durationSeconds(), it.transfers(), it.legs(),
                    it.fares(), new RouteSource(s.providerId(), RouteBasis.ESTIMATED, s.officialData(), s.attribution(),
                    s.observedAt(), s.expiresAt(), s.validity()), it.endpointGapMeters());
        }).toList();
        return new RouteResult(r.status(), out, r.providerId(), note, r.attempts());
    }

    private static int meters(GeoPoint a, GeoPoint b) {
        return (int) Math.round(GeoUtils.haversine(a.lat(), a.lng(), b.lat(), b.lng()) * 1000);
    }

    /** 역→역 공급자를 빼고 대중교통, 안 되면 도보로 잇는다. 이미 역에 있으면 빈 구간이다. */
    private Optional<RouteItinerary> local(RouteQuery q, Call c) {
        if (meters(q.origin(), q.destination()) <= SAME_PLACE_METERS) {
            var at = q.departAt().toOffsetDateTime();
            return Optional.of(new RouteItinerary(at, at, 0, 0, List.of(), List.of(),
                    new RouteSource("none", RouteBasis.ROAD_NETWORK, true, null, null, null)));
        }
        for (TravelMode mode : List.of(TravelMode.TRANSIT, TravelMode.WALK)) {
            RouteQuery m = new RouteQuery(q.origin(), q.destination(), q.departAt(), mode).snapped(coordinateDecimals);
            List<RouteProvider> locals = registry.providersFor(m).stream().filter(p -> !p.settings().stopToStop()).toList();
            if (locals.isEmpty()) continue;
            RouteResult r = routeVia(m, locals, false, c);
            if (r.status() == RouteStatus.FOUND) return r.earliestArrival();
        }
        return Optional.empty();
    }

    static RouteItinerary join(RouteItinerary... parts) {
        List<RouteLeg> legs = new ArrayList<>();
        List<Fare> fares = new ArrayList<>();
        RouteBasis basis = RouteBasis.REALTIME;
        boolean official = true;
        List<String> attribution = new ArrayList<>();
        int gap = 0;
        for (RouteItinerary p : parts) {
            legs.addAll(p.legs());
            fares.addAll(p.fares());
            if (p.legs().isEmpty()) continue;
            // 도보만 있는 접근·도착 구간은 시간표 근거를 낮추지 않는다. 근거는 대중교통 구간이 정한다.
            if (p.usesTransit()) {
                basis = RouteBasis.weakest(basis, p.source().basis());
                official &= p.source().officialData();
            }
            if (p.source().attribution() != null && !attribution.contains(p.source().attribution())) {
                attribution.add(p.source().attribution());
            }
            gap += p.endpointGapMeters();
        }
        if (Arrays.stream(parts).noneMatch(RouteItinerary::usesTransit)) {
            basis = RouteBasis.ROAD_NETWORK;
            official = false;
        }
        RouteItinerary first = parts[0].legs().isEmpty() ? parts[1] : parts[0];
        RouteItinerary last = parts[parts.length - 1].legs().isEmpty() ? parts[parts.length - 2] : parts[parts.length - 1];
        RouteSource s = parts[1].source();
        var departure = first.departure();
        var arrival = last.arrival();
        return new RouteItinerary(departure, arrival, (int) Duration.between(departure, arrival).toSeconds(),
                RouteItinerary.transfersOf(legs), legs, fares,
                new RouteSource(s.providerId(), basis, official, String.join("; ", attribution), s.observedAt(),
                        s.expiresAt(), s.validity()), gap);
    }

    /**
     * 공급자에 붙은 검증기로 대중교통 구간을 공식 시간표와 대조한다. 모든 대중교통 구간이 공식 시각과 맞으면
     * OFFICIAL_SCHEDULE, 공식 시간표에 없는 편이나 확인되지 않은 미검증 시간표 구간이 있으면 UNVERIFIED_SCHEDULE이다.
     * 시간표 기간 밖에서 빌린 시간표(ESTIMATED)는 공식 시간표와 맞아도 그날 운행 확인이 아니라 올리지 않는다.
     */
    RouteResult verify(RouteProvider p, RouteResult r) {
        List<ScheduleVerifier> vs = p.settings().verifiers().stream().map(verifiers::get)
                .filter(java.util.Objects::nonNull).toList();
        if (vs.isEmpty() || r.status() != RouteStatus.FOUND) return r;
        List<RouteItinerary> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        RouteItinerary earliest = r.earliestArrival().orElseThrow();
        for (RouteItinerary it : r.itineraries()) {
            List<String> itNotes = it == earliest ? notes : new ArrayList<>();
            if (it.source().basis() == RouteBasis.ESTIMATED || !it.usesTransit()) {
                out.add(it);
                continue;
            }
            int transit = 0;
            int confirmed = 0;
            boolean rejected = false;
            boolean unverifiedLeft = false;
            for (RouteLeg leg : it.legs()) {
                if (!leg.mode().isTransit()) continue;
                transit++;
                ScheduleVerifier.Verdict v = vs.stream().map(x -> x.verify(leg))
                        .filter(x -> x != ScheduleVerifier.Verdict.UNKNOWN).findFirst()
                        .orElse(ScheduleVerifier.Verdict.UNKNOWN);
                if (v == ScheduleVerifier.Verdict.CONFIRMED) confirmed++;
                if (v == ScheduleVerifier.Verdict.NOT_IN_OFFICIAL) {
                    rejected = true;
                    itNotes.add("not in official timetable: " + leg.line());
                }
                if (v != ScheduleVerifier.Verdict.CONFIRMED && leg.unverifiedSchedule()) unverifiedLeft = true;
            }
            // 구간 표시 없이 미검증인 근거는 어디서 왔는지 몰라 올리지 않는다.
            boolean unverifiedLegsConfirmed = !unverifiedLeft && it.legs().stream().anyMatch(RouteLeg::unverifiedSchedule);
            RouteBasis basis = confirmed == transit ? RouteBasis.OFFICIAL_SCHEDULE
                    : rejected || unverifiedLeft ? RouteBasis.UNVERIFIED_SCHEDULE
                    : it.source().basis() == RouteBasis.UNVERIFIED_SCHEDULE && unverifiedLegsConfirmed
                    ? RouteBasis.GTFS_SCHEDULE : it.source().basis();
            if (confirmed > 0) itNotes.add("official timetable confirmed " + confirmed + "/" + transit + " transit legs");
            RouteSource s = it.source();
            out.add(new RouteItinerary(it.departure(), it.arrival(), it.durationSeconds(), it.transfers(), it.legs(),
                    it.fares(), new RouteSource(s.providerId(), basis, s.officialData() || confirmed == transit,
                    s.attribution(), s.observedAt(), s.expiresAt(), s.validity()), it.endpointGapMeters()));
        }
        String reason = java.util.stream.Stream.concat(java.util.stream.Stream.ofNullable(r.reason()),
                notes.stream().distinct()).collect(java.util.stream.Collectors.joining("; "));
        return new RouteResult(r.status(), out, r.providerId(), reason.isEmpty() ? null : reason, r.attempts());
    }

    private static RouteResult callSafely(RouteProvider p, RouteQuery q) {
        try {
            return p.route(q);
        } catch (RuntimeException e) {
            log.warn("route provider failed provider={} cause={}", p.id(), e.getClass().getSimpleName());
            return RouteResult.of(RouteStatus.UNAVAILABLE, p.id(), "provider error " + e.getClass().getSimpleName());
        }
    }

    /**
     * 한 곳이라도 답을 못 받았으면 이동 불가능이라고 단정하지 않는다.
     * 모든 공급자가 데이터를 갖고 찾아봤는데 없을 때만 NO_CONNECTION이다.
     */
    static RouteResult summarize(List<ProviderAttempt> attempts) {
        RouteStatus status;
        if (attempts.stream().anyMatch(a -> a.status() == RouteStatus.UNAVAILABLE)) {
            status = RouteStatus.UNAVAILABLE;
        } else if (attempts.stream().anyMatch(a -> a.status() == RouteStatus.NO_CONNECTION)) {
            status = RouteStatus.NO_CONNECTION;
        } else {
            status = RouteStatus.NOT_COVERED;
        }
        String providerId = attempts.stream().filter(a -> a.status() == status).map(ProviderAttempt::providerId)
                .findFirst().orElse(null);
        String reason = attempts.stream().filter(a -> a.status() == status).map(ProviderAttempt::reason)
                .findFirst().orElse(null);
        return new RouteResult(status, List.of(), providerId, reason, attempts);
    }
}
