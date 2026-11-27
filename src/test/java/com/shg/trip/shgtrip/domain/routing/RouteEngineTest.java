package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteEngineTest {

    private RouteCache cache;
    private ProviderCallBudget budget;

    @BeforeEach
    void setUp() {
        cache = mock(RouteCache.class);
        budget = mock(ProviderCallBudget.class);
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        when(budget.tryAcquire(anyString(), anyInt(), anyInt(), anyInt(), any())).thenReturn(true);
    }

    private RouteEngine engine(boolean enabled, RouteProvider... providers) {
        RoutingProperties props = new RoutingProperties(enabled, 4, Map.of(), null, null, null, null, null, false, null, null);
        return new RouteEngine(new RouteProviderRegistry(List.of(providers)), cache, budget, props);
    }

    @Test
    void 우선순위가_높은_공급자가_찾으면_그_결과를_쓴다() {
        Fake first = new Fake("a", 1, found("a"));
        Fake second = new Fake("b", 2, found("b"));

        RouteResult r = engine(true, second, first).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        assertThat(r.providerId()).isEqualTo("a");
        assertThat(second.calls.get()).isZero();
        verify(cache).put(any(), any(), any());
    }

    @Test
    void 앞_공급자가_연결_없음이면_다음_공급자에_묻는다() {
        Fake first = new Fake("a", 1, RouteResult.of(RouteStatus.NO_CONNECTION, "a", "none"));
        Fake second = new Fake("b", 2, found("b"));

        RouteResult r = engine(true, first, second).route(query(TravelMode.TRANSIT));

        assertThat(r.providerId()).isEqualTo("b");
        assertThat(r.attempts()).extracting(ProviderAttempt::status)
                .containsExactly(RouteStatus.NO_CONNECTION, RouteStatus.FOUND);
    }

    @Test
    void 하나라도_답을_못_받으면_연결_없음이라고_단정하지_않는다() {
        Fake first = new Fake("a", 1, RouteResult.of(RouteStatus.NO_CONNECTION, "a", "none"));
        Fake second = new Fake("b", 2, RouteResult.of(RouteStatus.UNAVAILABLE, "b", "timeout"));

        RouteResult r = engine(true, first, second).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }

    @Test
    void 모두_찾아보고_없으면_연결_없음이다() {
        Fake first = new Fake("a", 1, RouteResult.of(RouteStatus.NO_CONNECTION, "a", "none"));
        Fake second = new Fake("b", 2, RouteResult.of(RouteStatus.NOT_COVERED, "b", "outside"));

        RouteResult r = engine(true, first, second).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NO_CONNECTION);
        assertThat(r.providerId()).isEqualTo("a");
    }

    @Test
    void 영역이나_수단을_다루는_공급자가_없으면_데이터_밖이다() {
        Fake carOnly = new Fake("car", 1, found("car"), Set.of(TravelMode.CAR));

        RouteResult r = engine(true, carOnly).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(carOnly.calls.get()).isZero();
    }

    @Test
    void 호출_한도를_넘으면_공급자를_부르지_않고_조회_불가다() {
        Fake p = new Fake("a", 1, found("a"));
        when(budget.tryAcquire(anyString(), anyInt(), anyInt(), anyInt(), any())).thenReturn(false);

        RouteResult r = engine(true, p).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.UNAVAILABLE);
        assertThat(p.calls.get()).isZero();
    }

    @Test
    void 공급자가_예외를_던져도_조회_불가로_바꾼다() {
        RouteProvider broken = new Fake("a", 1, null) {
            @Override
            public RouteResult route(RouteQuery q) {
                throw new IllegalStateException("boom");
            }
        };

        RouteResult r = engine(true, broken).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }

    @Test
    void 캐시에_있으면_공급자를_부르지_않는다() {
        Fake p = new Fake("a", 1, found("a"));
        when(cache.get(any(), any())).thenReturn(Optional.of(found("a")));

        RouteResult r = engine(true, p).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        assertThat(r.attempts().getFirst().fromCache()).isTrue();
        assertThat(p.calls.get()).isZero();
        verify(budget, never()).tryAcquire(anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 조회_전에_좌표를_설정_자릿수로_맞춘다() {
        List<RouteQuery> seen = new ArrayList<>();
        Fake p = new Fake("a", 1, found("a")) {
            @Override
            public RouteResult route(RouteQuery q) {
                seen.add(q);
                return super.route(q);
            }
        };
        RouteQuery q = new RouteQuery(new GeoPoint(43.068712345, 141.350812345), RoutingTestSettings.OTARU,
                query(TravelMode.TRANSIT).departAt(), TravelMode.TRANSIT);

        engine(true, p).route(q);

        assertThat(seen.getFirst().origin()).isEqualTo(new GeoPoint(43.0687, 141.3508));
    }

    @Test
    void 꺼져_있으면_묻지_않는다() {
        Fake p = new Fake("a", 1, found("a"));

        RouteResult r = engine(false, p).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(p.calls.get()).isZero();
    }

    @Test
    void 일일_한도가_있는데_시간대가_없으면_기동하지_않는다() {
        var s = new RoutingProperties.ProviderSettings("kakao-car", true, 1, "https://example.com", "k",
                Set.of(TravelMode.CAR), List.of(RoutingTestSettings.HOKKAIDO), null, null, 100, 0, null, null, null, false,
                "t", null, null, false, null, null, null, 0, null, false, null, null);
        RoutingProperties props = new RoutingProperties(true, 4, Map.of("kakao-car", s), null, null, null, null, null, false, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RouteProviderRegistry(props, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timezone");
    }

    @Test
    void 역_대_역_경로에_지역_접근_도착_구간을_시각_순서대로_붙인다() {
        GeoPoint home = new GeoPoint(43.10, 141.30);
        GeoPoint stationA = new GeoPoint(43.0687, 141.3508);
        GeoPoint stationB = new GeoPoint(43.1977, 140.9937);
        GeoPoint hotel = new GeoPoint(43.20, 141.00);
        var t0 = query(TravelMode.TRANSIT).departAt();
        List<RouteQuery> railCalls = new ArrayList<>();
        RouteProvider rail = new Fake("rail", 30, null) {
            @Override
            public RoutingProperties.ProviderSettings settings() {
                return RoutingTestSettings.stopToStop(5000, Set.of());
            }

            @Override
            public RouteResult route(RouteQuery q) {
                railCalls.add(q);
                var dep = q.departAt().toOffsetDateTime().plusMinutes(5);
                return RouteResult.found("rail", List.of(itinerary("rail", LegMode.RAIL, stationA, stationB, dep, 35)));
            }
        };
        RouteProvider local = new Fake("local", 10, null) {
            @Override
            public RouteResult route(RouteQuery q) {
                var dep = q.departAt().toOffsetDateTime();
                return RouteResult.found("local", List.of(itinerary("local", LegMode.BUS, q.origin(), q.destination(), dep, 20)));
            }
        };

        RouteResult r = engine(true, rail, local).route(new RouteQuery(home, hotel, t0, TravelMode.TRANSIT));

        RouteItinerary it = r.itineraries().getFirst();
        assertThat(it.legs()).extracting(RouteLeg::mode).containsExactly(LegMode.BUS, LegMode.RAIL, LegMode.BUS);
        assertThat(railCalls.getLast().departAt().toOffsetDateTime()).isEqualTo(t0.toOffsetDateTime().plusMinutes(20 + 10));
        assertThat(it.legs().get(1).departure()).isAfterOrEqualTo(it.legs().get(0).arrival().plusMinutes(10));
        assertThat(it.legs().get(2).departure()).isEqualTo(it.legs().get(1).arrival());
        assertThat(it.departure()).isEqualTo(t0.toOffsetDateTime());
        assertThat(it.transfers()).isEqualTo(2);
        assertThat(r.reason()).contains("connected");
        assertThat(it.source().basis()).isEqualTo(RouteBasis.GTFS_SCHEDULE);
    }

    @Test
    void 도보만_있는_접근_구간은_이어_붙인_경로의_시간표_근거를_낮추지_않는다() {
        var dep = query(TravelMode.TRANSIT).departAt().toOffsetDateTime();
        RouteItinerary walk = new RouteItinerary(dep, dep.plusMinutes(5), 300, 0, List.of(new RouteLeg(LegMode.WALK, null,
                null, RoutingTestSettings.SAPPORO, RoutingTestSettings.OTARU, dep, dep.plusMinutes(5), 300, null, null, null,
                false)), List.of(), new RouteSource("local", RouteBasis.ROAD_NETWORK, true, "t", Instant.EPOCH, Instant.EPOCH));
        RouteItinerary train = itinerary("rail", LegMode.RAIL, RoutingTestSettings.SAPPORO, RoutingTestSettings.OTARU,
                dep.plusMinutes(15), 120);

        RouteItinerary joined = RouteEngine.join(walk, train, walk);

        assertThat(joined.source().basis()).isEqualTo(RouteBasis.GTFS_SCHEDULE);
    }

    @Test
    void 접근_구간을_못_구하면_역_대_역_열차를_확정하지_않는다() {
        RouteProvider rail = new Fake("rail", 30, found("rail")) {
            @Override
            public RoutingProperties.ProviderSettings settings() {
                return RoutingTestSettings.stopToStop(5000, Set.of());
            }
        };

        RouteResult r = engine(true, rail).route(new RouteQuery(new GeoPoint(43.10, 141.30),
                new GeoPoint(43.20, 141.00), query(TravelMode.TRANSIT).departAt(), TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.ESTIMATED);
        assertThat(RouteConfidence.of(r)).isEqualTo(RouteConfidence.ESTIMATED);
        assertThat(r.reason()).contains("access leg");
    }

    @Test
    void 접근_구간_뒤에_탈_열차가_없으면_앞서_찾은_열차를_내지_않는다() {
        GeoPoint stationA = new GeoPoint(43.0687, 141.3508);
        GeoPoint stationB = new GeoPoint(43.1977, 140.9937);
        AtomicInteger railCalls = new AtomicInteger();
        RouteProvider rail = new Fake("rail", 30, null) {
            @Override
            public RoutingProperties.ProviderSettings settings() {
                return RoutingTestSettings.stopToStop(5000, Set.of());
            }

            @Override
            public RouteResult route(RouteQuery q) {
                if (railCalls.incrementAndGet() > 1) return RouteResult.of(RouteStatus.NO_CONNECTION, "rail", "last train gone");
                var dep = q.departAt().toOffsetDateTime().plusMinutes(5);
                return RouteResult.found("rail", List.of(itinerary("rail", LegMode.RAIL, stationA, stationB, dep, 35)));
            }
        };
        RouteProvider local = new Fake("local", 10, null) {
            @Override
            public RouteResult route(RouteQuery q) {
                var dep = q.departAt().toOffsetDateTime();
                return RouteResult.found("local", List.of(itinerary("local", LegMode.BUS, q.origin(), q.destination(), dep, 20)));
            }
        };

        RouteResult r = engine(true, rail, local).route(new RouteQuery(new GeoPoint(43.10, 141.30),
                new GeoPoint(43.20, 141.00), query(TravelMode.TRANSIT).departAt(), TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NO_CONNECTION);
        assertThat(r.itineraries()).isEmpty();
        assertThat(r.reason()).contains("no train after access arrival");
    }

    private static RouteItinerary itinerary(String id, LegMode mode, GeoPoint from, GeoPoint to,
                                            java.time.OffsetDateTime dep, int minutes) {
        RouteLeg leg = new RouteLeg(mode, null, null, from, to, dep, dep.plusMinutes(minutes), minutes * 60, null, null,
                null, false);
        return new RouteItinerary(dep, dep.plusMinutes(minutes), minutes * 60, 0, List.of(leg), List.of(),
                new RouteSource(id, RouteBasis.GTFS_SCHEDULE, true, id, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void 연속으로_조회_불가면_잠시_묻지_않는다() {
        Fake p = new Fake("a", 1, RouteResult.of(RouteStatus.UNAVAILABLE, "a", "timeout"));
        RouteEngine e = engine(true, p);

        for (int i = 0; i < 5; i++) e.route(query(TravelMode.TRANSIT));
        RouteResult r = e.route(query(TravelMode.TRANSIT));

        assertThat(p.calls.get()).isEqualTo(5);
        assertThat(r.reason()).isEqualTo("circuit open");
    }

    @Test
    void 일정당_외부_호출_예산을_다_쓰면_외부_공급자를_부르지_않는다() {
        Fake ext = new Fake("ext", 1, found("ext")) {
            @Override
            public RoutingProperties.ProviderSettings settings() {
                return RoutingTestSettings.external(super.settings(), true);
            }
        };
        CallAllowance allowance = CallAllowance.of(1);
        RouteEngine e = engine(true, ext);

        assertThat(e.route(query(TravelMode.TRANSIT), allowance).status()).isEqualTo(RouteStatus.FOUND);
        RouteResult second = e.route(new RouteQuery(RoutingTestSettings.OTARU, RoutingTestSettings.SAPPORO,
                query(TravelMode.TRANSIT).departAt(), TravelMode.TRANSIT), allowance);

        assertThat(second.status()).isEqualTo(RouteStatus.UNAVAILABLE);
        assertThat(second.reason()).isEqualTo("trip call allowance exhausted");
        assertThat(ext.calls.get()).isEqualTo(1);
    }

    @Test
    void 같은_요청이_동시에_오면_공급자를_한_번만_부른다() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Fake slow = new Fake("slow", 1, found("slow")) {
            @Override
            public RouteResult route(RouteQuery q) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return super.route(q);
            }
        };
        RouteEngine e = engine(true, slow);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var a = pool.submit(() -> e.route(query(TravelMode.TRANSIT)));
        entered.await();
        var b = pool.submit(() -> e.route(query(TravelMode.TRANSIT)));
        Thread.sleep(100);
        release.countDown();

        assertThat(a.get().status()).isEqualTo(RouteStatus.FOUND);
        assertThat(b.get().status()).isEqualTo(RouteStatus.FOUND);
        assertThat(slow.calls.get()).isEqualTo(1);
        pool.shutdown();
    }

    private static RouteEngine engineWith(List<ScheduleVerifier> verifiers, RouteCache cache, ProviderCallBudget budget,
                                          RouteProvider... providers) {
        RoutingProperties props = new RoutingProperties(true, 4, Map.of(), null, null, null, null, null, false, null, null);
        return new RouteEngine(new RouteProviderRegistry(List.of(providers)), cache, budget, props,
                new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(
                        io.micrometer.core.instrument.MeterRegistry.class), java.time.Clock.systemUTC(), verifiers);
    }

    private static Fake verified(RouteResult result) {
        return new Fake("otp-kr", 1, result) {
            @Override
            public RoutingProperties.ProviderSettings settings() {
                return RoutingTestSettings.withVerifiers(super.settings(), Set.of("KTDB"), List.of("v"));
            }
        };
    }

    private static RouteResult twoLegs(RouteBasis basis, String firstOperator) {
        var dep = query(TravelMode.TRANSIT).departAt().toOffsetDateTime();
        RouteLeg a = new RouteLeg(LegMode.RAIL, "서울", "대전", RoutingTestSettings.SAPPORO, RoutingTestSettings.OTARU, dep,
                dep.plusMinutes(60), 3600, null, "KTX", firstOperator, false);
        RouteLeg b = new RouteLeg(LegMode.BUS, "대전[대전]", "공주[공주]", RoutingTestSettings.OTARU, RoutingTestSettings.SAPPORO,
                dep.plusMinutes(70), dep.plusMinutes(110), 2400, null, "대전[대전]-공주[공주]_01", "KTDB", false, true);
        return RouteResult.found("otp-kr", List.of(new RouteItinerary(dep, dep.plusMinutes(110), 6600, 1, List.of(a, b),
                List.of(), new RouteSource("otp-kr", basis, false, "t", Instant.EPOCH, Instant.EPOCH))));
    }

    private static ScheduleVerifier verifier(java.util.function.Function<RouteLeg, ScheduleVerifier.Verdict> f) {
        return new ScheduleVerifier() {
            public String id() { return "v"; }
            public Verdict verify(RouteLeg leg) { return f.apply(leg); }
        };
    }

    @Test
    void 모든_대중교통_구간이_공식_시간표와_맞으면_확정_시각으로_올린다() {
        RouteResult r = engineWith(List.of(verifier(l -> ScheduleVerifier.Verdict.CONFIRMED)), cache, budget,
                verified(twoLegs(RouteBasis.UNVERIFIED_SCHEDULE, "코레일"))).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.OFFICIAL_SCHEDULE);
        assertThat(RouteConfidence.of(r)).isEqualTo(RouteConfidence.OFFICIAL_SCHEDULE);
        assertThat(r.reason()).contains("confirmed 2/2");
    }

    @Test
    void 미검증_운영기관_구간을_확인하지_못하면_추정으로_남긴다() {
        RouteResult r = engineWith(List.of(verifier(l -> "KTDB".equals(l.operator())
                        ? ScheduleVerifier.Verdict.UNKNOWN : ScheduleVerifier.Verdict.CONFIRMED)), cache, budget,
                verified(twoLegs(RouteBasis.UNVERIFIED_SCHEDULE, "코레일"))).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.UNVERIFIED_SCHEDULE);
        assertThat(RouteConfidence.of(r)).isEqualTo(RouteConfidence.ESTIMATED);
    }

    @Test
    void 공식_시간표에_없는_편은_사유에_남기고_올리지_않는다() {
        RouteResult r = engineWith(List.of(verifier(l -> ScheduleVerifier.Verdict.NOT_IN_OFFICIAL)), cache, budget,
                verified(twoLegs(RouteBasis.UNVERIFIED_SCHEDULE, "코레일"))).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.UNVERIFIED_SCHEDULE);
        assertThat(r.reason()).contains("not in official timetable");
    }

    @Test
    void 운행일이_있는_시간표라도_공식_시간표에_없는_편이면_확정하지_않는다() {
        RouteResult r = engineWith(List.of(verifier(l -> ScheduleVerifier.Verdict.NOT_IN_OFFICIAL)), cache, budget,
                verified(found("otp-kr"))).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.UNVERIFIED_SCHEDULE);
        assertThat(RouteConfidence.of(r)).isEqualTo(RouteConfidence.ESTIMATED);
    }

    @Test
    void 미검증_표시_구간이_모두_확인되면_나머지_시간표_근거로_올린다() {
        RouteResult r = engineWith(List.of(verifier(l -> l.unverifiedSchedule()
                        ? ScheduleVerifier.Verdict.CONFIRMED : ScheduleVerifier.Verdict.UNKNOWN)), cache, budget,
                verified(twoLegs(RouteBasis.UNVERIFIED_SCHEDULE, "코레일"))).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.GTFS_SCHEDULE);
    }

    @Test
    void 운영기관_이름이_미검증_목록에_없어도_미검증_표시_구간은_올리지_않는다() {
        RouteResult r = engineWith(List.of(verifier(l -> ScheduleVerifier.Verdict.UNKNOWN)), cache, budget,
                verified(twoLegs(RouteBasis.UNVERIFIED_SCHEDULE, "코레일"))).route(query(TravelMode.TRANSIT));
        RouteResult noLegFlag = engineWith(List.of(verifier(l -> ScheduleVerifier.Verdict.UNKNOWN)), cache, budget,
                verified(RouteResult.found("otp-kr", List.of(withBasis(found("otp-kr"), RouteBasis.UNVERIFIED_SCHEDULE)))))
                .route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.UNVERIFIED_SCHEDULE);
        assertThat(noLegFlag.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.UNVERIFIED_SCHEDULE);
    }

    private static RouteItinerary withBasis(RouteResult r, RouteBasis basis) {
        RouteItinerary it = r.itineraries().getFirst();
        RouteSource s = it.source();
        return new RouteItinerary(it.departure(), it.arrival(), it.durationSeconds(), it.transfers(), it.legs(), it.fares(),
                new RouteSource(s.providerId(), basis, false, s.attribution(), s.observedAt(), s.expiresAt()));
    }

    @Test
    void 빌린_시간표로_낸_추정은_공식_시간표와_맞아도_올리지_않는다() {
        RouteResult r = engineWith(List.of(verifier(l -> ScheduleVerifier.Verdict.CONFIRMED)), cache, budget,
                verified(twoLegs(RouteBasis.ESTIMATED, "코레일"))).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.ESTIMATED);
    }

    static RouteResult found(String id) {
        RouteQuery q = query(TravelMode.TRANSIT);
        var dep = q.departAt().toOffsetDateTime();
        RouteLeg leg = new RouteLeg(LegMode.RAIL, "札幌", "小樽", q.origin(), q.destination(), dep, dep.plusMinutes(35),
                2100, null, "エアポート", null, false);
        RouteSource src = new RouteSource(id, RouteBasis.GTFS_SCHEDULE, false, "test", Instant.EPOCH,
                Instant.EPOCH.plus(Duration.ofHours(1)));
        return RouteResult.found(id, List.of(new RouteItinerary(dep, dep.plusMinutes(35), 2100, 0, List.of(leg),
                List.of(), src)));
    }

    static class Fake implements RouteProvider {
        final String id;
        final RoutingProperties.ProviderSettings settings;
        final RouteResult result;
        final AtomicInteger calls = new AtomicInteger();

        Fake(String id, int priority, RouteResult result) {
            this(id, priority, result, Set.of(TravelMode.TRANSIT));
        }

        Fake(String id, int priority, RouteResult result, Set<TravelMode> modes) {
            this.id = id;
            this.settings = RoutingTestSettings.settings("fake", modes, priority, null, 0, null);
            this.result = result;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public RoutingProperties.ProviderSettings settings() {
            return settings;
        }

        @Override
        public RouteResult route(RouteQuery q) {
            calls.incrementAndGet();
            return result;
        }
    }
}
