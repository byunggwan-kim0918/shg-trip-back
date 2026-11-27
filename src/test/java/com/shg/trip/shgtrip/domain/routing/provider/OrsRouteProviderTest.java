package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static com.shg.trip.shgtrip.domain.routing.RoutingTestSettings.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrsRouteProviderTest {

    private final RoutingHttp http = mock(RoutingHttp.class);
    private final OrsRouteProvider ors = new OrsRouteProvider("ors",
            settings("ors", Set.of(TravelMode.WALK, TravelMode.CAR), 1, "k", 0, "Europe/Berlin"), http,
            new ObjectMapper(), Clock.systemUTC());
    private final RouteQuery walk = new RouteQuery(new GeoPoint(48.8584, 2.2945), new GeoPoint(48.8606, 2.3376),
            ZonedDateTime.of(2026, 11, 27, 10, 0, 0, 0, ZoneId.of("Europe/Paris")), TravelMode.WALK);

    @Test
    void 도보_경로를_공통_모델로_바꾼다() {
        when(http.postJson(argThat((URI u) -> u.getPath().endsWith("/foot-walking")), any(), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(200, "{\"routes\":[{\"summary\":{\"distance\":3672.4,\"duration\":2644.2}}]}"));

        var it = ors.route(walk).itineraries().getFirst();

        assertThat(it.durationSeconds()).isEqualTo(2644);
        assertThat(it.legs().getFirst().mode()).isEqualTo(LegMode.WALK);
        assertThat(it.legs().getFirst().distanceMeters()).isEqualTo(3672);
    }

    @Test
    void 길에서_먼_점은_데이터_밖이고_경로_없음은_연결_없음이다() {
        when(http.postJson(any(), any(), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(404, "{\"error\":{\"code\":2010,\"message\":\"x\"}}"));
        assertThat(ors.route(walk).status()).isEqualTo(RouteStatus.NOT_COVERED);

        when(http.postJson(any(), any(), anyMap(), any()))
                .thenReturn(new RoutingHttp.Response(404, "{\"error\":{\"code\":2009,\"message\":\"x\"}}"));
        assertThat(ors.route(walk).status()).isEqualTo(RouteStatus.NO_CONNECTION);

        when(http.postJson(any(), any(), anyMap(), any())).thenReturn(new RoutingHttp.Response(429, "{}"));
        assertThat(ors.route(walk).status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }
}
