package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.Fare;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KakaoCarRouteProviderTest {

    private static final String FOUND = """
            {"routes":[{"result_code":0,"result_msg":"길찾기 성공","summary":{"fare":{"taxi":16600,"toll":0},
             "distance":11142,"duration":2552}}]}""";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RoutingHttp http = mock(RoutingHttp.class);
    private final Instant now = Instant.parse("2026-11-26T00:00:00Z");

    private KakaoCarRouteProvider provider(String key) {
        return new KakaoCarRouteProvider("kakao-car", settings("kakao-car", Set.of(TravelMode.CAR), 1, key, 0,
                "Asia/Seoul"), http, mapper, Clock.fixed(now, ZoneOffset.UTC));
    }

    private RouteQuery at(ZonedDateTime t) {
        return new RouteQuery(new GeoPoint(37.5547, 126.9707), new GeoPoint(37.4979, 127.0276), t, TravelMode.CAR);
    }

    @Test
    void 택시_요청은_택시_구간과_카카오_택시요금만_담는다() throws IOException {
        RouteQuery q = new RouteQuery(new GeoPoint(37.5547, 126.9707), new GeoPoint(37.4979, 127.0276),
                now.atZone(ZoneId.of("Asia/Seoul")), TravelMode.TAXI);

        RouteItinerary it = provider("k").parse(mapper.readTree(FOUND), q).itineraries().getFirst();

        assertThat(it.legs().getFirst().mode()).isEqualTo(com.shg.trip.shgtrip.domain.routing.LegMode.TAXI);
        assertThat(it.fares()).containsExactly(new Fare(BigDecimal.valueOf(16600), "KRW", Fare.Kind.TAXI_ESTIMATE));
    }

    @Test
    void 자동차_요청은_소요_시간_거리_통행료를_담는다() throws IOException {
        RouteQuery q = at(now.atZone(ZoneId.of("Asia/Seoul")));

        RouteResult r = provider("k").parse(mapper.readTree(FOUND), q);

        RouteItinerary it = r.itineraries().getFirst();
        assertThat(it.durationSeconds()).isEqualTo(2552);
        assertThat(it.arrival()).isEqualTo(q.departAt().toOffsetDateTime().plusSeconds(2552));
        assertThat(it.legs().getFirst().distanceMeters()).isEqualTo(11142);
        assertThat(it.fares()).containsExactly(new Fare(BigDecimal.ZERO, "KRW", Fare.Kind.TOLL));
        assertThat(it.source().basis()).isEqualTo(RouteBasis.ROAD_NETWORK);
    }

    @Test
    void 끝점_주변에_길이_없으면_데이터_밖이고_길찾기_실패는_연결_없음이다() throws IOException {
        RouteQuery q = at(now.atZone(ZoneId.of("Asia/Seoul")));

        assertThat(provider("k").parse(mapper.readTree("{\"routes\":[{\"result_code\":102}]}"), q).status())
                .isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(provider("k").parse(mapper.readTree("{\"routes\":[{\"result_code\":1}]}"), q).status())
                .isEqualTo(RouteStatus.NO_CONNECTION);
    }

    @Test
    void 도착지_주변에_길이_없으면_데이터_밖이고_형식이_다른_응답은_조회_불가다() throws IOException {
        RouteQuery q = at(now.atZone(ZoneId.of("Asia/Seoul")));

        assertThat(provider("k").parse(mapper.readTree("{\"routes\":[{\"result_code\":103}]}"), q).status())
                .isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(provider("k").parse(mapper.readTree("{\"routes\":[{\"result_code\":104}]}"), q).status())
                .isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(provider("k").parse(mapper.readTree("{\"msg\":\"x\"}"), q).status())
                .isEqualTo(RouteStatus.UNAVAILABLE);
        assertThat(provider("k").parse(mapper.readTree("{\"routes\":[{\"result_code\":0,\"summary\":{}}]}"), q)
                .status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }

    @Test
    void 나중_출발은_그_시각_교통으로_묻는다() {
        when(http.get(argThat((URI u) -> u.getPath().equals("/v1/future/directions")
                && u.getQuery().contains("departure_time=202611270900")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, FOUND));

        RouteResult r = provider("k").route(at(ZonedDateTime.of(2026, 11, 27, 9, 0, 0, 0, ZoneId.of("Asia/Seoul"))));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
    }

    @Test
    void 키가_없으면_부르지_않고_조회_불가다() {
        assertThat(provider("").route(at(now.atZone(ZoneId.of("Asia/Seoul")))).status())
                .isEqualTo(RouteStatus.UNAVAILABLE);
    }
}
