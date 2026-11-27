package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * openrouteservice(HeiGIT) 도보·자전거·자동차 경로. 자체 Valhalla 타일이 없는 지역의 도로 경로를 맡는다.
 * 교통 상황을 반영하지 않아 자동차 시간은 도로 제한 속도 기준이다.
 */
public class OrsRouteProvider extends AbstractRouteProvider {

    static final String PATH = "/openrouteservice/v2/directions/";
    /** 좌표 근처에 경로를 낼 길이 없다. */
    private static final int UNROUTABLE_POINT = 2010;
    /** 두 점 사이에 경로가 없다. */
    private static final int ROUTE_NOT_FOUND = 2009;
    /** 요청 거리가 공급자 한도를 넘는다. 이동 불가능이 아니라 공급자 제한이다. */
    private static final int DISTANCE_LIMIT = 2004;

    public OrsRouteProvider(String id, ProviderSettings settings, RoutingHttp http, ObjectMapper mapper, Clock clock) {
        super(id, settings, http, mapper, clock);
    }

    @Override
    protected RouteResult call(RouteQuery q) throws IOException {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) return unavailable("api key missing");
        ObjectNode body = mapper.createObjectNode();
        body.putArray("coordinates")
                .add(mapper.createArrayNode().add(q.origin().lng()).add(q.origin().lat()))
                .add(mapper.createArrayNode().add(q.destination().lng()).add(q.destination().lat()));
        RoutingHttp.Response r = http.postJson(RoutingHttp.uri(settings.baseUrl(), PATH + profile(q.mode()), Map.of()),
                mapper.writeValueAsString(body), Map.of("Authorization", settings.apiKey()), settings.timeout());
        JsonNode json = r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : json(r.body());
        if (r.ok()) return parse(json, q);
        int code = json.path("error").path("code").asInt(-1);
        if (code == UNROUTABLE_POINT || code == DISTANCE_LIMIT) return status(RouteStatus.NOT_COVERED, "ors " + code);
        if (code == ROUTE_NOT_FOUND) return status(RouteStatus.NO_CONNECTION, "ors " + code);
        return unavailable("http " + r.status() + (code < 0 ? "" : " ors " + code));
    }

    static String profile(TravelMode mode) {
        return switch (mode) {
            case WALK -> "foot-walking";
            case BICYCLE -> "cycling-regular";
            case CAR, TAXI -> "driving-car";
            case TRANSIT -> throw new IllegalArgumentException("openrouteservice는 대중교통 시간표를 다루지 않는다");
        };
    }

    RouteResult parse(JsonNode root, RouteQuery q) {
        JsonNode summary = root.path("routes").path(0).path("summary");
        if (!summary.path("duration").isNumber()) return unavailable("unreadable response");
        int seconds = (int) Math.round(summary.path("duration").asDouble());
        int meters = (int) Math.round(summary.path("distance").asDouble());
        OffsetDateTime departure = q.departAt().toOffsetDateTime();
        LegMode mode = switch (q.mode()) {
            case WALK -> LegMode.WALK;
            case BICYCLE -> LegMode.BICYCLE;
            case TAXI -> LegMode.TAXI;
            default -> LegMode.CAR;
        };
        RouteLeg leg = new RouteLeg(mode, null, null, q.origin(), q.destination(), departure,
                departure.plusSeconds(seconds), seconds, meters, null, null, false);
        return RouteResult.found(id, List.of(new RouteItinerary(departure, departure.plusSeconds(seconds), seconds, 0,
                List.of(leg), List.of(), source(RouteBasis.ROAD_NETWORK))));
    }
}
