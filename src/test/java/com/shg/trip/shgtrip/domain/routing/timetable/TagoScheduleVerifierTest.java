package com.shg.trip.shgtrip.domain.routing.timetable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.ProviderCallBudget;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingProperties;
import com.shg.trip.shgtrip.domain.routing.ScheduleVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TagoScheduleVerifierTest {

    private final RoutingHttp http = mock(RoutingHttp.class);
    private final TagoTrainTimetable trains = mock(TagoTrainTimetable.class);
    private final ProviderCallBudget budget = mock(ProviderCallBudget.class);
    private TagoScheduleVerifier verifier;

    private static final OffsetDateTime DEP = OffsetDateTime.parse("2026-11-27T09:28:00+09:00");

    @BeforeEach
    void setUp() {
        when(budget.tryAcquire(anyString(), anyInt(), anyInt(), anyInt(), any())).thenReturn(true);
        RoutingProperties props = new RoutingProperties(true, 4, Map.of(),
                new RoutingProperties.TagoSettings(true, "https://apis.data.go.kr", "k", Duration.ofSeconds(5), 9000),
                null, null, null, null, false, null, null);
        verifier = new TagoScheduleVerifier(props, trains, http, new ObjectMapper(), budget, Clock.systemUTC());
        when(http.get(argThat((URI u) -> u != null && u.getPath().endsWith("GetCtyCodeList")), anyMap(), any()))
                .thenReturn(ok("{\"citycode\":\"11\",\"cityname\":\"서울\"}"));
        when(http.get(argThat((URI u) -> u != null && u.getPath().endsWith("GetCtyAcctoTrainSttnList")), anyMap(), any()))
                .thenReturn(ok("{\"nodeid\":\"NAT010000\",\"nodename\":\"서울\"},{\"nodeid\":\"NAT014445\",\"nodename\":\"부산\"}"));
        when(http.get(argThat((URI u) -> u != null && u.getPath().endsWith("GetExpBusTrminlList")), anyMap(), any()))
                .thenReturn(ok("{\"terminalId\":\"NAEK010\",\"terminalNm\":\"서울경부\"},{\"terminalId\":\"NAEK700\",\"terminalNm\":\"부산\"}"));
    }

    private static RoutingHttp.Response ok(String items) {
        return new RoutingHttp.Response(200, "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":["
                + items + "]}}}}");
    }

    private static RouteLeg leg(LegMode mode, String from, String to, String line, String operator, int minutes) {
        GeoPoint p = new GeoPoint(37.5, 127.0);
        return new RouteLeg(mode, from, to, p, p, DEP, DEP.plusMinutes(minutes), minutes * 60, null, line, operator, false);
    }

    @Test
    void 정류장_이름의_도시_꼬리표와_역_접미사를_뗀다() {
        assertThat(TagoScheduleVerifier.normalize("서울경부[서울]")).isEqualTo("서울경부");
        assertThat(TagoScheduleVerifier.normalize("부산역")).isEqualTo("부산");
    }

    @Test
    void 코레일_열차는_같은_날_같은_출발_도착_편이_있으면_확정이다() {
        when(trains.trains(eq("NAT010000"), eq("NAT014445"), eq(LocalDate.of(2026, 11, 27)))).thenReturn(
                new TagoTrainTimetable.Result(RouteStatus.FOUND, List.of(new TagoTrainTimetable.ScheduledTrain("00023",
                        "KTX", "서울", "부산", DEP, DEP.plusMinutes(187), BigDecimal.TEN)), null));

        assertThat(verifier.verify(leg(LegMode.RAIL, "서울", "부산", "KTX", "코레일", 187)))
                .isEqualTo(ScheduleVerifier.Verdict.CONFIRMED);
        assertThat(verifier.verify(leg(LegMode.RAIL, "서울", "부산", "KTX", "코레일", 150)))
                .isEqualTo(ScheduleVerifier.Verdict.NOT_IN_OFFICIAL);
    }

    @Test
    void SRT와_역_이름을_못_찾은_구간과_운행_0편은_확인하지_못한_것이다() {
        when(trains.trains(anyString(), anyString(), any())).thenReturn(
                new TagoTrainTimetable.Result(RouteStatus.NO_CONNECTION, List.of(), "tago empty"));

        assertThat(verifier.verify(leg(LegMode.RAIL, "수서", "부산", "[SRT]경부선(하행)", "KTDB", 140)))
                .isEqualTo(ScheduleVerifier.Verdict.UNKNOWN);
        assertThat(verifier.verify(leg(LegMode.RAIL, "없는역", "부산", "KTX", "코레일", 140)))
                .isEqualTo(ScheduleVerifier.Verdict.UNKNOWN);
        assertThat(verifier.verify(leg(LegMode.RAIL, "서울", "부산", "KTX", "코레일", 140)))
                .isEqualTo(ScheduleVerifier.Verdict.UNKNOWN);
    }

    @Test
    void 같은_역_쌍_같은_날_시간표는_한_번만_받고_답을_못_받은_결과는_두지_않는다() {
        when(trains.trains(eq("NAT010000"), eq("NAT014445"), any())).thenReturn(
                new TagoTrainTimetable.Result(RouteStatus.UNAVAILABLE, List.of(), "timeout"),
                new TagoTrainTimetable.Result(RouteStatus.FOUND, List.of(new TagoTrainTimetable.ScheduledTrain("00023",
                        "KTX", "서울", "부산", DEP, DEP.plusMinutes(187), BigDecimal.TEN)), null));
        RouteLeg ktx = leg(LegMode.RAIL, "서울", "부산", "KTX", "코레일", 187);

        assertThat(verifier.verify(ktx)).isEqualTo(ScheduleVerifier.Verdict.UNKNOWN);
        assertThat(verifier.verify(ktx)).isEqualTo(ScheduleVerifier.Verdict.CONFIRMED);
        assertThat(verifier.verify(ktx)).isEqualTo(ScheduleVerifier.Verdict.CONFIRMED);

        org.mockito.Mockito.verify(trains, org.mockito.Mockito.times(2)).trains(eq("NAT010000"), eq("NAT014445"), any());
    }

    @Test
    void 고속버스는_TAGO_출발_시각과_맞으면_확정이다() {
        when(http.get(argThat((URI u) -> u != null && u.getPath().endsWith("GetStrtpntAlocFndExpbusInfo")), anyMap(), any()))
                .thenReturn(ok("{\"depPlandTime\":202611270928,\"arrPlandTime\":202611271328}"));

        assertThat(verifier.verify(leg(LegMode.BUS, "서울경부[서울]", "부산[부산]", "서울경부[서울]-부산[부산]_01", "KTDB", 240)))
                .isEqualTo(ScheduleVerifier.Verdict.CONFIRMED);
    }

    @Test
    void 시내버스는_다루지_않는다() {
        assertThat(verifier.verify(leg(LegMode.BUS, "서울역", "경복궁", "1711", "KTDB", 8)))
                .isEqualTo(ScheduleVerifier.Verdict.UNKNOWN);
    }
}
