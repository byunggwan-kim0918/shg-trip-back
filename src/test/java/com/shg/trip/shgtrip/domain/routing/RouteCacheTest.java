package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RouteCacheTest {

    private final RouteQuery nine = query(TravelMode.TRANSIT);

    @Test
    void 가장_빨리_닿는_편이_요청_시각_전에_떠났으면_남은_편이_있어도_쓰지_않는다() {
        RouteResult cached = RouteResult.found("otp", List.of(itinerary(LegMode.RAIL, 0), itinerary(LegMode.RAIL, 12)));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(5), nine.mode());

        assertThat(RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), later))
                .as("5분 뒤에 물으면 12분 편보다 빠른 편이 있을 수 있다").isEmpty();
    }

    @Test
    void 가장_빨리_닿는_편이_요청_시각_뒤에_떠나면_그_편을_그대로_쓴다() {
        RouteResult cached = RouteResult.found("otp", List.of(itinerary(LegMode.RAIL, 12), itinerary(LegMode.RAIL, 30)));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(5), nine.mode());

        RouteResult fitted = RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), later).orElseThrow();

        assertThat(fitted.earliestArrival().orElseThrow().departure())
                .isEqualTo(nine.departAt().toOffsetDateTime().plusMinutes(12));
        assertThat(fitted.itineraries()).allSatisfy(it -> assertThat(it.departure()).isAfterOrEqualTo(
                later.departAt().toOffsetDateTime()));
    }

    @Test
    void 끝점을_정류장으로_옮긴_경로는_정류장까지_걷는_시간을_더해도_탈_수_있을_때만_쓴다() {
        RouteResult cached = RouteResult.found("otp", List.of(itinerary(LegMode.RAIL, 12).withEndpointGap(400)));
        RouteQuery fiveLater = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(5),
                nine.mode());
        RouteQuery tenLater = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(10),
                nine.mode());

        assertThat(RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), fiveLater))
                .as("400m(약 5분) 걸어 12분 편에 닿는다").isPresent();
        assertThat(RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), tenLater))
                .as("10분에 떠나면 정류장에 15분쯤 닿아 12분 편을 놓친다").isEmpty();
    }

    @Test
    void 날짜가_바뀐_요청에는_앞_날짜에_물은_시간표를_쓰지_않는다() {
        RouteQuery lateNight = new RouteQuery(nine.origin(), nine.destination(),
                nine.departAt().withHour(23).withMinute(50), nine.mode());
        var dep = lateNight.departAt().toOffsetDateTime().plusMinutes(30);
        RouteLeg leg = new RouteLeg(LegMode.BUS, null, null, nine.origin(), nine.destination(), dep, dep.plusMinutes(20),
                1200, null, null, null, false);
        RouteResult cached = RouteResult.found("otp", List.of(new RouteItinerary(dep, dep.plusMinutes(20), 1200, 0,
                List.of(leg), List.of(), new RouteSource("p", RouteBasis.GTFS_SCHEDULE, true, "t", Instant.EPOCH,
                Instant.EPOCH))));
        RouteQuery afterMidnight = new RouteQuery(nine.origin(), nine.destination(), lateNight.departAt().plusMinutes(15),
                nine.mode());

        assertThat(RouteCache.fitTo(cached, lateNight.departAt().toOffsetDateTime(), afterMidnight)).isEmpty();
    }

    @Test
    void 남는_시간표_경로가_없으면_캐시를_쓰지_않는다() {
        RouteResult cached = RouteResult.found("otp", List.of(itinerary(LegMode.RAIL, 0)));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(5), nine.mode());

        assertThat(RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), later)).isEmpty();
    }

    @Test
    void 시간표가_없는_경로는_요청_시각으로_옮긴다() {
        RouteResult cached = RouteResult.found("kakao", List.of(itinerary(LegMode.CAR, 0)));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(20),
                TravelMode.CAR);

        RouteItinerary it = RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), later).orElseThrow().itineraries().getFirst();

        assertThat(it.departure()).isEqualTo(later.departAt().toOffsetDateTime());
        assertThat(it.legs().getFirst().departure()).isEqualTo(later.departAt().toOffsetDateTime());
        assertThat(it.durationSeconds()).isEqualTo(1800);
    }

    @Test
    void 물어본_시각보다_이른_요청에는_시간표_결과를_쓰지_않는다() {
        RouteResult cached = RouteResult.found("otp", List.of(itinerary(LegMode.RAIL, 9)));
        RouteQuery earlier = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(1), nine.mode());

        assertThat(RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime().plusMinutes(9), earlier)).isEmpty();
        assertThat(RouteCache.fitTo(RouteResult.of(RouteStatus.NO_CONNECTION, "otp", "x"),
                nine.departAt().toOffsetDateTime().plusMinutes(9), earlier)).isEmpty();
    }

    @Test
    void 대중교통_질의는_도보뿐인_결과도_이른_요청에_쓰지_않는다() {
        RouteResult walkOnly = RouteResult.found("otp", List.of(itinerary(LegMode.WALK, 9)));
        RouteQuery earlier = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(2), nine.mode());

        assertThat(RouteCache.fitTo(walkOnly, nine.departAt().toOffsetDateTime().plusMinutes(9), earlier)).isEmpty();
    }

    @Test
    void 도로_질의의_데이터_밖_결과는_이른_요청에도_쓴다() {
        RouteQuery earlierCar = new RouteQuery(nine.origin(), nine.destination(), nine.departAt(), TravelMode.CAR);
        RouteResult gap = RouteResult.of(RouteStatus.NOT_COVERED, "kakao-car", "kakao 103");

        assertThat(RouteCache.fitTo(gap, nine.departAt().toOffsetDateTime().plusMinutes(30), earlierCar)).contains(gap);
    }

    @Test
    void 대중교통_데이터_밖_답도_다른_시각에는_쓰지_않는다() {
        RouteResult borrowed = RouteResult.of(RouteStatus.NOT_COVERED, "otp", "outside service period; borrowed day NO_CONNECTION");
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(20), nine.mode());

        assertThat(RouteCache.fitTo(borrowed, nine.departAt().toOffsetDateTime(), nine)).contains(borrowed);
        assertThat(RouteCache.fitTo(borrowed, nine.departAt().toOffsetDateTime(), later)).isEmpty();
    }

    @Test
    void 대중교통_연결_없음은_물어본_시각에만_쓴다() {
        RouteResult none = RouteResult.of(RouteStatus.NO_CONNECTION, "otp", "NO_TRANSIT_CONNECTION");
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(3), nine.mode());

        assertThat(RouteCache.fitTo(none, nine.departAt().toOffsetDateTime(), nine)).contains(none);
        assertThat(RouteCache.fitTo(none, nine.departAt().toOffsetDateTime(), later))
                .as("물은 시각마다 탐색 범위 끝이 달라 그 뒤 편을 놓칠 수 있다").isEmpty();
    }

    @Test
    void 끝점을_옮긴_내역과_간격을_캐시를_거쳐도_남긴다() {
        RouteResult cached = new RouteResult(RouteStatus.FOUND, List.of(itinerary(LegMode.RAIL, 3).withEndpointGap(1684)),
                "otp", "destination snapped to 新千歳空港 1684m", List.of());

        RouteResult fitted = RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), nine).orElseThrow();

        assertThat(fitted.reason()).isEqualTo("destination snapped to 新千歳空港 1684m");
        assertThat(fitted.itineraries().getFirst().endpointGapMeters()).isEqualTo(1684);
    }

    @Test
    void JSON으로_저장했다가_읽어도_시각_오프셋과_상태가_그대로다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        RouteCache cache = new RouteCache(redis, mapper);
        RouteProvider p = new RouteEngineTest.Fake("otp", 1, null, Set.of(TravelMode.TRANSIT));
        RouteResult original = RouteResult.found("otp", List.of(itinerary(LegMode.RAIL, 3)));

        cache.put(p, nine, original);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(RouteCache.key(p, nine)), json.capture(), eq(Duration.ofHours(1)));
        stored(ops, java.util.Map.of(RouteCache.key(p, nine), json.getValue()));

        RouteResult read = cache.get(p, nine).orElseThrow();
        assertThat(read).isEqualTo(original);
        assertThat(read.itineraries().getFirst().departure().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
    }

    /** 여러 칸을 한 번에 읽는 조회(MGET)에 저장된 값만 돌려준다. */
    private static void stored(ValueOperations<String, String> ops, java.util.Map<String, String> byKey) {
        when(ops.multiGet(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(0);
            return keys.stream().map(byKey::get).collect(java.util.stream.Collectors.toList());
        });
    }

    @Test
    void 다시_맞춰_늦어진_출발은_앞_칸에서_가장_빨리_닿는_편이_그_뒤에_떠날_때만_쓴다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        RouteCache cache = new RouteCache(redis, mapper);
        RouteProvider p = new RouteEngineTest.Fake("otp", 1, null, Set.of(TravelMode.TRANSIT));
        cache.put(p, nine, RouteResult.found("otp", List.of(itinerary(LegMode.BUS, 30), itinerary(LegMode.BUS, 45))));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(RouteCache.key(p, nine)), json.capture(), eq(Duration.ofHours(1)));
        stored(ops, java.util.Map.of(RouteCache.key(p, nine), json.getValue()));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(25), nine.mode());
        RouteQuery tooLate = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(50),
                nine.mode());

        assertThat(RouteCache.key(p, later)).isNotEqualTo(RouteCache.key(p, nine));
        assertThat(cache.get(p, later).orElseThrow().earliestArrival().orElseThrow().departure())
                .isEqualTo(nine.departAt().toOffsetDateTime().plusMinutes(30));
        assertThat(cache.get(p, tooLate)).as("가장 빨리 닿는 편이 이미 떠났으면 다시 묻는다").isEmpty();
    }

    @Test
    void 앞_칸의_연결_없음은_뒤_시각에_쓰지_않는다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        RouteCache cache = new RouteCache(redis, mapper);
        RouteProvider p = new RouteEngineTest.Fake("otp", 1, null, Set.of(TravelMode.TRANSIT));
        cache.put(p, nine, RouteResult.of(RouteStatus.NO_CONNECTION, "otp", "before first train"));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(RouteCache.key(p, nine)), json.capture(), org.mockito.ArgumentMatchers.any(Duration.class));
        stored(ops, java.util.Map.of(RouteCache.key(p, nine), json.getValue()));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(25), nine.mode());

        assertThat(cache.get(p, nine)).get().extracting(RouteResult::status).isEqualTo(RouteStatus.NO_CONNECTION);
        assertThat(cache.get(p, later)).isEmpty();
    }

    @Test
    void 조회_불가는_저장하지_않는다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RouteCache cache = new RouteCache(redis, new ObjectMapper().findAndRegisterModules());
        RouteProvider p = new RouteEngineTest.Fake("otp", 1, null, Set.of(TravelMode.TRANSIT));

        cache.put(p, nine, RouteResult.of(RouteStatus.UNAVAILABLE, "otp", "timeout"));

        verifyNoInteractions(redis);
    }

    @Test
    void 같은_시간_칸의_출발은_같은_키를_쓴다() {
        RouteProvider p = new RouteEngineTest.Fake("otp", 1, null, Set.of(TravelMode.TRANSIT));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(14), nine.mode());
        RouteQuery nextSlot = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(15), nine.mode());

        assertThat(RouteCache.key(p, later)).isEqualTo(RouteCache.key(p, nine));
        assertThat(RouteCache.key(p, nextSlot)).isNotEqualTo(RouteCache.key(p, nine));
    }

    @Test
    void 그래프_판이_바뀌면_같은_구간도_다른_키를_쓴다() {
        RouteProvider fake = new RouteEngineTest.Fake("otp", 1, null, Set.of(TravelMode.TRANSIT));
        RouteProvider before = versioned(fake, "2026-11-20T03:00:00");
        RouteProvider after = versioned(fake, "2026-11-27T18:01:47");

        assertThat(RouteCache.key(before, nine)).isNotEqualTo(RouteCache.key(after, nine));
        assertThat(RouteCache.key(fake, nine)).as("판을 모르는 공급자는 예전 키 그대로").doesNotContain("@");
    }

    private static RouteProvider versioned(RouteProvider p, String version) {
        return new RouteProvider() {
            public String id() { return p.id(); }
            public RoutingProperties.ProviderSettings settings() { return p.settings(); }
            public RouteResult route(RouteQuery q) { return p.route(q); }
            public String dataVersion() { return version; }
        };
    }

    private RouteItinerary itinerary(LegMode mode, int startOffsetMinutes) {
        var dep = nine.departAt().toOffsetDateTime().plusMinutes(startOffsetMinutes);
        RouteLeg leg = new RouteLeg(mode, null, null, nine.origin(), nine.destination(), dep, dep.plusMinutes(30), 1800,
                null, null, null, false);
        return new RouteItinerary(dep, dep.plusMinutes(30), 1800, 0, List.of(leg), List.of(),
                new RouteSource("p", RouteBasis.GTFS_SCHEDULE, true, "t", Instant.EPOCH, Instant.EPOCH));
    }
}
