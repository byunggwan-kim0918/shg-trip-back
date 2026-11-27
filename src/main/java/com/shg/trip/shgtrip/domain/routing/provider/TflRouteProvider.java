package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 런던 TfL Journey Planner. 시각은 런던 현지 시각(시간대 표기 없음)으로 주고받는다. */
public class TflRouteProvider extends AbstractRouteProvider {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final BigDecimal PENCE_PER_POUND = BigDecimal.valueOf(100);

    public TflRouteProvider(String id, ProviderSettings settings, RoutingHttp http, ObjectMapper mapper, Clock clock) {
        super(id, settings, http, mapper, clock);
    }

    @Override
    protected RouteResult call(RouteQuery q) throws IOException {
        ZonedDateTime local = q.departAt().withZoneSameInstant(zone(q));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("date", local.format(DATE));
        params.put("time", local.format(TIME));
        params.put("timeIs", "Departing");
        switch (q.mode()) {
            case WALK -> params.put("mode", "walking");
            case BICYCLE -> params.put("mode", "cycle");
            default -> { }
        }
        if (settings.apiKey() != null && !settings.apiKey().isBlank()) params.put("app_key", settings.apiKey());
        String path = String.format(Locale.ROOT, "/Journey/JourneyResults/%.6f,%.6f/to/%.6f,%.6f",
                q.origin().lat(), q.origin().lng(), q.destination().lat(), q.destination().lng());
        RoutingHttp.Response r = http.get(RoutingHttp.uri(settings.baseUrl(), path, params), Map.of(), settings.timeout());
        return switch (r.status()) {
            case 200 -> parse(json(r.body()), q);
            // 좌표를 장소 하나로 못 정해 후보 목록을 돌려준 경우다.
            case 300 -> status(RouteStatus.NOT_COVERED, "tfl disambiguation");
            case 404 -> status(RouteStatus.NO_CONNECTION, "tfl no journey");
            default -> unavailable("http " + r.status());
        };
    }

    RouteResult parse(JsonNode root, RouteQuery q) {
        ZoneId zone = zone(q);
        List<RouteItinerary> out = new ArrayList<>();
        for (JsonNode j : root.path("journeys")) {
            List<RouteLeg> legs = new ArrayList<>();
            for (JsonNode l : j.path("legs")) {
                JsonNode dep = l.path("departurePoint");
                JsonNode arr = l.path("arrivalPoint");
                String line = l.path("routeOptions").path(0).path("name").asText("");
                OffsetDateTime departure = local(l.path("departureTime").asText(), zone);
                OffsetDateTime arrival = local(l.path("arrivalTime").asText(), zone);
                boolean realtime = !l.path("scheduledDepartureTime").asText("").isEmpty()
                        && !l.path("scheduledDepartureTime").asText().equals(l.path("departureTime").asText());
                legs.add(new RouteLeg(mode(l.path("mode").path("id").asText()),
                        dep.path("commonName").asText(null), arr.path("commonName").asText(null),
                        point(dep, q.origin()), point(arr, q.destination()), departure, arrival,
                        l.path("duration").asInt() * 60,
                        l.path("distance").isNumber() ? (int) Math.round(l.path("distance").asDouble()) : null,
                        line.isBlank() ? null : line, null, realtime));
            }
            List<Fare> fares = j.path("fare").path("totalCost").isNumber()
                    ? List.of(new Fare(BigDecimal.valueOf(j.path("fare").path("totalCost").asLong())
                    .divide(PENCE_PER_POUND), "GBP", Fare.Kind.TICKET))
                    : List.of();
            boolean realtime = legs.stream().anyMatch(RouteLeg::realtime);
            out.add(new RouteItinerary(local(j.path("startDateTime").asText(), zone),
                    local(j.path("arrivalDateTime").asText(), zone), j.path("duration").asInt() * 60,
                    RouteItinerary.transfersOf(legs), legs, fares,
                    source(realtime ? RouteBasis.REALTIME : RouteBasis.OFFICIAL_SCHEDULE)));
        }
        if (out.isEmpty()) return status(RouteStatus.NO_CONNECTION, "tfl empty journeys");
        return RouteResult.found(id, out);
    }

    private static OffsetDateTime local(String isoLocal, ZoneId zone) {
        return LocalDateTime.parse(isoLocal).atZone(zone).toOffsetDateTime();
    }

    /** 승하차 지점 좌표가 빠진 구간은 요청 좌표로 채운다. */
    private static GeoPoint point(JsonNode p, GeoPoint fallback) {
        return p.path("lat").isNumber() && p.path("lon").isNumber()
                ? new GeoPoint(p.path("lat").asDouble(), p.path("lon").asDouble()) : fallback;
    }

    static LegMode mode(String tfl) {
        return switch (tfl) {
            case "walking" -> LegMode.WALK;
            case "cycle", "cycle-hire" -> LegMode.BICYCLE;
            case "tube", "dlr" -> LegMode.SUBWAY;
            case "overground", "elizabeth-line", "national-rail", "tflrail", "international-rail" -> LegMode.RAIL;
            case "bus", "coach", "replacement-bus" -> LegMode.BUS;
            case "tram" -> LegMode.TRAM;
            case "river-bus", "river-tour" -> LegMode.FERRY;
            case "cable-car" -> LegMode.CABLE;
            case "taxi" -> LegMode.TAXI;
            default -> LegMode.OTHER;
        };
    }
}
