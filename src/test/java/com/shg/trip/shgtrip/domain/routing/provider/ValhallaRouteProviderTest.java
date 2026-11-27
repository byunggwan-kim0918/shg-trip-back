package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.TaxiFareTable;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValhallaRouteProviderTest {

    /** 세 점(서울역 → 인천항 → 제주항)을 polyline6로 담고, 가운데 구간을 배로 탄다. */
    private static final String FERRY_TRIP = """
            {"trip":{"summary":{"time":36000,"length":470.5},"legs":[{"shape":"%s","maneuvers":[
             {"type":1,"time":3600,"length":40.0,"begin_shape_index":0,"end_shape_index":1},
             {"type":28,"time":30000,"length":430.0,"begin_shape_index":1,"end_shape_index":2},
             {"type":29,"time":2400,"length":0.5,"begin_shape_index":2,"end_shape_index":2}]}]}}""";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RoutingHttp http = mock(RoutingHttp.class);
    private final TaxiFareTable fares = new TaxiFareTable(List.of(new TaxiFareTable.TaxiFare("KR", "서울",
            new GeoPoint(37.566, 126.978), "KRW", 4800, 1600, 131, 100, null)), 40);
    private final ValhallaRouteProvider provider = new ValhallaRouteProvider("valhalla",
            settings("valhalla", Set.of(TravelMode.CAR, TravelMode.TAXI, TravelMode.WALK), 1, null, 0, "Asia/Seoul"),
            http, mapper, Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC), fares);

    private RouteQuery query(TravelMode mode) {
        return new RouteQuery(new GeoPoint(37.5547, 126.9707), new GeoPoint(33.5276, 126.5430),
                ZonedDateTime.of(2026, 11, 26, 9, 0, 0, 0, ZoneId.of("Asia/Seoul")), mode);
    }

    @Test
    void 배_구간을_FERRY로_나누고_좌표를_경로선에서_읽는다() throws IOException {
        String shape = encode(List.of(new GeoPoint(37.5547, 126.9707), new GeoPoint(37.4600, 126.6000),
                new GeoPoint(33.5276, 126.5430)));

        RouteResult r = provider.parse(mapper.readTree(FERRY_TRIP.formatted(shape)), query(TravelMode.CAR));

        RouteItinerary it = r.itineraries().getFirst();
        assertThat(it.legs()).extracting(l -> l.mode()).containsExactly(LegMode.CAR, LegMode.FERRY, LegMode.CAR);
        assertThat(it.legs().get(1).from()).isEqualTo(new GeoPoint(37.46, 126.6));
        assertThat(it.legs().get(1).durationSeconds()).isEqualTo(30000);
        assertThat(it.durationSeconds()).isEqualTo(36000);
        assertThat(it.source().basis()).isEqualTo(RouteBasis.ROAD_NETWORK);
        assertThat(it.fares()).isEmpty();
    }

    @Test
    void 택시는_요금표로_요금을_붙인다() throws IOException {
        String json = "{\"trip\":{\"summary\":{\"time\":1500,\"length\":11.1},\"legs\":[{\"shape\":\"\",\"maneuvers\":[]}]}}";

        RouteResult r = provider.parse(mapper.readTree(json), query(TravelMode.TAXI));

        RouteItinerary it = r.itineraries().getFirst();
        assertThat(it.legs().getFirst().mode()).isEqualTo(LegMode.TAXI);
        assertThat(it.fares()).singleElement().satisfies(f -> assertThat(f.currency()).isEqualTo("KRW"));
    }

    @Test
    void 길이_없으면_데이터_밖이고_이어지지_않으면_연결_없음이다() {
        when(http.postJson(any(), any(), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(400, "{\"error_code\":171,\"error\":\"No suitable edges\"}"));
        assertThat(provider.route(query(TravelMode.CAR)).status()).isEqualTo(RouteStatus.NOT_COVERED);

        when(http.postJson(any(), any(), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(400, "{\"error_code\":442,\"error\":\"No path could be found\"}"));
        assertThat(provider.route(query(TravelMode.CAR)).status()).isEqualTo(RouteStatus.NO_CONNECTION);

        when(http.postJson(any(), any(), anyMap(), any())).thenReturn(new RoutingHttp.Response(500, ""));
        assertThat(provider.route(query(TravelMode.CAR)).status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }

    @Test
    void 출발지_현지_시각과_수단별_costing을_보낸다() {
        var body = provider.requestBody(query(TravelMode.WALK));

        assertThat(body.path("costing").asText()).isEqualTo("pedestrian");
        assertThat(body.path("date_time").path("value").asText()).isEqualTo("2026-11-26T09:00");
    }

    @Test
    void 경로선을_풀어_좌표로_읽는다() {
        List<GeoPoint> points = List.of(new GeoPoint(35.681236, 139.767125), new GeoPoint(35.689487, 139.691711));

        assertThat(ValhallaRouteProvider.decode(encode(points))).isEqualTo(points);
        assertThat(ValhallaRouteProvider.decode("@@@")).isEmpty();
    }

    private static String encode(List<GeoPoint> points) {
        StringBuilder sb = new StringBuilder();
        long lat = 0;
        long lng = 0;
        for (GeoPoint p : points) {
            long la = Math.round(p.lat() * 1e6);
            long ln = Math.round(p.lng() * 1e6);
            write(sb, la - lat);
            write(sb, ln - lng);
            lat = la;
            lng = ln;
        }
        return sb.toString();
    }

    private static void write(StringBuilder sb, long v) {
        long s = v < 0 ? ~(v << 1) : v << 1;
        while (s >= 0x20) {
            sb.append((char) ((0x20 | (s & 0x1f)) + 63));
            s >>= 5;
        }
        sb.append((char) (s + 63));
    }
}
