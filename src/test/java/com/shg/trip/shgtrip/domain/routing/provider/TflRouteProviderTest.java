package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.Fare;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
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

class TflRouteProviderTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RoutingHttp http = mock(RoutingHttp.class);
    private final TflRouteProvider provider = new TflRouteProvider("tfl",
            settings("tfl", Set.of(TravelMode.TRANSIT), 1, null, 0, "Europe/London"), http, mapper,
            Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));
    private final RouteQuery q = new RouteQuery(new GeoPoint(51.5033, -0.1195), new GeoPoint(51.5308, -0.1238),
            ZonedDateTime.of(2026, 11, 26, 18, 0, 0, 0, ZoneId.of("Asia/Seoul")), TravelMode.TRANSIT);

    @Test
    void 런던_현지_시각과_파운드_요금으로_바꾼다() throws IOException {
        RouteResult r = provider.parse(mapper.readTree(OtpRouteProviderTest.fixture("tfl-waterloo-kingscross.json")), q);

        RouteItinerary it = r.itineraries().getFirst();
        assertThat(it.departure().toString()).isEqualTo("2026-11-26T09:01Z");
        assertThat(it.legs()).extracting(l -> l.mode())
                .containsExactly(LegMode.WALK, LegMode.SUBWAY, LegMode.SUBWAY, LegMode.WALK);
        assertThat(it.transfers()).isEqualTo(1);
        assertThat(it.fares()).containsExactly(new Fare(new BigDecimal("3.1"), "GBP", Fare.Kind.TICKET));
        assertThat(it.source().basis()).isEqualTo(RouteBasis.OFFICIAL_SCHEDULE);
    }

    @Test
    void 요청_시각을_런던_시각으로_보낸다() {
        when(http.get(argThat((URI u) -> u.getQuery().contains("date=20261126") && u.getQuery().contains("time=0900")),
                anyMap(), any())).thenReturn(new RoutingHttp.Response(404, "{}"));

        assertThat(provider.route(q).status()).isEqualTo(RouteStatus.NO_CONNECTION);
    }

    @Test
    void 좌표를_장소로_못_정하면_데이터_밖이다() {
        when(http.get(any(), anyMap(), any())).thenReturn(new RoutingHttp.Response(300, "{}"));

        assertThat(provider.route(q).status()).isEqualTo(RouteStatus.NOT_COVERED);
    }

    @Test
    void 한도_초과는_조회_불가다() {
        when(http.get(any(), anyMap(), any())).thenReturn(new RoutingHttp.Response(429, "{}"));

        assertThat(provider.route(q).status()).isEqualTo(RouteStatus.UNAVAILABLE);
    }
}
