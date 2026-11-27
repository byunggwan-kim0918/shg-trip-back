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
    void 요청_시각_전에_떠나는_시간표_경로는_쓰지_않는다() {
        RouteResult cached = RouteResult.found("otp", List.of(itinerary(LegMode.RAIL, 0), itinerary(LegMode.RAIL, 12)));
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(5), nine.mode());

        RouteResult fitted = RouteCache.fitTo(cached, nine.departAt().toOffsetDateTime(), later).orElseThrow();

        assertThat(fitted.itineraries()).hasSize(1);
        assertThat(fitted.itineraries().getFirst().departure()).isEqualTo(nine.departAt().toOffsetDateTime().plusMinutes(12));
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
    void 연결_없음은_물어본_시각_뒤_요청에는_쓴다() {
        RouteResult none = RouteResult.of(RouteStatus.NO_CONNECTION, "otp", "NO_TRANSIT_CONNECTION");
        RouteQuery later = new RouteQuery(nine.origin(), nine.destination(), nine.departAt().plusMinutes(3), nine.mode());

        assertThat(RouteCache.fitTo(none, nine.departAt().toOffsetDateTime(), later)).contains(none);
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
        when(ops.get(RouteCache.key(p, nine))).thenReturn(json.getValue());

        RouteResult read = cache.get(p, nine).orElseThrow();
        assertThat(read).isEqualTo(original);
        assertThat(read.itineraries().getFirst().departure().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
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

    private RouteItinerary itinerary(LegMode mode, int startOffsetMinutes) {
        var dep = nine.departAt().toOffsetDateTime().plusMinutes(startOffsetMinutes);
        RouteLeg leg = new RouteLeg(mode, null, null, nine.origin(), nine.destination(), dep, dep.plusMinutes(30), 1800,
                null, null, null, false);
        return new RouteItinerary(dep, dep.plusMinutes(30), 1800, 0, List.of(leg), List.of(),
                new RouteSource("p", RouteBasis.GTFS_SCHEDULE, true, "t", Instant.EPOCH, Instant.EPOCH));
    }
}
