package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingTestSettings;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.query;
import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.settings;
import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.stopToStop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OtpRouteProviderTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RoutingHttp http = mock(RoutingHttp.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);

    private OtpRouteProvider provider(int snapMeters) {
        return new OtpRouteProvider("otp-hokkaido",
                settings("otp", Set.of(TravelMode.TRANSIT), 1, null, snapMeters, "Asia/Tokyo"), http, mapper, clock);
    }

    @Test
    void 시간표_경로를_공통_모델로_바꾼다() throws IOException {
        RouteResult r = provider(0).parse(mapper.readTree(fixture("otp-sapporo-otaru.json")), query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        RouteItinerary it = r.itineraries().getFirst();
        assertThat(it.legs()).extracting(l -> l.mode()).containsExactly(LegMode.WALK, LegMode.RAIL, LegMode.WALK);
        assertThat(it.legs().get(1).line()).isEqualTo("エアポート27号");
        assertThat(it.source().basis()).isEqualTo(RouteBasis.GTFS_SCHEDULE);
        assertThat(it.source().officialData()).isFalse();
        assertThat(it.departure().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(it.transfers()).isZero();
    }

    @Test
    void 끝점에_정류장이_없으면_데이터_밖이다() throws IOException {
        RouteResult r = provider(0).parse(mapper.readTree(noItinerary("NO_STOPS_IN_RANGE", "TO")),
                query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(r.reason()).isEqualTo("NO_STOPS_IN_RANGE@TO");
    }

    @Test
    void 이어지는_운행이_없으면_연결_없음이다() throws IOException {
        RouteResult r = provider(0).parse(mapper.readTree(noItinerary("NO_TRANSIT_CONNECTION", "DATE_TIME")),
                query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NO_CONNECTION);
    }

    @Test
    void GraphQL_오류는_조회_불가다() throws IOException {
        RouteResult r = provider(0).parse(mapper.readTree("{\"errors\":[{\"message\":\"bad\"}]}"),
                query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }

    @Test
    void 대중교통_요청에서_대중교통보다_늦게_닿는_도보_경로는_뺀다() throws IOException {
        String walkThenRail = """
                {"data":{"planConnection":{"routingErrors":[],"edges":[
                 {"node":{"start":"2026-11-26T09:00:00+09:00","end":"2026-11-26T15:00:00+09:00","duration":21600,
                  "numberOfTransfers":0,"legs":[{"mode":"WALK","duration":21600,"distance":25000,"realTime":false,
                  "transitLeg":false,"start":{"scheduledTime":"2026-11-26T09:00:00+09:00"},
                  "end":{"scheduledTime":"2026-11-26T15:00:00+09:00"},"from":{"name":"a","lat":43.0,"lon":141.0},
                  "to":{"name":"b","lat":43.1,"lon":141.1},"route":null}]}}]}}}""";
        String rail = new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8);
        String merged = rail.replace("\"edges\": [", "\"edges\": [" + edgeOf(walkThenRail) + ",");

        RouteResult r = provider(0).parse(mapper.readTree(merged), query(TravelMode.TRANSIT));

        assertThat(r.itineraries()).allMatch(RouteItinerary::usesTransit);
    }

    @Test
    void 대중교통보다_먼저_닿는_도보_경로는_남긴다() throws IOException {
        String shortWalk = """
                {"data":{"planConnection":{"routingErrors":[],"edges":[
                 {"node":{"start":"2026-11-26T09:00:00+09:00","end":"2026-11-26T09:20:00+09:00","duration":1200,
                  "numberOfTransfers":0,"legs":[{"mode":"WALK","duration":1200,"distance":1500,"realTime":false,
                  "transitLeg":false,"start":{"scheduledTime":"2026-11-26T09:00:00+09:00"},
                  "end":{"scheduledTime":"2026-11-26T09:20:00+09:00"},"from":{"name":"a","lat":43.0,"lon":141.0},
                  "to":{"name":"b","lat":43.1,"lon":141.1},"route":null}]}}]}}}""";
        String rail = new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8);
        String merged = rail.replace("\"edges\": [", "\"edges\": [" + edgeOf(shortWalk) + ",");

        RouteResult r = provider(0).parse(mapper.readTree(merged), query(TravelMode.TRANSIT));

        assertThat(r.itineraries()).anyMatch(it -> !it.usesTransit());
    }

    @Test
    void 길과_끊긴_끝점은_가까운_정류장으로_옮겨_다시_묻고_옮긴_내역을_남긴다() throws IOException {
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("141.002")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, noItinerary("NO_STOPS_IN_RANGE", "TO")));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200,
                        "{\"data\":{\"stopsByBbox\":[{\"name\":\"小樽\",\"lat\":43.1975759,\"lon\":140.9933746}]}}"));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("140.9933746")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));

        RouteResult r = provider(2000).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        assertThat(r.reason()).startsWith("destination snapped to 小樽");
        assertThat(r.itineraries()).allMatch(it -> it.endpointGapMeters() > 0);
    }

    @Test
    void 끝점을_옮긴_뒤_걷기만_나오면_목적지에_닿은_것이_아니라_데이터_밖이다() throws IOException {
        String walkOnly = """
                {"data":{"planConnection":{"routingErrors":[],"edges":[
                 {"node":{"start":"2026-11-26T09:00:00+09:00","end":"2026-11-26T09:03:00+09:00","duration":180,
                  "numberOfTransfers":0,"legs":[{"mode":"WALK","duration":180,"distance":200,"realTime":false,
                  "transitLeg":false,"start":{"scheduledTime":"2026-11-26T09:00:00+09:00"},
                  "end":{"scheduledTime":"2026-11-26T09:03:00+09:00"},"from":{"name":"a","lat":43.0,"lon":141.0},
                  "to":{"name":"b","lat":43.1,"lon":141.1},"route":null}]}}]}}}""";
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("141.002")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, noItinerary("NO_STOPS_IN_RANGE", "TO")));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200,
                        "{\"data\":{\"stopsByBbox\":[{\"gtfsId\":\"1:X\",\"name\":\"対岸\",\"lat\":43.1975759,\"lon\":140.9933746,\"vehicleMode\":\"BUS\"}]}}"));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("140.9933746")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, walkOnly));

        RouteResult r = provider(2000).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(r.reason()).contains("walking only");
    }

    @Test
    void 대중교통_요청에_먼_도보만_나오면_수단별_정류장끼리_다시_묻는다() throws IOException {
        String longWalk = """
                {"data":{"planConnection":{"routingErrors":[],"edges":[
                 {"node":{"start":"2026-11-26T09:00:00+09:00","end":"2026-11-26T11:30:00+09:00","duration":9000,
                  "numberOfTransfers":0,"legs":[{"mode":"WALK","duration":9000,"distance":11000,"realTime":false,
                  "transitLeg":false,"start":{"scheduledTime":"2026-11-26T09:00:00+09:00"},
                  "end":{"scheduledTime":"2026-11-26T11:30:00+09:00"},"from":{"name":"a","lat":43.0,"lon":141.0},
                  "to":{"name":"b","lat":43.1,"lon":141.1},"route":null}]}}]}}}""";
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("coordinate")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200, longWalk));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, "{\"data\":{\"stopsByBbox\":[{\"gtfsId\":\"15:A\","
                        + "\"name\":\"那覇空港\",\"lat\":43.0687,\"lon\":141.3508,\"vehicleMode\":\"RAIL\"},"
                        + "{\"gtfsId\":\"15:B\",\"name\":\"首里\",\"lat\":43.199,\"lon\":141.002,\"vehicleMode\":\"RAIL\"}]}}"));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopLocationId")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));

        RouteResult r = provider(2000).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries()).allMatch(RouteItinerary::usesTransit);
        assertThat(r.reason()).contains("(RAIL)");
    }

    private void busThenLongWalk(String end) {
        String json = """
                {"data":{"planConnection":{"routingErrors":[],"edges":[
                 {"node":{"start":"2026-11-26T09:00:00+09:00","end":"%s","duration":3600,
                  "numberOfTransfers":0,"legs":[
                  {"mode":"BUS","duration":600,"distance":5000,"realTime":false,"transitLeg":true,
                   "start":{"scheduledTime":"2026-11-26T09:00:00+09:00"},"end":{"scheduledTime":"2026-11-26T09:10:00+09:00"},
                   "from":{"name":"a","lat":43.0,"lon":141.0},"to":{"name":"b","lat":43.1,"lon":141.1},
                   "route":{"gtfsId":"1:R","shortName":"1","agency":{"name":"bus"}}},
                  {"mode":"WALK","duration":2400,"distance":3000,"realTime":false,"transitLeg":false,
                   "start":{"scheduledTime":"2026-11-26T09:10:00+09:00"},"end":{"scheduledTime":"%s"},
                   "from":{"name":"b","lat":43.1,"lon":141.1},"to":{"name":"c","lat":43.2,"lon":141.0},"route":null}]}}]}}}"""
                .formatted(end, end);
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("coordinate")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200, json));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, "{\"data\":{\"stopsByBbox\":[{\"gtfsId\":\"1:A\","
                        + "\"name\":\"札幌\",\"lat\":43.0687,\"lon\":141.3508,\"vehicleMode\":\"RAIL\"},"
                        + "{\"gtfsId\":\"1:B\",\"name\":\"空港\",\"lat\":43.199,\"lon\":141.002,\"vehicleMode\":\"RAIL\"}]}}"));
        try {
            when(http.postJson(any(), argThat(b -> b != null && b.contains("stopLocationId")), anyMap(), any()))
                    .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 끝_도보가_길어_늦게_닿으면_정류장끼리_다시_물어_이른_쪽을_고른다() {
        busThenLongWalk("2026-11-26T13:00:00+09:00");

        RouteResult r = provider(2000).route(query(TravelMode.TRANSIT));

        assertThat(r.reason()).contains("(RAIL)");
        assertThat(r.itineraries().getFirst().legs()).anyMatch(l -> l.mode() == LegMode.RAIL);
    }

    @Test
    void 끝_도보가_길어도_정류장_경로보다_먼저_닿으면_그대로_쓴다() {
        busThenLongWalk("2026-11-26T09:50:00+09:00");

        RouteResult r = provider(2000).route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().legs()).extracting(l -> l.mode()).containsExactly(LegMode.BUS, LegMode.WALK);
    }

    @Test
    void 출발지가_길과_끊기면_출발지를_옮긴다() throws IOException {
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("141.3508")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200, noItinerary("LOCATION_NOT_FOUND", "FROM")));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200,
                        "{\"data\":{\"stopsByBbox\":[{\"name\":\"札幌\",\"lat\":43.0686365,\"lon\":141.3509218}]}}"));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("141.3509218")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200,
                new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));

        RouteResult r = provider(2000).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        assertThat(r.reason()).startsWith("origin snapped to 札幌");
        assertThat(r.itineraries()).allMatch(it -> it.endpointGapMeters() > 0);
    }

    @Test
    void 오류가_여럿이면_데이터_공백을_먼저_본다() throws IOException {
        String json = "{\"data\":{\"planConnection\":{\"routingErrors\":["
                + "{\"code\":\"NO_TRANSIT_CONNECTION\",\"inputField\":\"DATE_TIME\"},"
                + "{\"code\":\"LOCATION_NOT_FOUND\",\"inputField\":\"TO\"}],\"edges\":[]}}}";

        RouteResult r = provider(0).parse(mapper.readTree(json), query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(r.reason()).isEqualTo("LOCATION_NOT_FOUND@TO");
    }

    @Test
    void 옮기기를_끄면_데이터_밖으로_남긴다() {
        when(http.postJson(any(), any(), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, noItinerary("NO_STOPS_IN_RANGE", "TO")));

        assertThat(provider(0).route(query(TravelMode.TRANSIT)).status()).isEqualTo(RouteStatus.NOT_COVERED);
    }

    @Test
    void 서버_오류와_통신_실패는_조회_불가다() {
        when(http.postJson(any(), any(), anyMap(), any())).thenReturn(new RoutingHttp.Response(503, ""));
        assertThat(provider(0).route(query(TravelMode.TRANSIT)).status()).isEqualTo(RouteStatus.UNAVAILABLE);

        when(http.postJson(any(), any(), anyMap(), any())).thenThrow(new RoutingHttp.CallFailed("timeout"));
        RouteResult r = provider(0).route(query(TravelMode.TRANSIT));
        assertThat(r.status()).isEqualTo(RouteStatus.UNAVAILABLE);
        assertThat(r.reason()).isEqualTo("timeout");
    }

    @Test
    void 요청_수단을_OTP_수단으로_보낸다() {
        assertThat(provider(0).requestBody(query(TravelMode.CAR)).path("variables").path("modes").toString())
                .isEqualTo("{\"directOnly\":true,\"direct\":[\"CAR\"]}");
        assertThat(provider(0).requestBody(query(TravelMode.TRANSIT)).path("variables").path("dt").asText())
                .isEqualTo("2026-11-26T09:00+09:00");
    }

    @Test
    void 역_대_역_그래프는_양_끝을_고른_차량_종류의_가까운_역으로_묻고_간격을_남긴다() throws IOException {
        OtpRouteProvider rail = new OtpRouteProvider("jp-rail", stopToStop(5000, Set.of("RAIL")), http, mapper, clock);
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox") && b.contains("141.28")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200, """
                {"data":{"stopsByBbox":[
                 {"gtfsId":"1:8403","name":"さっぽろ","lat":43.0688,"lon":141.3507,"vehicleMode":"SUBWAY"},
                 {"gtfsId":"1:245","name":"札幌","lat":43.0686,"lon":141.3509,"vehicleMode":"RAIL"}]}}"""));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox") && b.contains("140.94")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200,
                "{\"data\":{\"stopsByBbox\":[{\"gtfsId\":\"1:236\",\"name\":\"小樽\",\"lat\":43.1976,\"lon\":140.9934,\"vehicleMode\":\"RAIL\"}]}}"));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("\"stopLocationId\":\"1:245\"")
                && b.contains("\"stopLocationId\":\"1:236\"")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));

        RouteResult r = rail.route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        assertThat(r.reason()).isEqualTo("stops 札幌 → 小樽");
        assertThat(r.itineraries()).allMatch(it -> it.endpointGapMeters() > 0);
    }

    @Test
    void 역_대_역_그래프에서_가까운_역이_없으면_데이터_밖이다() {
        OtpRouteProvider rail = new OtpRouteProvider("jp-rail", stopToStop(5000, Set.of()), http, mapper, clock);
        when(http.postJson(any(), any(), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, "{\"data\":{\"stopsByBbox\":[]}}"));

        assertThat(rail.route(query(TravelMode.TRANSIT)).status()).isEqualTo(RouteStatus.NOT_COVERED);
    }

    @Test
    void 검증하지_못한_운영기관_구간이_든_경로는_확정_시간표로_보지_않는다() throws IOException {
        OtpRouteProvider kr = new OtpRouteProvider("otp-kr", RoutingTestSettings.withUnverified(
                settings("otp", Set.of(TravelMode.TRANSIT), 1, null, 0, "Asia/Tokyo"), Set.of("JR")), http, mapper, clock);
        String json = new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8);

        RouteResult r = kr.parse(mapper.readTree(json), query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().legs().get(1).operator()).isEqualTo("JR");
        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.UNVERIFIED_SCHEDULE);
    }

    @Test
    void 달력을_믿을_수_없는_피드_구간이_든_경로는_확정_시간표로_보지_않는다() throws IOException {
        OtpRouteProvider sg = new OtpRouteProvider("otp", RoutingTestSettings.withUnverifiedFeeds(
                settings("otp", Set.of(TravelMode.TRANSIT), 1, null, 0, "Asia/Tokyo"), Set.of("jp-rail")), http, mapper, clock);
        String json = new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)
                .replace("\"shortName\": \"エアポート27号\"", "\"gtfsId\": \"jp-rail:R1\", \"shortName\": \"エアポート27号\"");

        RouteResult r = sg.parse(mapper.readTree(json), query(TravelMode.TRANSIT));

        assertThat(r.itineraries().getFirst().source().basis()).isEqualTo(RouteBasis.UNVERIFIED_SCHEDULE);
    }

    @Test
    void 좌표로_연결이_없으면_가까운_정류장끼리_다시_묻는다() throws IOException {
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("coordinate")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200, noItinerary("NO_TRANSIT_CONNECTION", "")));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, "{\"data\":{\"stopsByBbox\":[{\"gtfsId\":\"1:GMP\","
                        + "\"name\":\"김포\",\"lat\":43.0687,\"lon\":141.3508,\"vehicleMode\":\"AIRPLANE\"},"
                        + "{\"gtfsId\":\"1:CJU\",\"name\":\"제주\",\"lat\":43.199,\"lon\":141.002,\"vehicleMode\":\"AIRPLANE\"}]}}"));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopLocationId")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));

        RouteResult r = provider(2000).route(query(TravelMode.TRANSIT));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        assertThat(r.reason()).startsWith("stops ");
    }

    private void serviceRange(String start, String end) {
        when(http.postJson(any(), argThat(b -> b != null && b.contains("serviceTimeRange")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, "{\"data\":{\"serviceTimeRange\":{\"start\":"
                        + Instant.parse(start).getEpochSecond() + ",\"end\":" + Instant.parse(end).getEpochSecond() + "}}}"));
    }

    @Test
    void 운행_기간_밖_날짜는_기간_안_같은_요일_시간표로_소요_시간만_내고_추정으로_표시한다() throws IOException {
        serviceRange("2026-11-19T00:00:00Z", "2027-02-28T15:00:00Z");
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("2027-02-26")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200,
                new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));
        RouteQuery july = new RouteQuery(RoutingTestSettings.SAPPORO, RoutingTestSettings.OTARU,
                java.time.ZonedDateTime.of(2027, 7, 2, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo")), TravelMode.TRANSIT);

        RouteResult r = provider(0).route(july);

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        RouteItinerary it = r.itineraries().getFirst();
        assertThat(it.source().basis()).isEqualTo(RouteBasis.ESTIMATED);
        assertThat(it.source().validity().requestedDateCovered()).isFalse();
        assertThat(it.source().validity().timetableDate()).isEqualTo(java.time.LocalDate.of(2027, 2, 26));
        assertThat(it.departure().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 26).plusDays(
                java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(2027, 2, 26), java.time.LocalDate.of(2027, 7, 2))));
        assertThat(it.legs()).noneMatch(l -> l.realtime());
        assertThat(r.reason()).startsWith("projected from 2027-02-26");
        assertThat(com.shg.trip.shgtrip.domain.routing.RouteConfidence.of(r))
                .isEqualTo(com.shg.trip.shgtrip.domain.routing.RouteConfidence.ESTIMATED);
    }

    @Test
    void 운행_기간_안이면_시간표_근거와_기간을_그대로_싣는다() throws IOException {
        serviceRange("2026-11-19T00:00:00Z", "2027-02-28T15:00:00Z");
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));

        RouteItinerary it = provider(0).route(query(TravelMode.TRANSIT)).itineraries().getFirst();

        assertThat(it.source().basis()).isEqualTo(RouteBasis.GTFS_SCHEDULE);
        assertThat(it.source().validity().requestedDateCovered()).isTrue();
        assertThat(it.source().validity().until()).isEqualTo(Instant.parse("2027-02-28T15:00:00Z"));
    }

    @Test
    void 근처_피드가_그날_운행_기간_밖이면_그_지역_경로만_미검증으로_낮춘다(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws IOException {
        serviceRange("2026-11-19T00:00:00Z", "2027-02-28T15:00:00Z");
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));
        java.nio.file.Files.writeString(dir.resolve("feeds-coverage.json"), """
                {"feeds": [
                  {"feed": "otaru-bus", "modes": ["BUS"], "bbox": [43.15, 140.95, 43.25, 141.05], "periods": [["20261101", "20261125"]]},
                  {"feed": "far-bus", "modes": ["BUS"], "bbox": [35.0, 135.0, 35.1, 135.1], "periods": [["20261101", "20261110"]]}]}""");
        OtpRouteProvider p = new OtpRouteProvider("otp-hokkaido", RoutingTestSettings.withFeedCoverage(
                settings("otp", Set.of(TravelMode.TRANSIT), 1, null, 0, "Asia/Tokyo"), "feeds-coverage.json"),
                http, mapper, clock, dir.toString());

        RouteResult r = p.route(query(TravelMode.TRANSIT));

        assertThat(r.itineraries()).allMatch(it -> it.source().basis() == RouteBasis.UNVERIFIED_SCHEDULE);
        assertThat(r.reason()).contains("otaru-bus [BUS] ended 2026-11-25").doesNotContain("far-bus");
    }

    @Test
    void 근처_피드가_그날_운행하면_시간표_근거를_그대로_둔다(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws IOException {
        serviceRange("2026-11-19T00:00:00Z", "2027-02-28T15:00:00Z");
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));
        java.nio.file.Files.writeString(dir.resolve("feeds-coverage.json"), """
                {"feeds": [{"feed": "otaru-bus", "modes": ["BUS"], "bbox": [43.15, 140.95, 43.25, 141.05],
                            "periods": [["20261101", "20261130"], ["20261201", "20261230"]]}]}""");
        OtpRouteProvider p = new OtpRouteProvider("otp-hokkaido", RoutingTestSettings.withFeedCoverage(
                settings("otp", Set.of(TravelMode.TRANSIT), 1, null, 0, "Asia/Tokyo"),
                dir.resolve("feeds-coverage.json").toString()), http, mapper, clock, null);

        assertThat(p.route(query(TravelMode.TRANSIT)).itineraries().getFirst().source().basis())
                .isEqualTo(RouteBasis.GTFS_SCHEDULE);
    }

    @Test
    void 기간이_일주일보다_짧아_같은_요일이_없으면_데이터_밖이다() {
        serviceRange("2026-11-25T00:00:00Z", "2026-11-28T00:00:00Z");
        RouteQuery later = new RouteQuery(RoutingTestSettings.SAPPORO, RoutingTestSettings.OTARU,
                java.time.ZonedDateTime.of(2027, 1, 11, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo")), TravelMode.TRANSIT);

        assertThat(provider(0).route(later).status()).isEqualTo(RouteStatus.NOT_COVERED);
    }

    @Test
    void 빌린_날에_연결이_없으면_요청_날짜는_이동_불가가_아니라_데이터_밖이다() {
        serviceRange("2026-11-19T00:00:00Z", "2027-02-28T15:00:00Z");
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, noItinerary("NO_TRANSIT_CONNECTION", "")));
        RouteQuery july = new RouteQuery(RoutingTestSettings.SAPPORO, RoutingTestSettings.OTARU,
                java.time.ZonedDateTime.of(2027, 7, 2, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo")), TravelMode.TRANSIT);

        RouteResult r = provider(0).route(july);

        assertThat(r.status()).isEqualTo(RouteStatus.NOT_COVERED);
        assertThat(r.reason()).contains("borrowed 2027-02-26");
    }

    @Test
    void 빌린_시간표를_옮길_때_일광절약시간을_건너도_현지_시각을_지킨다() {
        java.time.ZoneId berlin = java.time.ZoneId.of("Europe/Berlin");
        java.time.OffsetDateTime summer = java.time.OffsetDateTime.parse("2026-08-28T09:00:00+02:00");

        java.time.OffsetDateTime winter = OtpRouteProvider.plusLocalDays(summer, 98, berlin);

        assertThat(winter).isEqualTo(java.time.OffsetDateTime.parse("2026-12-04T09:00:00+01:00"));
    }

    @Test
    void 정류장_조회가_실패하면_데이터_밖이_아니라_조회_불가다() {
        OtpRouteProvider rail = new OtpRouteProvider("jp-rail", stopToStop(5000, Set.of()), http, mapper, clock);
        when(http.postJson(any(), any(), anyMap(), any())).thenReturn(new RoutingHttp.Response(500, ""));

        assertThat(rail.route(query(TravelMode.TRANSIT)).status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }

    @Test
    void 출발지를_정류장으로_옮기면_걸어간_뒤_떠나는_편을_묻는다() throws IOException {
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("141.3508")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200, noItinerary("LOCATION_NOT_FOUND", "FROM")));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("stopsByBbox")), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200,
                        "{\"data\":{\"stopsByBbox\":[{\"name\":\"札幌\",\"lat\":43.0606,\"lon\":141.3509}]}}"));
        when(http.postJson(any(), argThat(b -> b != null && b.contains("planConnection") && b.contains("141.3509}")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(200,
                new String(fixture("otp-sapporo-otaru.json"), StandardCharsets.UTF_8)));

        provider(2000).route(query(TravelMode.TRANSIT));

        var bodies = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.atLeastOnce()).postJson(any(), bodies.capture(), anyMap(), any());
        String snapped = bodies.getAllValues().stream().filter(b -> b.contains("141.3509}")).findFirst().orElseThrow();
        java.time.OffsetDateTime dt = java.time.OffsetDateTime.parse(mapper.readTree(snapped).path("variables").path("dt").asText());
        assertThat(dt).isAfter(query(TravelMode.TRANSIT).departAt().toOffsetDateTime().plusMinutes(5));
    }

    private static String noItinerary(String code, String field) {
        return "{\"data\":{\"planConnection\":{\"routingErrors\":[{\"code\":\"" + code
                + "\",\"description\":\"x\",\"inputField\":\"" + field + "\"}],\"edges\":[]}}}";
    }

    private static String edgeOf(String planJson) {
        int start = planJson.indexOf("{\"node\"");
        int end = planJson.lastIndexOf("]}}}");
        return planJson.substring(start, end);
    }

    static byte[] fixture(String name) throws IOException {
        try (var in = Objects.requireNonNull(OtpRouteProviderTest.class.getResourceAsStream("/routing/" + name))) {
            return in.readAllBytes();
        }
    }
}
