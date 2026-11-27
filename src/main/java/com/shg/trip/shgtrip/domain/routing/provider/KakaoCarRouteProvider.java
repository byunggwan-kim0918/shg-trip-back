package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.Fare;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingProperties.ProviderSettings;
import com.shg.trip.shgtrip.domain.routing.TravelMode;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 카카오모빌리티 자동차 길찾기. 택시 요청이면 같은 경로에 카카오가 준 택시 요금을 붙인다. 출발이 충분히 뒤면 그 시각 교통을 반영한다. */
public class KakaoCarRouteProvider extends AbstractRouteProvider {

    /** 이보다 가까운 출발은 지금 교통으로 본다. */
    private static final Duration FUTURE_THRESHOLD = Duration.ofMinutes(30);
    private static final DateTimeFormatter DEPARTURE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** 출발지(102)·도착지(103) 주변에 차가 다닐 길이 없거나 둘이 같은 곳이다(104). 차로 못 간다는 뜻이 아니다. */
    private static final Set<Integer> DATA_GAP_CODES = Set.of(102, 103, 104);

    public KakaoCarRouteProvider(String id, ProviderSettings settings, RoutingHttp http, ObjectMapper mapper,
                                 Clock clock) {
        super(id, settings, http, mapper, clock);
    }

    @Override
    protected RouteResult call(RouteQuery q) throws IOException {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) return unavailable("api key missing");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", String.format(Locale.ROOT, "%.6f,%.6f", q.origin().lng(), q.origin().lat()));
        params.put("destination", String.format(Locale.ROOT, "%.6f,%.6f", q.destination().lng(), q.destination().lat()));
        params.put("priority", "RECOMMEND");
        params.put("summary", "true");
        String path = "/v1/directions";
        if (q.departAt().toInstant().isAfter(clock.instant().plus(FUTURE_THRESHOLD))) {
            path = "/v1/future/directions";
            params.put("departure_time", q.departAt().withZoneSameInstant(zone(q)).format(DEPARTURE));
        }
        RoutingHttp.Response r = http.get(RoutingHttp.uri(settings.baseUrl(), path, params),
                Map.of("Authorization", "KakaoAK " + settings.apiKey()), settings.timeout());
        if (!r.ok()) return unavailable("http " + r.status());
        return parse(json(r.body()), q);
    }

    RouteResult parse(JsonNode root, RouteQuery q) {
        JsonNode route = root.path("routes").path(0);
        if (!route.path("result_code").isNumber()) return unavailable("unreadable response");
        int code = route.path("result_code").asInt();
        if (code != 0) {
            return status(DATA_GAP_CODES.contains(code) ? RouteStatus.NOT_COVERED : RouteStatus.NO_CONNECTION,
                    "kakao " + code);
        }
        JsonNode summary = route.path("summary");
        if (!summary.path("duration").isNumber()) return unavailable("unreadable response");
        int seconds = summary.path("duration").asInt();
        OffsetDateTime departure = q.departAt().toOffsetDateTime();
        OffsetDateTime arrival = departure.plusSeconds(seconds);
        boolean taxi = q.mode() == TravelMode.TAXI;
        RouteLeg leg = new RouteLeg(taxi ? LegMode.TAXI : LegMode.CAR, null, null, q.origin(), q.destination(),
                departure, arrival, seconds, summary.path("distance").asInt(), null, null, false);
        JsonNode fare = summary.path("fare");
        List<Fare> fares = new java.util.ArrayList<>();
        if (!taxi && fare.path("toll").isNumber()) {
            fares.add(new Fare(BigDecimal.valueOf(fare.path("toll").asLong()), "KRW", Fare.Kind.TOLL));
        }
        if (taxi && fare.path("taxi").isNumber()) {
            fares.add(new Fare(BigDecimal.valueOf(fare.path("taxi").asLong()), "KRW", Fare.Kind.TAXI_ESTIMATE));
        }
        return RouteResult.found(id, List.of(new RouteItinerary(departure, arrival, seconds, 0, List.of(leg), fares,
                source(RouteBasis.ROAD_NETWORK))));
    }
}
