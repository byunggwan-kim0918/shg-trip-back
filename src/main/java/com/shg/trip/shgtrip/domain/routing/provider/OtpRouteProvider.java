package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shg.trip.shgtrip.domain.routing.FeedCoverage;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteSource;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingProperties.ProviderSettings;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 지역별 OpenTripPlanner 2.8 서버(GTFS GraphQL API). 그래프 하나가 한 지역이고, 지역마다 설정 항목 하나다.
 * 대중교통 시각은 GTFS 시간표이며, 서버에 GTFS-RT가 붙어 구간에 realTime이 오면 실시간으로 본다.
 */
public class OtpRouteProvider extends AbstractRouteProvider {

    static final String PATH = "/otp/gtfs/v1";
    private static final int ITINERARIES = 3;

    private static final String QUERY = """
            query Plan($origin: PlanLabeledLocationInput!, $destination: PlanLabeledLocationInput!,
                       $dt: OffsetDateTime!, $first: Int, $modes: PlanModesInput, $window: Duration) {
              planConnection(
                origin: $origin
                destination: $destination
                dateTime: {earliestDeparture: $dt}
                first: $first
                modes: $modes
                searchWindow: $window
              ) {
                routingErrors { code description inputField }
                edges { node {
                  start end duration numberOfTransfers
                  legs {
                    mode duration distance realTime transitLeg
                    start { scheduledTime estimated { time } }
                    end { scheduledTime estimated { time } }
                    from { name lat lon }
                    to { name lat lon }
                    route { gtfsId shortName longName agency { name } }
                    fareProducts { id product { riderCategory { id } medium { id }
                      ... on DefaultFareProduct { price { amount currency { code } } } } }
                  }
                } }
              }
            }""";

    private static final String STOPS_IN_BBOX = """
            query Stops($minLat: Float!, $minLon: Float!, $maxLat: Float!, $maxLon: Float!) {
              stopsByBbox(minLat: $minLat, minLon: $minLon, maxLat: $maxLat, maxLon: $maxLon) {
                gtfsId name lat lon vehicleMode
              }
            }""";

    /** 정류장·운행일이 데이터에 없다는 뜻이라 이동 불가능과 구분한다. */
    private static final Set<String> DATA_GAP_ERRORS = Set.of(
            "OUTSIDE_BOUNDS", "LOCATION_NOT_FOUND", "NO_STOPS_IN_RANGE", "OUTSIDE_SERVICE_PERIOD");
    /** 끝점이 길과 이어지지 않았을 때 나는 오류. 공항·역 장소 좌표가 부지 중심이면 난다. */
    private static final Set<String> UNLINKED_ENDPOINT_ERRORS = Set.of("LOCATION_NOT_FOUND", "NO_STOPS_IN_RANGE");
    /** 출발지·도착지를 하나씩 옮기면 둘 다 길과 끊긴 경우에도 두 번이면 끝난다. */
    private static final int MAX_SNAPS = 2;
    private static final double METERS_PER_DEGREE_LAT = 111_320;
    /** 정류장 재조회 때 먼저 볼 수단. 공항·항만·역처럼 장거리 수단은 근처 버스 정류장에 가려지기 쉽다. */
    private static final List<String> MODE_PREFERENCE = List.of("AIRPLANE", "FERRY", "RAIL", "COACH", "SUBWAY", "TRAM", "BUS");
    private static final int MAX_MODE_PAIRS = 3;
    /** 대중교통 요청에 이보다 긴 도보만 나오면 끝점이 정류장과 끊겼을 수 있다. 도시 안 도보 이동 한도에 맞춘다. */
    private static final int WALK_ONLY_SUSPECT_SECONDS = 30 * 60;
    /** 하루 몇 편뿐인 지방 노선도 다음 편을 찾는 범위. */
    private static final String WIDE_SEARCH_WINDOW = "PT6H";
    /** OTP 기본 보행 속도. 출발지를 정류장으로 옮기면 그만큼 걸어간 뒤 떠나는 편을 고른다. */
    private static final double WALK_METERS_PER_SECOND = 1.33;
    /** 한 질의 안에서 끝점 옮기기·수단별 재조회로 여러 번 부를 때 전체가 이 배수의 제한시간을 넘지 않게 한다. */
    private static final int CALL_DEADLINE_TIMEOUTS = 3;
    /** 운행 기간 조회가 실패하면 그동안 다시 묻지 않는다. 느린 서버에서 질의마다 제한시간을 더 쓰지 않게 한다. */
    private static final Duration SERVICE_RANGE_RETRY = Duration.ofMinutes(1);

    private final FeedCoverage.File feedCoverage;

    public OtpRouteProvider(String id, ProviderSettings settings, RoutingHttp http, ObjectMapper mapper, Clock clock) {
        this(id, settings, http, mapper, clock, null);
    }

    /** @param coverageDir feed-coverage-file 상대 경로의 기준 폴더. null이면 절대 경로만 쓴다 */
    public OtpRouteProvider(String id, ProviderSettings settings, RoutingHttp http, ObjectMapper mapper, Clock clock,
                            String coverageDir) {
        super(id, settings, http, mapper, clock);
        this.feedCoverage = settings.feedCoveragePath(coverageDir).map(p -> new FeedCoverage.File(p, mapper, clock))
                .orElse(null);
    }

    @Override
    public String dataVersion() {
        return feedCoverage == null ? null : feedCoverage.version();
    }

    private static final String SERVICE_RANGE = "{ serviceTimeRange { start end } }";
    /** 그래프는 다시 지을 때만 바뀌므로 운행 기간을 이만큼 재사용한다. */
    private static final Duration SERVICE_RANGE_TTL = Duration.ofHours(1);

    private record ServiceRange(Instant start, Instant end, Instant fetchedAt) {}

    private final AtomicReference<ServiceRange> serviceRange = new AtomicReference<>();
    private final AtomicReference<Instant> serviceRangeFailedAt = new AtomicReference<>();
    private final ThreadLocal<Instant> deadline = new ThreadLocal<>();

    @Override
    protected RouteResult call(RouteQuery q) throws IOException {
        deadline.set(clock.instant().plus(settings.timeout().multipliedBy(CALL_DEADLINE_TIMEOUTS)));
        try {
            Optional<ServiceRange> range = serviceRange();
            Instant at = q.departAt().toInstant();
            if (range.isPresent() && (at.isBefore(range.get().start()) || at.isAfter(range.get().end()))) {
                return projected(q, range.get());
            }
            RouteResult r = settings.stopToStop() ? stopToStop(q) : coordinates(q);
            RouteResult valid = range.map(sr -> withValidity(r, sr, true, q.departAt().toLocalDate())).orElse(r);
            return withFeedCoverage(valid, q);
        } finally {
            deadline.remove();
        }
    }

    private RoutingHttp.Response post(ObjectNode body) throws IOException {
        Instant until = deadline.get();
        if (until != null && clock.instant().isAfter(until)) throw new RoutingHttp.CallFailed("call deadline exceeded");
        return http.postJson(RoutingHttp.uri(settings.baseUrl(), PATH, Map.of()), mapper.writeValueAsString(body),
                Map.of(), settings.timeout());
    }

    /**
     * 그래프 운행 기간 밖 날짜는 시간표가 없다. 기간 안 같은 요일·같은 시각 시간표로 소요 시간만 내고 추정으로 표시한다.
     * 그 날짜 그 편이 다닌다는 뜻이 아니므로 출발 시각을 확정하지 않는다.
     */
    private RouteResult projected(RouteQuery q, ServiceRange range) throws IOException {
        ZoneId zone = zone(q);
        java.time.LocalDate last = range.end().atZone(zone).toLocalDate().minusDays(1);
        java.time.LocalDate first = range.start().atZone(zone).toLocalDate().plusDays(1);
        java.time.LocalDate day = last;
        while (day.getDayOfWeek() != q.departAt().getDayOfWeek() && day.isAfter(first)) day = day.minusDays(1);
        if (day.getDayOfWeek() != q.departAt().getDayOfWeek()) {
            return status(RouteStatus.NOT_COVERED, "outside service period until " + range.end());
        }
        long shiftDays = java.time.temporal.ChronoUnit.DAYS.between(day, q.departAt().toLocalDate());
        RouteQuery borrowed = new RouteQuery(q.origin(), q.destination(),
                q.departAt().with(day), q.mode());
        RouteResult r = settings.stopToStop() ? stopToStop(borrowed) : coordinates(borrowed);
        if (r.status() == RouteStatus.UNAVAILABLE) return r;
        // 빌린 날이 감편일이면 연결이 없을 수 있다. 요청 날짜는 시간표가 없는 것이지 이동 불가능이 아니다.
        if (r.status() != RouteStatus.FOUND) {
            return status(RouteStatus.NOT_COVERED, "outside service period until " + range.end() + "; borrowed "
                    + day + " " + r.status());
        }
        RouteSource.ScheduleValidity validity = new RouteSource.ScheduleValidity(range.start(), range.end(), false, day);
        List<RouteItinerary> shifted = r.itineraries().stream().map(it -> shiftDays(it, shiftDays, zone, validity))
                .toList();
        return new RouteResult(RouteStatus.FOUND, shifted, id, "projected from " + day + " timetable; requested date "
                + q.departAt().toLocalDate() + " is outside service period until " + range.end(), List.of());
    }

    private static RouteItinerary shiftDays(RouteItinerary it, long days, ZoneId zone,
                                            RouteSource.ScheduleValidity validity) {
        List<RouteLeg> legs = it.legs().stream().map(l -> l.shifted(plusLocalDays(l.departure(), days, zone),
                plusLocalDays(l.arrival(), days, zone))).toList();
        RouteSource s = it.source();
        return new RouteItinerary(plusLocalDays(it.departure(), days, zone), plusLocalDays(it.arrival(), days, zone),
                it.durationSeconds(),
                it.transfers(), legs, it.fares(), new RouteSource(s.providerId(), RouteBasis.ESTIMATED, s.officialData(),
                s.attribution(), s.observedAt(), s.expiresAt(), validity), it.endpointGapMeters());
    }

    /** 일광절약시간을 건너도 현지 시각이 같게 옮긴다. */
    static OffsetDateTime plusLocalDays(OffsetDateTime t, long days, ZoneId zone) {
        return t.atZoneSameInstant(zone).plusDays(days).toOffsetDateTime();
    }

    /** 근처 피드가 그날 운행 기간 밖이면 그 운행이 빠진 경로라 시간표를 확정하지 않는다. */
    private RouteResult withFeedCoverage(RouteResult r, RouteQuery q) {
        if (feedCoverage == null || r.status() != RouteStatus.FOUND) return r;
        java.time.LocalDate day = q.departAt().withZoneSameInstant(zone(q)).toLocalDate();
        List<FeedCoverage.Feed> lapsed = feedCoverage.get().lapsed(q.origin(), q.destination(), day);
        if (lapsed.isEmpty()) return r;
        // 도보만 나온 경로도 빠진 버스 대신 나온 것일 수 있어 함께 낮춘다.
        List<RouteItinerary> out = r.itineraries().stream().map(it -> {
            RouteSource s = it.source();
            return new RouteItinerary(it.departure(), it.arrival(), it.durationSeconds(), it.transfers(), it.legs(),
                    it.fares(), new RouteSource(s.providerId(), RouteBasis.weakest(s.basis(), RouteBasis.UNVERIFIED_SCHEDULE),
                    s.officialData(), s.attribution(), s.observedAt(), s.expiresAt(), s.validity()), it.endpointGapMeters());
        }).toList();
        String note = FeedCoverage.describe(lapsed, day);
        return new RouteResult(r.status(), out, r.providerId(), r.reason() == null ? note : r.reason() + "; " + note,
                r.attempts());
    }

    private RouteResult withValidity(RouteResult r, ServiceRange range, boolean covered, java.time.LocalDate day) {
        if (r.status() != RouteStatus.FOUND) return r;
        RouteSource.ScheduleValidity v = new RouteSource.ScheduleValidity(range.start(), range.end(), covered, day);
        return new RouteResult(r.status(), r.itineraries().stream().map(it -> new RouteItinerary(it.departure(),
                it.arrival(), it.durationSeconds(), it.transfers(), it.legs(), it.fares(), withValidity(it.source(), v),
                it.endpointGapMeters())).toList(), r.providerId(), r.reason(), r.attempts());
    }

    private static RouteSource withValidity(RouteSource s, RouteSource.ScheduleValidity v) {
        return new RouteSource(s.providerId(), s.basis(), s.officialData(), s.attribution(), s.observedAt(),
                s.expiresAt(), v);
    }

    /** 운행 기간을 못 읽으면 막지 않고 OTP 판정에 맡긴다. */
    private Optional<ServiceRange> serviceRange() {
        ServiceRange cached = serviceRange.get();
        Instant now = clock.instant();
        if (cached != null && cached.fetchedAt().plus(SERVICE_RANGE_TTL).isAfter(now)) {
            return Optional.of(cached);
        }
        Instant failed = serviceRangeFailedAt.get();
        if (failed != null && failed.plus(SERVICE_RANGE_RETRY).isAfter(now)) return Optional.ofNullable(cached);
        try {
            RoutingHttp.Response r = post(mapper.createObjectNode().put("query", SERVICE_RANGE));
            JsonNode range = r.ok() ? json(r.body()).path("data").path("serviceTimeRange") : null;
            if (range == null || !range.path("end").isNumber()) {
                serviceRangeFailedAt.set(now);
                return Optional.ofNullable(cached);
            }
            ServiceRange fresh = new ServiceRange(Instant.ofEpochSecond(range.path("start").asLong()),
                    Instant.ofEpochSecond(range.path("end").asLong()), clock.instant());
            serviceRange.set(fresh);
            return Optional.of(fresh);
        } catch (IOException | RuntimeException e) {
            serviceRangeFailedAt.set(now);
            return Optional.ofNullable(cached);
        }
    }

    /** 시간표만 넣은 그래프는 좌표를 길에 붙일 수 없어 양 끝을 가까운 정류장으로 바꿔 묻는다. */
    private RouteResult stopToStop(RouteQuery q) throws IOException {
        Optional<NamedStop> from = nearestStop(q.origin());
        Optional<NamedStop> to = nearestStop(q.destination());
        if (from.isEmpty() || to.isEmpty()) {
            return status(RouteStatus.NOT_COVERED, "no stop within " + settings.snapToStopMeters() + "m");
        }
        if (from.get().id().equals(to.get().id())) return status(RouteStatus.NOT_COVERED, "same stop");
        RouteResult result = plan(q, stop(from.get()), stop(to.get()));
        if (result.status() != RouteStatus.FOUND) return result;
        int gap = meters(q.origin(), from.get().point()) + meters(q.destination(), to.get().point());
        return new RouteResult(RouteStatus.FOUND, result.itineraries().stream().map(it -> it.withEndpointGap(gap))
                .toList(), id, "stops " + from.get().name() + " → " + to.get().name(), List.of());
    }

    /**
     * 양 끝에서 수단별로 가장 가까운 정류장을 골라 같은 수단끼리 묻는다. 공항 옆 버스 정류장끼리 물으면 항공편을 놓친다.
     * 응답 시간을 묶기 위해 장거리 수단부터 MAX_MODE_PAIRS 쌍만 시도한다.
     */
    private RouteResult stopToStopByMode(RouteQuery q) throws IOException {
        Map<String, NamedStop> from = nearestByMode(q.origin());
        Map<String, NamedStop> to = nearestByMode(q.destination());
        int tried = 0;
        for (String mode : MODE_PREFERENCE) {
            NamedStop a = from.get(mode);
            NamedStop b = to.get(mode);
            if (a == null || b == null || a.id().equals(b.id())) continue;
            if (tried++ >= MAX_MODE_PAIRS) break;
            RouteResult r = plan(afterWalk(q, meters(q.origin(), a.point())), stop(a), stop(b));
            if (r.status() != RouteStatus.FOUND) continue;
            int gap = meters(q.origin(), a.point()) + meters(q.destination(), b.point());
            return new RouteResult(RouteStatus.FOUND, r.itineraries().stream().map(it -> it.withEndpointGap(gap)).toList(),
                    id, "stops " + a.name() + " → " + b.name() + " (" + mode + ")", List.of());
        }
        return status(RouteStatus.NO_CONNECTION, "no connection between nearby stops");
    }

    private RouteResult coordinates(RouteQuery q) throws IOException {
        RouteQuery current = q;
        List<String> snaps = new ArrayList<>();
        int gapMeters = 0;
        RouteResult result = plan(current, coordinate(current.origin()), coordinate(current.destination()));
        while (snaps.size() < MAX_SNAPS && settings.snapToStopMeters() > 0 && unlinkedEndpoint(result) != null) {
            boolean origin = "FROM".equals(unlinkedEndpoint(result));
            GeoPoint from = origin ? current.origin() : current.destination();
            Optional<NamedStop> stop = nearestStop(from);
            if (stop.isEmpty()) break;
            int meters = meters(from, stop.get().point());
            current = origin
                    ? afterWalk(new RouteQuery(stop.get().point(), current.destination(), current.departAt(),
                            current.mode()), meters)
                    : new RouteQuery(current.origin(), stop.get().point(), current.departAt(), current.mode());
            gapMeters += meters;
            snaps.add((origin ? "origin" : "destination") + " snapped to " + stop.get().name() + " " + meters + "m");
            result = plan(current, coordinate(current.origin()), coordinate(current.destination()));
        }
        if (result.status() == RouteStatus.FOUND && !snaps.isEmpty()) {
            int gap = gapMeters;
            // 옮긴 끝점까지 걷기만 하는 경로는 원래 목적지에 닿은 것이 아니다(물 건너 정류장으로 옮긴 경우 등).
            List<RouteItinerary> withTransit = result.itineraries().stream().filter(RouteItinerary::usesTransit)
                    .map(it -> it.withEndpointGap(gap)).toList();
            if (withTransit.isEmpty()) {
                return status(RouteStatus.NOT_COVERED, "unlinked endpoint; " + String.join("; ", snaps) + " gives walking only");
            }
            return new RouteResult(RouteStatus.FOUND, withTransit, id, String.join("; ", snaps), List.of());
        }
        // 공항·항만 정류장은 길에 붙어도 걸어 닿지 못하는 섬에 놓여, 좌표 조회가 연결 없음이나 먼 도보로 이어진다.
        if (settings.snapToStopMeters() > 0 && (result.status() == RouteStatus.NO_CONNECTION || longWalkAtEnds(q, result))) {
            RouteResult viaStops = stopToStopByMode(q);
            if (viaStops.status() == RouteStatus.FOUND && arrivesEarlier(viaStops, result)) return viaStops;
        }
        return result;
    }

    /** 옮긴 정류장까지 걸어간 뒤에 떠나는 편만 탈 수 있다. 걷는 시간은 호출부가 끝점 간격으로 더한다. */
    private static RouteQuery afterWalk(RouteQuery q, int meters) {
        return new RouteQuery(q.origin(), q.destination(),
                q.departAt().plusSeconds(Math.round(meters / WALK_METERS_PER_SECOND)), q.mode());
    }

    /** 배차가 몇 시간에 한 편인 노선은 기본 탐색 범위 밖에 다음 편이 있어 한 번 넓혀 다시 묻는다. */
    private RouteResult plan(RouteQuery q, ObjectNode origin, ObjectNode destination) throws IOException {
        RouteResult r = plan(q, origin, destination, null);
        if (r.status() == RouteStatus.NO_CONNECTION && "NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW".equals(r.reason())) {
            return plan(q, origin, destination, WIDE_SEARCH_WINDOW);
        }
        return r;
    }

    private RouteResult plan(RouteQuery q, ObjectNode origin, ObjectNode destination, String window) throws IOException {
        ObjectNode body = requestBody(q);
        if (window != null) ((ObjectNode) body.path("variables")).put("window", window);
        ObjectNode v = (ObjectNode) body.path("variables");
        v.set("origin", origin);
        v.set("destination", destination);
        RoutingHttp.Response r = post(body);
        if (!r.ok()) return unavailable("http " + r.status());
        return parse(json(r.body()), q);
    }

    /** 모든 경로가 먼 도보만이거나 처음·끝 도보가 길면 끝점이 정류장과 끊긴 것으로 본다. */
    private static boolean longWalkAtEnds(RouteQuery q, RouteResult r) {
        return q.mode() == TravelMode.TRANSIT && r.status() == RouteStatus.FOUND
                && r.itineraries().stream().allMatch(it -> it.usesTransit()
                ? longWalk(it.legs().getFirst()) || longWalk(it.legs().getLast())
                : it.durationSeconds() > WALK_ONLY_SUSPECT_SECONDS);
    }

    private static boolean longWalk(RouteLeg leg) {
        return leg.mode() == LegMode.WALK && leg.durationSeconds() > WALK_ONLY_SUSPECT_SECONDS;
    }

    /** 정류장끼리 낸 경로는 끝점 간격을 걷는 시간이 빠져 있어 그만큼 더해 비교한다. */
    private static boolean arrivesEarlier(RouteResult viaStops, RouteResult coordinates) {
        if (coordinates.status() != RouteStatus.FOUND) return true;
        RouteItinerary stops = viaStops.earliestArrival().orElseThrow();
        OffsetDateTime withWalk = stops.arrival().plusSeconds(Math.round(stops.endpointGapMeters() / WALK_METERS_PER_SECOND));
        return withWalk.isBefore(coordinates.earliestArrival().orElseThrow().arrival());
    }

    /** NOT_COVERED 사유가 끝점이 길과 끊긴 경우면 FROM 또는 TO, 아니면 null. */
    private static String unlinkedEndpoint(RouteResult r) {
        if (r.status() != RouteStatus.NOT_COVERED || r.reason() == null) return null;
        String[] codeAndField = r.reason().split("@", 2);
        return codeAndField.length == 2 && UNLINKED_ENDPOINT_ERRORS.contains(codeAndField[0])
                && Set.of("FROM", "TO").contains(codeAndField[1]) ? codeAndField[1] : null;
    }

    record NamedStop(String id, String name, GeoPoint point, String mode) {}

    private ObjectNode coordinate(GeoPoint p) {
        ObjectNode n = mapper.createObjectNode();
        n.putObject("location").putObject("coordinate").put("latitude", p.lat()).put("longitude", p.lng());
        return n;
    }

    private ObjectNode stop(NamedStop s) {
        ObjectNode n = mapper.createObjectNode();
        n.putObject("location").putObject("stopLocation").put("stopLocationId", s.id());
        return n;
    }

    private static int meters(GeoPoint a, GeoPoint b) {
        return (int) Math.round(GeoUtils.haversine(a.lat(), a.lng(), b.lat(), b.lng()) * 1000);
    }

    private Map<String, NamedStop> nearestByMode(GeoPoint p) throws IOException {
        Map<String, NamedStop> out = new java.util.HashMap<>();
        for (NamedStop s : stopsNear(p)) out.putIfAbsent(s.mode(), s);
        return out;
    }

    /** 걷는 길로 찾는 stopsByRadius는 길과 끊긴 점에서 비므로 사각형으로 찾고 직선거리로 고른다. */
    private Optional<NamedStop> nearestStop(GeoPoint p) throws IOException {
        return stopsNear(p).stream()
                .filter(s -> settings.stopModes().isEmpty() || settings.stopModes().contains(s.mode())).findFirst();
    }

    /** snapToStopMeters 안 정류장을 가까운 순으로. */
    private List<NamedStop> stopsNear(GeoPoint p) throws IOException {
        int radius = settings.snapToStopMeters();
        double dLat = radius / METERS_PER_DEGREE_LAT;
        double dLng = radius / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(p.lat())));
        ObjectNode body = mapper.createObjectNode();
        body.put("query", STOPS_IN_BBOX);
        ObjectNode v = body.putObject("variables");
        v.put("minLat", p.lat() - dLat);
        v.put("minLon", p.lng() - dLng);
        v.put("maxLat", p.lat() + dLat);
        v.put("maxLon", p.lng() + dLng);
        RoutingHttp.Response r = post(body);
        // 정류장 조회 실패를 빈 목록으로 두면 데이터 밖으로 분류돼 캐시된다.
        if (!r.ok()) throw new RoutingHttp.CallFailed("stops http " + r.status());
        List<NamedStop> out = new ArrayList<>();
        for (JsonNode s : json(r.body()).path("data").path("stopsByBbox")) {
            GeoPoint at = new GeoPoint(s.path("lat").asDouble(), s.path("lon").asDouble());
            if (meters(p, at) <= radius) {
                out.add(new NamedStop(s.path("gtfsId").asText(), s.path("name").asText(), at, s.path("vehicleMode").asText()));
            }
        }
        out.sort(java.util.Comparator.comparingInt(x -> meters(p, x.point())));
        return out;
    }

    ObjectNode requestBody(RouteQuery q) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", QUERY);
        ObjectNode v = body.putObject("variables");
        v.put("dt", q.departAt().toOffsetDateTime().toString());
        v.put("first", ITINERARIES);
        ObjectNode modes = v.putObject("modes");
        switch (q.mode()) {
            case TRANSIT -> modes.putArray("direct").add("WALK");
            case WALK -> modes.put("directOnly", true).putArray("direct").add("WALK");
            case BICYCLE -> modes.put("directOnly", true).putArray("direct").add("BICYCLE");
            case CAR, TAXI -> modes.put("directOnly", true).putArray("direct").add("CAR");
        }
        return body;
    }

    RouteResult parse(JsonNode root, RouteQuery q) {
        if (root.path("errors").isArray() && !root.path("errors").isEmpty()) {
            return unavailable("graphql error");
        }
        JsonNode plan = root.path("data").path("planConnection");
        List<RouteItinerary> all = new ArrayList<>();
        for (JsonNode edge : plan.path("edges")) all.add(itinerary(edge.path("node")));
        List<RouteItinerary> itineraries = q.mode() == TravelMode.TRANSIT ? dropSlowerThanTransit(all) : all;
        if (!itineraries.isEmpty()) return RouteResult.found(id, itineraries);
        // 연결 없음으로 분류하면 데이터 공백인데도 끝점 옮기기가 일어나지 않고 그대로 캐시된다.
        String firstCode = null;
        for (JsonNode error : plan.path("routingErrors")) {
            String code = error.path("code").asText("");
            if (DATA_GAP_ERRORS.contains(code)) {
                return status(RouteStatus.NOT_COVERED, code + "@" + error.path("inputField").asText(""));
            }
            if (firstCode == null && !code.isEmpty()) firstCode = code;
        }
        return status(RouteStatus.NO_CONNECTION, firstCode == null ? "no itinerary" : firstCode);
    }

    /** OTP는 대중교통 요청에도 도보만 쓰는 경로를 늘 함께 준다. 대중교통보다 늦게 닿는 도보 경로는 몇 시간짜리라도 섞여 든다. */
    private static List<RouteItinerary> dropSlowerThanTransit(List<RouteItinerary> all) {
        Optional<OffsetDateTime> transitArrival = all.stream().filter(RouteItinerary::usesTransit)
                .map(RouteItinerary::arrival).min(OffsetDateTime::compareTo);
        if (transitArrival.isEmpty()) return all;
        return all.stream().filter(it -> it.usesTransit() || !it.arrival().isAfter(transitArrival.get())).toList();
    }

    private RouteItinerary itinerary(JsonNode n) {
        List<RouteLeg> legs = new ArrayList<>();
        boolean realtime = false;
        boolean transit = false;
        for (JsonNode l : n.path("legs")) {
            boolean rt = l.path("realTime").asBoolean(false);
            boolean transitLeg = l.path("transitLeg").asBoolean(false);
            realtime |= rt;
            transit |= transitLeg;
            JsonNode route = l.path("route");
            String operator = route.isMissingNode() || route.isNull() ? null : text(route.path("agency").path("name"));
            legs.add(new RouteLeg(mode(l.path("mode").asText()),
                    text(l.path("from").path("name")), text(l.path("to").path("name")),
                    point(l.path("from")), point(l.path("to")),
                    time(l.path("start")), time(l.path("end")),
                    (int) Math.round(l.path("duration").asDouble()),
                    l.path("distance").isNumber() ? (int) Math.round(l.path("distance").asDouble()) : null,
                    route.isMissingNode() || route.isNull() ? null
                            : firstNonBlank(text(route.path("shortName")), text(route.path("longName"))),
                    operator, rt, transitLeg && unverified(route.path("gtfsId").asText(""), operator)));
        }
        RouteBasis basis = realtime ? RouteBasis.REALTIME : transit ? RouteBasis.GTFS_SCHEDULE : RouteBasis.ROAD_NETWORK;
        if (legs.stream().anyMatch(RouteLeg::unverifiedSchedule)) basis = RouteBasis.UNVERIFIED_SCHEDULE;
        return new RouteItinerary(OffsetDateTime.parse(n.path("start").asText()),
                OffsetDateTime.parse(n.path("end").asText()), n.path("duration").asInt(),
                n.path("numberOfTransfers").asInt(0), legs, fares(n.path("legs")), source(basis));
    }

    /**
     * GTFS 요금(fare_attributes)으로 OTP가 낸 승차권 합. 같은 사용 id는 갈아타기를 한 장으로 덮는 표라 한 번만 센다.
     * 탈 것 하나라도 요금이 없으면 일부만 더한 금액이 되어 요금을 모르는 것으로 둔다. 매체가 여럿이면 현금처럼 매체를 가리지
     * 않는 상품, 없으면 가장 비싼 상품을 쓴다(교통카드 할인은 여행자가 카드를 가졌는지 모른다).
     */
    static List<com.shg.trip.shgtrip.domain.routing.Fare> fares(JsonNode legs) {
        Map<String, java.math.BigDecimal> byUse = new java.util.LinkedHashMap<>();
        String currency = null;
        for (JsonNode l : legs) {
            if (!l.path("transitLeg").asBoolean(false)) continue;
            JsonNode chosen = null;
            String useId = null;
            for (JsonNode use : l.path("fareProducts")) {
                JsonNode product = use.path("product");
                JsonNode price = product.path("price");
                if (!price.path("amount").isNumber() || !product.path("riderCategory").isNull()
                        && !product.path("riderCategory").isMissingNode()) continue;
                boolean anyMedium = product.path("medium").isNull() || product.path("medium").isMissingNode();
                boolean chosenAnyMedium = chosen != null && (chosen.path("medium").isNull()
                        || chosen.path("medium").isMissingNode());
                if (chosen == null || anyMedium && !chosenAnyMedium || anyMedium == chosenAnyMedium
                        && price.path("amount").asDouble() > chosen.path("price").path("amount").asDouble()) {
                    chosen = product;
                    useId = use.path("id").asText();
                }
            }
            if (chosen == null) return List.of();
            String code = chosen.path("price").path("currency").path("code").asText(null);
            if (code == null || currency != null && !currency.equals(code)) return List.of();
            currency = code;
            byUse.putIfAbsent(useId, java.math.BigDecimal.valueOf(chosen.path("price").path("amount").asDouble()));
        }
        if (byUse.isEmpty()) return List.of();
        java.math.BigDecimal total = byUse.values().stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return List.of(new com.shg.trip.shgtrip.domain.routing.Fare(total, currency,
                com.shg.trip.shgtrip.domain.routing.Fare.Kind.TICKET));
    }

    /** OTP 노선 id는 "피드id:노선id"다. */
    private boolean unverified(String routeId, String operator) {
        int sep = routeId.indexOf(':');
        return sep > 0 && settings.unverifiedFeeds().contains(routeId.substring(0, sep))
                || settings.unverifiedAgencies().contains(operator);
    }

    /** 예측 시각이 있으면 그것을, 없으면 시간표 시각을 쓴다. */
    private static OffsetDateTime time(JsonNode place) {
        String estimated = place.path("estimated").path("time").asText(null);
        return OffsetDateTime.parse(estimated != null ? estimated : place.path("scheduledTime").asText());
    }

    private static GeoPoint point(JsonNode place) {
        return new GeoPoint(place.path("lat").asDouble(), place.path("lon").asDouble());
    }

    static LegMode mode(String otp) {
        return switch (otp) {
            case "WALK" -> LegMode.WALK;
            case "BICYCLE", "SCOOTER" -> LegMode.BICYCLE;
            case "CAR" -> LegMode.CAR;
            case "BUS", "COACH", "TROLLEYBUS" -> LegMode.BUS;
            case "TRAM" -> LegMode.TRAM;
            case "SUBWAY", "MONORAIL" -> LegMode.SUBWAY;
            case "RAIL" -> LegMode.RAIL;
            case "FERRY" -> LegMode.FERRY;
            case "AIRPLANE" -> LegMode.AIR;
            case "GONDOLA", "CABLE_CAR", "FUNICULAR" -> LegMode.CABLE;
            default -> LegMode.OTHER;
        };
    }

    private static String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
