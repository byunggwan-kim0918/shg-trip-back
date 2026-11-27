package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shg.trip.shgtrip.domain.routing.Fare;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingProperties.ProviderSettings;
import com.shg.trip.shgtrip.domain.routing.TaxiFareTable;
import com.shg.trip.shgtrip.domain.routing.TravelMode;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valhalla(OSM 도로·보행망) 도보·자전거·자동차·택시 경로. 타일만 바꾸면 지역에서 전 세계로 넓어진다.
 * 차 경로가 배를 타면 그 구간을 FERRY로 나눈다.
 */
public class ValhallaRouteProvider extends AbstractRouteProvider {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    /** 출발 시각 기준 경로(Valhalla date_time type 1). 시각은 출발지 현지 시각으로 넘긴다. */
    private static final int DEPART_AT = 1;
    /** Valhalla 안내 종류 중 배 타기·내리기. */
    private static final int FERRY_ENTER = 28;
    private static final int FERRY_EXIT = 29;
    private static final double METERS_PER_KM = 1000.0;
    /** 출발지·도착지 근처에 쓸 수 있는 길이 없다. 지도 데이터 공백이지 이동 불가능이 아니다. */
    private static final Set<Integer> DATA_GAP_ERRORS = Set.of(171);
    /** 길은 있는데 이어지지 않는다(다리·배 없는 섬 등). */
    private static final Set<Integer> NO_PATH_ERRORS = Set.of(170, 442);

    private final TaxiFareTable taxiFares;

    public ValhallaRouteProvider(String id, ProviderSettings settings, RoutingHttp http, ObjectMapper mapper,
                                 Clock clock, TaxiFareTable taxiFares) {
        super(id, settings, http, mapper, clock);
        this.taxiFares = taxiFares;
    }

    @Override
    protected RouteResult call(RouteQuery q) throws IOException {
        RoutingHttp.Response r = http.postJson(RoutingHttp.uri(settings.baseUrl(), "/route", Map.of()),
                mapper.writeValueAsString(requestBody(q)), Map.of(), settings.timeout());
        JsonNode body = r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : json(r.body());
        if (r.ok()) return parse(body, q);
        int code = body.path("error_code").asInt(-1);
        if (DATA_GAP_ERRORS.contains(code)) return status(RouteStatus.NOT_COVERED, "valhalla " + code);
        if (NO_PATH_ERRORS.contains(code)) return status(RouteStatus.NO_CONNECTION, "valhalla " + code);
        return unavailable("http " + r.status() + (code < 0 ? "" : " valhalla " + code));
    }

    ObjectNode requestBody(RouteQuery q) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode locations = body.putArray("locations");
        locations.addObject().put("lat", q.origin().lat()).put("lon", q.origin().lng());
        locations.addObject().put("lat", q.destination().lat()).put("lon", q.destination().lng());
        body.put("costing", costing(q.mode()));
        body.putObject("date_time").put("type", DEPART_AT)
                .put("value", q.departAt().withZoneSameInstant(zone(q)).format(LOCAL));
        body.put("units", "kilometers");
        body.put("directions_type", "maneuvers");
        return body;
    }

    static String costing(TravelMode mode) {
        return switch (mode) {
            case WALK -> "pedestrian";
            case BICYCLE -> "bicycle";
            case CAR -> "auto";
            case TAXI -> "taxi";
            case TRANSIT -> throw new IllegalArgumentException("Valhalla는 대중교통 시간표를 다루지 않는다");
        };
    }

    RouteResult parse(JsonNode root, RouteQuery q) {
        JsonNode trip = root.path("trip");
        JsonNode summary = trip.path("summary");
        if (!summary.path("time").isNumber() || !summary.path("length").isNumber()) {
            return unavailable("unreadable response");
        }
        LegMode main = switch (q.mode()) {
            case WALK -> LegMode.WALK;
            case BICYCLE -> LegMode.BICYCLE;
            case TAXI -> LegMode.TAXI;
            default -> LegMode.CAR;
        };
        OffsetDateTime departure = q.departAt().toOffsetDateTime();
        JsonNode leg0 = trip.path("legs").path(0);
        List<RouteLeg> legs = legs(leg0.path("maneuvers"), decode(leg0.path("shape").asText("")), main, departure, q);
        int seconds = (int) Math.round(summary.path("time").asDouble());
        int meters = (int) Math.round(summary.path("length").asDouble() * METERS_PER_KM);
        if (legs.isEmpty()) {
            legs = List.of(new RouteLeg(main, null, null, q.origin(), q.destination(), departure,
                    departure.plusSeconds(seconds), seconds, meters, null, null, false));
        }
        List<Fare> fares = q.mode() == TravelMode.TAXI
                ? taxiFares.estimate(q.origin(), meters, q.departAt().toLocalDate()).map(List::of).orElse(List.of())
                : List.of();
        return RouteResult.found(id, List.of(new RouteItinerary(departure, departure.plusSeconds(seconds), seconds,
                0, legs, fares, source(RouteBasis.ROAD_NETWORK))));
    }

    /** 배 타기부터 내리기 안내까지를 한 FERRY 구간으로, 나머지 연속 안내를 주 수단 구간으로 묶는다. */
    private static List<RouteLeg> legs(JsonNode maneuvers, List<GeoPoint> shape, LegMode main, OffsetDateTime start,
                                       RouteQuery q) {
        List<RouteLeg> legs = new ArrayList<>();
        if (!maneuvers.isArray() || maneuvers.isEmpty()) return legs;
        OffsetDateTime cursor = start;
        LegMode current = null;
        double seconds = 0;
        double km = 0;
        GeoPoint from = q.origin();
        GeoPoint to = q.origin();
        for (JsonNode m : maneuvers) {
            int type = m.path("type").asInt();
            LegMode mode = current == LegMode.FERRY && type != FERRY_EXIT || type == FERRY_ENTER ? LegMode.FERRY : main;
            GeoPoint begin = at(shape, m.path("begin_shape_index").asInt(-1), to);
            if (current != null && mode != current) {
                legs.add(leg(current, cursor, seconds, km, from, begin));
                cursor = cursor.plusSeconds(Math.round(seconds));
                seconds = 0;
                km = 0;
                from = begin;
            }
            current = mode;
            seconds += m.path("time").asDouble();
            km += m.path("length").asDouble();
            to = at(shape, m.path("end_shape_index").asInt(-1), begin);
        }
        if (current != null) legs.add(leg(current, cursor, seconds, km, from, q.destination()));
        return legs;
    }

    private static RouteLeg leg(LegMode mode, OffsetDateTime departure, double seconds, double km, GeoPoint from,
                                GeoPoint to) {
        long s = Math.round(seconds);
        return new RouteLeg(mode, null, null, from, to, departure, departure.plusSeconds(s),
                (int) s, (int) Math.round(km * METERS_PER_KM), null, null, false);
    }

    private static GeoPoint at(List<GeoPoint> shape, int index, GeoPoint fallback) {
        return index >= 0 && index < shape.size() ? shape.get(index) : fallback;
    }

    /** Valhalla 경로선은 소수 여섯째 자리 정밀도의 Google polyline 형식이다. */
    static List<GeoPoint> decode(String polyline) {
        List<GeoPoint> points = new ArrayList<>();
        int index = 0;
        long lat = 0;
        long lng = 0;
        try {
            while (index < polyline.length()) {
                long[] d = new long[2];
                for (int k = 0; k < 2; k++) {
                    long result = 0;
                    int shift = 0;
                    int b;
                    do {
                        b = polyline.charAt(index++) - 63;
                        result |= (long) (b & 0x1f) << shift;
                        shift += 5;
                    } while (b >= 0x20);
                    d[k] = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
                }
                lat += d[0];
                lng += d[1];
                points.add(new GeoPoint(lat / 1e6, lng / 1e6));
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return points;
    }
}
