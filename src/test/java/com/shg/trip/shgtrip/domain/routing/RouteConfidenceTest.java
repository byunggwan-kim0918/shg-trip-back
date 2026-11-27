package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.query;
import static org.assertj.core.api.Assertions.assertThat;

class RouteConfidenceTest {

    private static RouteResult with(RouteBasis basis, boolean official) {
        var dep = query(TravelMode.TRANSIT).departAt().toOffsetDateTime();
        RouteLeg leg = new RouteLeg(LegMode.RAIL, null, null, RoutingTestSettings.SAPPORO, RoutingTestSettings.OTARU, dep,
                dep.plusMinutes(35), 2100, null, null, null, false);
        return RouteResult.found("p", List.of(new RouteItinerary(dep, dep.plusMinutes(35), 2100, 0, List.of(leg),
                List.of(), new RouteSource("p", basis, official, "t", Instant.EPOCH, Instant.EPOCH))));
    }

    @Test
    void 검증하지_못한_시간표와_도로_시간은_추정이다() {
        assertThat(RouteConfidence.of(with(RouteBasis.UNVERIFIED_SCHEDULE, false))).isEqualTo(RouteConfidence.ESTIMATED);
        assertThat(RouteConfidence.of(with(RouteBasis.ROAD_NETWORK, false))).isEqualTo(RouteConfidence.ESTIMATED);
    }

    @Test
    void 시간표_근거는_그대로_올린다() {
        assertThat(RouteConfidence.of(with(RouteBasis.GTFS_SCHEDULE, false))).isEqualTo(RouteConfidence.GTFS_SCHEDULE);
        assertThat(RouteConfidence.of(with(RouteBasis.OFFICIAL_SCHEDULE, true))).isEqualTo(RouteConfidence.OFFICIAL_SCHEDULE);
        assertThat(RouteConfidence.of(with(RouteBasis.REALTIME, true))).isEqualTo(RouteConfidence.OFFICIAL_SCHEDULE);
    }

    @Test
    void 경로가_없으면_원인과_상관없이_확인된_경로_없음이다() {
        for (RouteStatus s : List.of(RouteStatus.NO_CONNECTION, RouteStatus.NOT_COVERED, RouteStatus.UNAVAILABLE)) {
            assertThat(RouteConfidence.of(RouteResult.of(s, "p", "x"))).isEqualTo(RouteConfidence.UNAVAILABLE);
        }
    }
}
