package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;
import com.shg.trip.shgtrip.domain.routing.CallAllowance;
import com.shg.trip.shgtrip.domain.routing.Fare;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteEngine;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteSource;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripRoutesTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2027, 5, 19);
    private static final ZoneOffset SEOUL = ZoneOffset.ofHours(9);
    private static long nextId = 1;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-28T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private RouteEngine engine;
    private final List<RouteQuery> asked = new ArrayList<>();
    private Function<RouteQuery, RouteResult> answer;

    private final PlaceCandidate hotel = place("해운대 호텔", "Travel and Transportation > Lodging > Hotel", null,
            35.1587, 129.1604, null);
    private final PlaceCandidate museum = place("부산시립미술관", "Arts and Entertainment > Museum", "SIGHT",
            35.1667, 129.1374, null);
    private final PlaceCandidate market = place("자갈치시장", "Retail > Market", "SIGHT", 35.0966, 129.0306, null);

    @BeforeEach
    void setUp() {
        engine = mock(RouteEngine.class);
        when(engine.enabled()).thenReturn(true);
        when(engine.route(any(RouteQuery.class), any(CallAllowance.class))).thenAnswer(inv -> {
            RouteQuery q = inv.getArgument(0);
            synchronized (asked) {
                asked.add(q);
            }
            return answer.apply(q);
        });
    }

    private static PlaceCandidate place(String name, String category, String role, double lat, double lng,
                                        String hours) {
        return new PlaceCandidate(1, nextId++, name, null, category, List.of(), "Busan", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.0, null, hours, null, null, false,
                null, null, null, null, role, null, false, null, null, null, null);
    }

    private DayBuilder.Frame frame() {
        return new DayBuilder.Frame(2, WEDNESDAY, 9 * 60, 21 * 60, 22 * 60, 19 * 60 + 30, 5 * 60 + 15, hotel, null,
                null, false, false, null, false, "transit", List.of(), List.of(), Integer.MAX_VALUE, 1.0, null, "KR", 0);
    }

    /** 요청 시각에서 waitMinutes 뒤 떠나 rideMinutes 타고 walkMinutes 걷는 버스 경로. */
    private static RouteResult bus(RouteQuery q, int waitMinutes, int rideMinutes, int walkMinutes, Integer fareWon) {
        OffsetDateTime boards = q.departAt().toOffsetDateTime().plusMinutes(waitMinutes);
        OffsetDateTime alights = boards.plusMinutes(rideMinutes);
        OffsetDateTime arrives = alights.plusMinutes(walkMinutes);
        List<RouteLeg> legs = List.of(
                new RouteLeg(LegMode.BUS, "해운대", "시립미술관", q.origin(), q.destination(), boards, alights,
                        rideMinutes * 60, 3000, "1003", "부산버스", false),
                new RouteLeg(LegMode.WALK, "시립미술관", null, q.destination(), q.destination(), alights, arrives,
                        walkMinutes * 60, 300, null, null, false));
        RouteSource source = new RouteSource("otp-kr", RouteBasis.GTFS_SCHEDULE, true, "TAGO",
                Instant.parse("2026-11-28T00:00:00Z"), null,
                new RouteSource.ScheduleValidity(null, Instant.parse("2027-06-30T00:00:00Z"), true, WEDNESDAY));
        List<Fare> fares = fareWon == null ? List.of() : List.of(new Fare(BigDecimal.valueOf(fareWon), "KRW",
                Fare.Kind.TICKET));
        return RouteResult.found("otp-kr", List.of(new RouteItinerary(q.departAt().toOffsetDateTime(), arrives,
                (int) java.time.Duration.between(q.departAt().toOffsetDateTime(), arrives).toSeconds(), 0, legs, fares,
                source))).withAttempts(List.of(new com.shg.trip.shgtrip.domain.routing.ProviderAttempt("otp-kr",
                RouteStatus.FOUND, null, false, "2026-11-27T18:01:47")));
    }

    private static RouteResult taxi(RouteQuery q, int minutes, int fareWon) {
        OffsetDateTime at = q.departAt().toOffsetDateTime();
        RouteLeg leg = new RouteLeg(LegMode.TAXI, null, null, q.origin(), q.destination(), at, at.plusMinutes(minutes),
                minutes * 60, 4200, null, null, false);
        return RouteResult.found("valhalla", List.of(new RouteItinerary(at, at.plusMinutes(minutes), minutes * 60, 0,
                List.of(leg), List.of(new Fare(BigDecimal.valueOf(fareWon), "KRW", Fare.Kind.TAXI_ESTIMATE)),
                new RouteSource("valhalla", RouteBasis.ROAD_NETWORK, false, "OSM", null, null))));
    }

    private TripRoutes.Day routeDay(List<DayBuilder.Stop> stops) {
        DayBuilder.Frame f = frame();
        return new TripRoutes(engine, clock).session("KR").route(f, DayBuilder.build(f, stops));
    }

    private static DayBuilder.Stop sight(PlaceCandidate p) {
        return new DayBuilder.Stop(p, "sight", "morning", List.of(), true);
    }

    private static StepData stepOf(DayBuilder.Built built, PlaceCandidate p) {
        return built.steps().stream().filter(s -> s.place().name().equals(p.name())).findFirst().orElseThrow();
    }

    @Test
    void 대중교통_경로를_받으면_기다리는_시간까지_이동_시간으로_쓰고_근거를_남긴다() {
        answer = q -> bus(q, 12, 23, 5, 1550);

        DayBuilder.Built built = routeDay(List.of(sight(museum))).built();

        StepData step = stepOf(built, museum);
        assertThat(step.transportationMode()).isEqualTo("BUS");
        assertThat(step.transportationDuration()).isEqualTo(40);
        assertThat(step.startTime()).isEqualTo("09:40");
        assertThat(step.transportationCost()).isEqualByComparingTo("1550");
        TransportRoute r = step.transportationRoute();
        assertThat(r.confidence()).isEqualTo("GTFS_SCHEDULE");
        assertThat(r.provider()).isEqualTo("otp-kr");
        assertThat(r.dataVersion()).isEqualTo("2026-11-27T18:01:47");
        assertThat(r.waitMinutes()).isEqualTo(12);
        assertThat(r.departAt()).isEqualTo("2027-05-19T09:00+09:00");
        assertThat(r.validUntil()).isEqualTo("2027-06-30T00:00:00Z");
        assertThat(r.fare().basis()).as("GTFS 요금표로 낸 운임").isEqualTo("PUBLIC_DATA");
        assertThat(r.fare().per()).isEqualTo(TransportRoute.Fare.PERSON);
        assertThat(r.fare().currency()).isEqualTo("KRW");
        assertThat(r.legs()).extracting(TransportRoute.Leg::line).containsExactly("1003", null);
        assertThat(r.estimated()).isFalse();
    }

    @Test
    void 다시_맞춘_뒤_떠나는_시각으로_다음_구간을_묻고_저장한_출발_시각이_앞_스텝_끝과_같다() {
        answer = q -> bus(q, 10, 50, 5, null);

        DayBuilder.Built built = routeDay(List.of(sight(museum), sight(market))).built();

        List<StepData> steps = built.steps();
        for (int i = 1; i < steps.size(); i++) {
            TransportRoute r = steps.get(i).transportationRoute();
            assertThat(r).isNotNull();
            assertThat(r.departAt().substring(11, 16)).isEqualTo(steps.get(i - 1).endTime());
            assertThat(r.confidence()).isEqualTo("GTFS_SCHEDULE");
        }
        assertThat(stepOf(built, museum).startTime()).isEqualTo("10:05");
    }

    /** 걷는 경로. 도로망 경로라 출발 시각과 상관없이 같은 시간이 걸린다. */
    private static RouteResult walk(RouteQuery q, int minutes) {
        OffsetDateTime at = q.departAt().toOffsetDateTime();
        RouteLeg leg = new RouteLeg(LegMode.WALK, null, null, q.origin(), q.destination(), at, at.plusMinutes(minutes),
                minutes * 60, 900, null, null, false);
        return RouteResult.found("valhalla", List.of(new RouteItinerary(at, at.plusMinutes(minutes), minutes * 60, 0,
                List.of(leg), List.of(), new RouteSource("valhalla", RouteBasis.ROAD_NETWORK, false, "OSM", null, null))));
    }

    @Test
    void 다시_쓴_도보_구간도_근거의_출발_시각은_그_구간이_떠나는_시각이다() {
        PlaceCandidate gallery = place("가까운 전시관", "Arts and Entertainment > Museum", "SIGHT", 35.1600, 129.1590, null);
        PlaceCandidate beach = place("해변 산책로", "Arts and Entertainment > Museum", "SIGHT", 35.1590, 129.1630, null);
        answer = q -> walk(q, 25);
        DayBuilder.Frame f = new DayBuilder.Frame(2, WEDNESDAY, 9 * 60, 21 * 60, 22 * 60, 19 * 60 + 30, 5 * 60 + 15,
                hotel, null, hotel, false, false, null, false, "walk", List.of(), List.of(), Integer.MAX_VALUE, 1.0,
                null, "KR", 0);
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");

        DayBuilder.Built built = session.route(f, DayBuilder.build(f, List.of(sight(gallery), sight(beach)))).built();

        List<StepData> steps = built.steps();
        for (int i = 1; i < steps.size(); i++) {
            TransportRoute r = steps.get(i).transportationRoute();
            assertThat(r.departAt().substring(11, 16)).as(steps.get(i).place().name()).isEqualTo(steps.get(i - 1).endTime());
            assertThat(r.arriveAt().substring(11, 16)).isEqualTo(steps.get(i).startTime());
        }
    }

    @Test
    void 다른_시각에_확인한_근거만_남은_구간은_떠나는_시각에_맞춰_추정으로_낮춘다() {
        answer = q -> bus(q, 5, 20, 5, null);
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");
        DayBuilder.Frame f = frame();
        DayBuilder.Built built = session.route(f, DayBuilder.build(f, List.of(sight(museum)))).built();
        DayBuilder.Hop hop = built.hops().get(0);
        DayBuilder.Hop later = new DayBuilder.Hop(hop.from(), hop.to(), hop.departMin() + 15,
                new TransportLeg(hop.leg().mode(), 30, hop.leg().distanceKm(), hop.leg().cost(),
                        stepOf(built, museum).transportationRoute()), hop.step());
        java.util.Map<DayBuilder.Hop, TransportLeg> noAnswer = new java.util.HashMap<>();
        noAnswer.put(later, null);

        TransportRoute r = session.settled(f, built, noAnswer).steps().get(later.step()).transportationRoute();

        assertThat(r.departAt()).isEqualTo("2027-05-19T09:15+09:00");
        assertThat(r.arriveAt()).isEqualTo("2027-05-19T09:45+09:00");
        assertThat(r.estimated()).isTrue();
        assertThat(r.note()).isEqualTo("departure moved after check");
    }

    private DayBuilder.Frame anyModeFrame() {
        return new DayBuilder.Frame(2, WEDNESDAY, 9 * 60, 21 * 60, 22 * 60, 19 * 60 + 30, 5 * 60 + 15, hotel, null,
                null, false, false, null, false, "any", List.of(), List.of(), Integer.MAX_VALUE, 1.0, null, "KR", 0);
    }

    @Test
    void 수단을_정하지_않은_여행은_그_시각_대중교통이_없으면_택시로_바꾸고_이유를_남긴다() {
        answer = q -> q.mode() == TravelMode.TRANSIT
                ? RouteResult.of(RouteStatus.NO_CONNECTION, "otp-kr", "no itinerary after last bus")
                : taxi(q, 14, 9200);
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");
        DayBuilder.Frame f = anyModeFrame();

        DayBuilder.Built built = session.route(f, DayBuilder.build(f, List.of(sight(market)))).built();

        StepData step = stepOf(built, market);
        assertThat(step.transportationMode()).isEqualTo("TAXI");
        assertThat(step.transportationDuration()).isEqualTo(14);
        assertThat(step.transportationCost()).isEqualByComparingTo("9200");
        assertThat(step.transportationRoute().note()).startsWith("no transit connection");
        assertThat(session.notes()).anyMatch(n -> n.contains("택시로 계산"));
        assertThat(session.stats().summary()).containsEntry("taxiFallbacks", 1L);
    }

    @Test
    void 대중교통만_다니는_여행은_편이_없는_장소를_그_시각에_넣지_않고_택시로_바꾸지_않는다() {
        PlaceCandidate later = place("저녁 전망대", "Arts and Entertainment > Museum", "SIGHT", 35.1700, 129.1400, null);
        answer = q -> q.mode() != TravelMode.TRANSIT ? taxi(q, 14, 9200)
                : q.destination().lat() == 35.17 ? RouteResult.of(RouteStatus.NO_CONNECTION, "otp-kr", "no itinerary")
                : bus(q, 5, 20, 5, null);
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");
        DayBuilder.Frame f = frame();

        DayBuilder.Built built = session.route(f, DayBuilder.build(f, List.of(sight(museum), sight(later)))).built();

        assertThat(built.steps()).noneMatch(s -> "TAXI".equals(s.transportationMode()));
        assertThat(asked).noneMatch(q -> q.mode() == TravelMode.TAXI);
        assertThat(built.missed()).extracting(DayBuilder.Drop::reason).containsExactly(DayBuilder.NO_TRANSIT);
        assertThat(built.missed().get(0).cause()).isEqualTo(DraftItineraryPlanner.MissCause.TRANSIT);
    }

    @Test
    void 대중교통만_다니는_여행의_숙소_구간에_편이_없으면_요청하지_않은_장소부터_덜어_더_일찍_떠난다() {
        PlaceCandidate spare = place("들러도 되는 곳", "Arts and Entertainment > Museum", "SIGHT", 35.1650, 129.1380, null);
        answer = q -> {
            int at = q.departAt().getHour() * 60 + q.departAt().getMinute();
            boolean toHotel = q.destination().lat() == hotel.latitude().doubleValue();
            if (toHotel && at > 12 * 60) return RouteResult.of(RouteStatus.NO_CONNECTION, "otp-kr", "no itinerary");
            return bus(q, 5, 20, 5, null);
        };
        DayBuilder.Frame f = new DayBuilder.Frame(2, WEDNESDAY, 9 * 60, 21 * 60, 22 * 60, 19 * 60 + 30, 5 * 60 + 15,
                hotel, null, hotel, false, false, null, false, "transit", List.of(), List.of(), Integer.MAX_VALUE, 1.0,
                null, "KR", 0);
        List<DayBuilder.Stop> stops = List.of(sight(museum),
                new DayBuilder.Stop(spare, "sight", "afternoon", List.of(), false));

        DayBuilder.Built built = new TripRoutes(engine, clock).session("KR").route(f, DayBuilder.build(f, stops))
                .built();

        assertThat(built.placed()).contains(museum).doesNotContain(spare);
        assertThat(built.drops()).anyMatch(d -> d.stop().place() == spare && DayBuilder.NO_TRANSIT.equals(d.reason()));
        StepData lodging = built.steps().get(built.steps().size() - 1);
        assertThat(lodging.transportationRoute().unreachable()).isFalse();
    }

    @Test
    void 대중교통이_택시보다_두_배_넘게_걸리면_택시로_가고_대중교통만_다니는_여행은_그대로_둔다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? bus(q, 40, 70, 10, null) : taxi(q, 25, 18000);
        DayBuilder.Frame any = new DayBuilder.Frame(2, WEDNESDAY, 9 * 60, 21 * 60, 22 * 60, 19 * 60 + 30, 5 * 60 + 15,
                hotel, null, null, false, false, null, false, "any", List.of(), List.of(), Integer.MAX_VALUE, 1.0,
                null, "KR", 0);

        StepData taxiStep = stepOf(new TripRoutes(engine, clock).session("KR")
                .route(any, DayBuilder.build(any, List.of(sight(market)))).built(), market);
        StepData busStep = stepOf(routeDay(List.of(sight(market))).built(), market);

        assertThat(taxiStep.transportationMode()).isEqualTo("TAXI");
        assertThat(taxiStep.transportationDuration()).isEqualTo(25);
        assertThat(taxiStep.transportationRoute().note())
                .isEqualTo("transit 120 min (burden 120 with transfers and walking); taxi saves 95 min");
        assertThat(busStep.transportationMode()).as("대중교통만 다니는 여행").isEqualTo("BUS");
        assertThat(busStep.transportationDuration()).isEqualTo(120);
    }

    @Test
    void 첫차까지_두_시간_넘게_기다리면_수단을_정하지_않은_여행만_택시로_가고_대중교통_여행은_갈_수_없다고_표시한다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? bus(q, 200, 20, 5, null) : taxi(q, 15, 11000);
        DayBuilder.Frame any = anyModeFrame();

        StepData byTaxi = stepOf(new TripRoutes(engine, clock).session("KR")
                .route(any, DayBuilder.build(any, List.of(sight(market)))).built(), market);
        DayBuilder.Built transitOnly = routeDay(List.of(sight(museum))).built();

        assertThat(byTaxi.transportationMode()).isEqualTo("TAXI");
        assertThat(byTaxi.transportationRoute().note()).isEqualTo("transit waits 200 min");
        assertThat(transitOnly.placed()).doesNotContain(museum);
        assertThat(transitOnly.missed()).extracting(DayBuilder.Drop::reason).containsExactly(DayBuilder.NO_TRANSIT);
    }

    @Test
    void 택시가_빨라도_하루_예산_몫을_넘는_요금이면_대중교통으로_간다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? bus(q, 40, 70, 10, null) : taxi(q, 25, 18000);
        DayBuilder.Frame any = anyModeFrame();

        StepData step = stepOf(new TripRoutes(engine, clock).session("KR", BigDecimal.valueOf(100_000), 1, null)
                .route(any, DayBuilder.build(any, List.of(sight(market)))).built(), market);

        assertThat(step.transportationMode()).as("하루 10만 원의 15% = 15,000원 < 18,000원").isEqualTo("BUS");
    }

    @Test
    void 대중교통으로_물었는데_걷기만_오래_나오면_택시와_견주되_대중교통_여행은_장소를_빼지_않고_추정으로_둔다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? walk(q, 137) : taxi(q, 20, 15000);
        DayBuilder.Frame any = anyModeFrame();

        StepData byTaxi = stepOf(new TripRoutes(engine, clock).session("KR")
                .route(any, DayBuilder.build(any, List.of(sight(market)))).built(), market);
        DayBuilder.Built transitOnly = routeDay(List.of(sight(market))).built();

        assertThat(byTaxi.transportationMode()).isEqualTo("TAXI");
        assertThat(byTaxi.transportationRoute().note()).startsWith("only walking 137 min");
        assertThat(transitOnly.placed()).contains(market);
        StepData kept = stepOf(transitOnly, market);
        assertThat(kept.transportationMode()).isEqualTo("BUS");
        assertThat(kept.transportationRoute().unreachable()).isFalse();
        assertThat(kept.transportationRoute().status()).isEqualTo("NOT_COVERED");
    }

    @Test
    void 거리_추정보다_세_배_넘게_돌아가는_경로는_노선_데이터_공백으로_보고_추정_시간을_쓴다() {
        answer = q -> bus(q, 5, 220, 5, null);
        DayBuilder.Frame f = frame();
        DayBuilder.Built estimate = DayBuilder.build(f, List.of(sight(market)));
        int estimated = stepOf(estimate, market).transportationDuration();

        StepData step = stepOf(new TripRoutes(engine, clock).session("KR").route(f, estimate).built(), market);

        assertThat(step.transportationDuration()).isEqualTo(estimated);
        assertThat(step.transportationRoute().estimated()).isTrue();
        assertThat(step.transportationRoute().note()).contains("transit data likely missing");
        assertThat(step.transportationRoute().legs()).isEmpty();
    }

    /** 요청 시각에서 waitMinutes 뒤 떠나 rideMinutes 타는 배. */
    private static RouteResult ferry(RouteQuery q, int waitMinutes, int rideMinutes) {
        OffsetDateTime boards = q.departAt().toOffsetDateTime().plusMinutes(waitMinutes);
        OffsetDateTime arrives = boards.plusMinutes(rideMinutes);
        RouteLeg leg = new RouteLeg(LegMode.FERRY, "항구", "섬", q.origin(), q.destination(), boards, arrives,
                rideMinutes * 60, 12000, "추자 항로", "여객선", false);
        return RouteResult.found("otp-kr", List.of(new RouteItinerary(q.departAt().toOffsetDateTime(), arrives,
                (int) java.time.Duration.between(q.departAt().toOffsetDateTime(), arrives).toSeconds(), 0, List.of(leg),
                List.of(), new RouteSource("otp-kr", RouteBasis.UNVERIFIED_SCHEDULE, true, "KTDB", null, null))));
    }

    @Test
    void 배는_하루_몇_편이라_오래_기다려도_운행_끊김으로_보지_않고_택시로_바꾸지_않는다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? ferry(q, 150, 60) : taxi(q, 5, 9000);

        StepData step = stepOf(new TripRoutes(engine, clock).session("KR")
                .route(anyModeFrame(), DayBuilder.build(anyModeFrame(), List.of(sight(market)))).built(), market);

        assertThat(step.transportationMode()).isEqualTo("FERRY");
        assertThat(step.transportationDuration()).isEqualTo(210);
        assertThat(asked).noneMatch(q -> q.mode() == TravelMode.TAXI);
    }

    @Test
    void 바다_건너_엉터리로_빠른_택시_답은_대신하지_않고_그_시각_갈_수_없다고_표시한다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? RouteResult.of(RouteStatus.NO_CONNECTION, "otp-kr", "none")
                : taxi(q, 2, 4800);

        DayBuilder.Built built = new TripRoutes(engine, clock).session("KR")
                .route(anyModeFrame(), DayBuilder.build(anyModeFrame(), List.of(sight(market)))).built();

        assertThat(built.steps()).noneMatch(x -> "TAXI".equals(x.transportationMode()));
        assertThat(built.missed()).extracting(DayBuilder.Drop::reason).containsExactly(DayBuilder.NO_TRANSIT);
    }

    @Test
    void 저녁_식당_뒤_숙소_구간에_편이_없으면_관광지를_덜지_않고_충돌로_남긴다() {
        PlaceCandidate dinner = place("저녁 식당", "Dining and Drinking > Restaurant > Korean Restaurant", "MEAL",
                35.1700, 129.1300, null);
        answer = q -> {
            int at = q.departAt().getHour() * 60 + q.departAt().getMinute();
            boolean toHotel = q.destination().lat() == hotel.latitude().doubleValue();
            if (toHotel && at > 18 * 60) return RouteResult.of(RouteStatus.NO_CONNECTION, "otp-kr", "no itinerary");
            return bus(q, 5, 20, 5, null);
        };
        DayBuilder.Frame f = new DayBuilder.Frame(2, WEDNESDAY, 9 * 60, 21 * 60, 22 * 60, 19 * 60 + 30, 5 * 60 + 15,
                hotel, null, hotel, false, false, null, false, "transit", List.of(), List.of(), Integer.MAX_VALUE, 1.0,
                null, "KR", 0);
        List<DayBuilder.Stop> stops = List.of(new DayBuilder.Stop(museum, "sight", "morning", List.of(), false),
                new DayBuilder.Stop(market, "sight", "afternoon", List.of(), false),
                new DayBuilder.Stop(dinner, "meal", "dinner", List.of(), false));

        DayBuilder.Built built = new TripRoutes(engine, clock).session("KR").route(f, DayBuilder.build(f, stops))
                .built();

        assertThat(built.placed()).contains(museum, market, dinner);
        assertThat(built.drops()).noneMatch(d -> DayBuilder.NO_TRANSIT.equals(d.reason()));
        StepData lodging = built.steps().get(built.steps().size() - 1);
        assertThat(lodging.transportationRoute().unreachable()).isTrue();
        assertThat(TripPlanner.unreachableLegs(built.steps())).singleElement().asString().contains("대중교통 편이 없어요");
    }

    @Test
    void 갈_수_없다는_답은_물어_본_출발_시각_둘레에서만_쓴다() {
        DayBuilder.Frame f = frame();
        TransportRoute askedAtNine = new TransportRoute("UNAVAILABLE", null, "NO_CONNECTION", "otp-kr", null, null,
                null, "2027-05-19T09:00+09:00", null, null, null, List.of(), null, null, null, null, null, true);
        TransportLeg leg = new TransportLeg("BUS", 20, BigDecimal.ONE, BigDecimal.ZERO, askedAtNine);

        assertThat(f.unreachableAt(leg, 9 * 60 + 20)).isTrue();
        assertThat(f.unreachableAt(leg, 10 * 60)).isFalse();
        assertThat(f.unreachableAt(leg, 8 * 60 + 20)).isFalse();
    }

    @Test
    void 시간표로_확인한_허브_구간은_출발_여유를_두지_않고_추정_구간만_여유를_둔다() {
        TransportRoute timetable = new TransportRoute("GTFS_SCHEDULE", "GTFS_SCHEDULE", "FOUND", "otp-jp-kyushu", null,
                null, null, null, null, 5, 1, List.of(), null, null, null, null);
        TransportRoute road = new TransportRoute("ESTIMATED", "ROAD_NETWORK", "FOUND", "valhalla", null, null, null,
                null, null, null, 0, List.of(), null, null, null, null);

        assertThat(TripHubs.legSlack(248, timetable)).isZero();
        assertThat(TripHubs.legSlack(248, road)).isEqualTo(TripHubs.estimatedLegSlack(248)).isPositive();
        assertThat(TripHubs.legSlack(248, null)).isEqualTo(TripHubs.estimatedLegSlack(248));
        assertThat(TripHubs.latestLeaveOn(12 * 60, new TransportLeg("TRAIN", 248, BigDecimal.TEN, null, timetable)))
                .isEqualTo(12 * 60 - 248);
    }

    @Test
    void 데이터가_없는_지역은_거리_추정을_그대로_두고_추정이라고_적는다() {
        answer = q -> RouteResult.of(RouteStatus.NOT_COVERED, null, "no provider for area and mode");
        DayBuilder.Frame f = frame();
        DayBuilder.Built estimate = DayBuilder.build(f, List.of(sight(museum)));

        DayBuilder.Built built = new TripRoutes(engine, clock).session("KR").route(f, estimate).built();

        StepData step = stepOf(built, museum);
        assertThat(step.transportationDuration()).isEqualTo(stepOf(estimate, museum).transportationDuration());
        assertThat(step.startTime()).isEqualTo(stepOf(estimate, museum).startTime());
        assertThat(step.transportationRoute().status()).isEqualTo("NOT_COVERED");
        assertThat(step.transportationRoute().estimated()).isTrue();
    }

    @Test
    void 늦어진_도착으로_문_닫는_시각을_넘기면_그_장소를_빼고_이유를_남긴다() {
        PlaceCandidate shortHours = place("오전만 여는 전시관", "Arts and Entertainment > Museum", "SIGHT",
                35.1667, 129.1374, "수요일: 오전 9:00 ~ 오전 10:30");
        answer = q -> bus(q, 10, 40, 5, null);

        DayBuilder.Built built = routeDay(List.of(sight(shortHours))).built();

        assertThat(built.placed()).doesNotContain(shortHours);
        assertThat(built.missed()).extracting(d -> d.stop().place().name()).containsExactly("오전만 여는 전시관");
    }

    @Test
    void 같은_구간_같은_출발_시각은_일정_안에서_한_번만_묻는다() {
        answer = q -> bus(q, 5, 20, 5, null);
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");
        DayBuilder.Frame f = frame();
        TripRoutes.Day first = session.route(f, DayBuilder.build(f, List.of(sight(museum))));
        int calls = asked.size();

        session.route(first.frame(), DayBuilder.build(first.frame(), first.built().stops(), true));

        assertThat(asked).hasSize(calls);
        assertThat((Long) session.stats().summary().get("memoHits")).isPositive();
    }

    @Test
    void 경로_엔진이_꺼져_있으면_거리_추정으로_지은_하루를_그대로_쓴다() {
        when(engine.enabled()).thenReturn(false);
        DayBuilder.Frame f = frame();
        DayBuilder.Built estimate = DayBuilder.build(f, List.of(sight(museum)));

        TripRoutes.Day day = new TripRoutes(engine, clock).session("KR").route(f, estimate);

        assertThat(day.built()).isSameAs(estimate);
        verify(engine, never()).route(any(RouteQuery.class), any(CallAllowance.class));
    }

    @Test
    void 경로_조회에_쓴_시간이_상한에_닿으면_남은_날은_묻지_않고_추정으로_둔다() {
        answer = q -> {
            now.set(now.get().plus(TripRoutes.TRIP_BUDGET).plusSeconds(1));
            return bus(q, 5, 20, 5, null);
        };
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");
        DayBuilder.Frame f = frame();
        session.route(f, DayBuilder.build(f, List.of(sight(museum))));
        int calls = asked.size();

        DayBuilder.Built nextDay = session.route(f, DayBuilder.build(f, List.of(sight(market)))).built();

        assertThat(asked).hasSize(calls);
        assertThat(stepOf(nextDay, market).transportationRoute().note()).isEqualTo("trip route budget exceeded");
    }

    @Test
    void 장소를_고르는_시간은_조회_상한에_넣지_않는다() {
        answer = q -> bus(q, 5, 20, 5, null);
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");
        now.set(now.get().plus(TripRoutes.TRIP_BUDGET).plusSeconds(1));
        DayBuilder.Frame f = frame();

        DayBuilder.Built built = session.route(f, DayBuilder.build(f, List.of(sight(museum)))).built();

        assertThat(asked).isNotEmpty();
        assertThat(stepOf(built, museum).transportationRoute().confidence()).isEqualTo("GTFS_SCHEDULE");
    }

    @Test
    void 도시_간_구간은_수단_이름을_두고_실제로_탄_수단은_근거에_둔다() {
        RouteQuery q = new RouteQuery(new GeoPoint(37.55, 126.97), new GeoPoint(35.11, 129.04),
                WEDNESDAY.atStartOfDay(SEOUL).plusHours(9), TravelMode.TRANSIT);
        RouteItinerary highwayBus = bus(q, 10, 260, 5, null).itineraries().get(0);

        assertThat(TripRoutes.modeOf("TRAIN", TravelMode.TRANSIT, highwayBus)).isEqualTo("TRAIN");
        assertThat(TripRoutes.modeOf("BUS", TravelMode.TRANSIT, highwayBus)).isEqualTo("BUS");
        assertThat(TripRoutes.modeOf("CAR", TravelMode.CAR, highwayBus)).as("차 경로에 낀 배").isEqualTo("CAR");
        assertThat(TripRoutes.travelMode("FLIGHT")).isNull();
        assertThat(TripRoutes.travelMode("DRIVE")).isEqualTo(TravelMode.CAR);
    }

    private static RouteResult taxiTable(RouteQuery q, int minutes, Integer fareWon) {
        OffsetDateTime at = q.departAt().toOffsetDateTime();
        RouteLeg leg = new RouteLeg(LegMode.TAXI, null, null, q.origin(), q.destination(), at, at.plusMinutes(minutes),
                minutes * 60, 9000, null, null, false);
        List<Fare> fares = fareWon == null ? List.of()
                : List.of(new Fare(BigDecimal.valueOf(fareWon), "KRW", Fare.Kind.TAXI_TABLE));
        return RouteResult.found("valhalla", List.of(new RouteItinerary(at, at.plusMinutes(minutes), minutes * 60, 0,
                List.of(leg), fares, new RouteSource("valhalla", RouteBasis.ROAD_NETWORK, false, "OSM", null, null))));
    }

    private StepData marketStep(Integer partySize, BigDecimal dayBudget) {
        DayBuilder.Frame any = anyModeFrame();
        return stepOf(new TripRoutes(engine, clock).session("KR", dayBudget, 1, partySize)
                .route(any, DayBuilder.build(any, List.of(sight(market)))).built(), market);
    }

    @Test
    void 택시가_더_드는_금액은_일행_전체로_잰다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? bus(q, 40, 70, 10, 1500) : taxiTable(q, 25, 18000);

        assertThat(marketStep(1, BigDecimal.valueOf(100_000)).transportationMode())
                .as("혼자면 택시가 16,500원 더 들어 하루 몫 15,000원을 넘는다").isEqualTo("BUS");
        assertThat(marketStep(4, BigDecimal.valueOf(100_000)).transportationMode())
                .as("넷이면 택시 한 대 18,000원과 버스 6,000원의 차가 12,000원이다").isEqualTo("TAXI");
    }

    @Test
    void 예산이_있는데_택시_요금을_모르면_택시로_바꾸지_않는다() {
        answer = q -> q.mode() == TravelMode.TRANSIT ? bus(q, 40, 70, 10, 1500) : taxiTable(q, 25, null);

        assertThat(marketStep(2, BigDecimal.valueOf(500_000)).transportationMode()).isEqualTo("BUS");
        assertThat(marketStep(2, null).transportationMode()).as("예산을 모르면 시간으로만 견준다").isEqualTo("TAXI");
    }

    @Test
    void 요금_데이터가_없는_대중교통_구간은_금액을_지어내지_않고_모름으로_둔다() {
        answer = q -> bus(q, 5, 20, 5, null);

        StepData step = stepOf(routeDay(List.of(sight(museum))).built(), museum);

        assertThat(step.transportationCost()).isNull();
        assertThat(step.transportationRoute().fare().basis()).isEqualTo(TransportRoute.Fare.UNKNOWN);
        assertThat(step.transportationRoute().fare().known()).isFalse();
        assertThat(step.transportationRoute().confidence()).as("경로 근거는 요금과 따로다").isEqualTo("GTFS_SCHEDULE");
    }

    @Test
    void 심야_택시는_요금표에_할증이_없어_추정으로_낮추고_빠진_항목을_적는다() {
        answer = q -> q.mode() == TravelMode.TRANSIT
                ? RouteResult.of(RouteStatus.NO_CONNECTION, "otp-kr", "after last bus") : taxiTable(q, 20, 12000);
        TransportLeg estimate = new TransportLeg("BUS", 45, BigDecimal.valueOf(13), BigDecimal.valueOf(1500));

        TransportLeg late = new TripRoutes(engine, clock).session("KR").legAt(WEDNESDAY, hotel, market, 23 * 60,
                estimate, true);

        assertThat(late.mode()).isEqualTo("TAXI");
        TransportRoute.Fare fare = late.route().fare();
        assertThat(fare.basis()).isEqualTo("ESTIMATED");
        assertThat(fare.per()).isEqualTo(TransportRoute.Fare.VEHICLE);
        assertThat(fare.excludes()).contains("NIGHT_SURCHARGE", "TIME_CHARGE", "TOLL");
        assertThat(late.cost()).isEqualByComparingTo("12000");
    }

    @Test
    void 대중교통_경로_안에_긴_걷기가_끼면_노선_데이터_공백으로_보고_추정을_쓴다() {
        answer = q -> {
            OffsetDateTime at = q.departAt().toOffsetDateTime();
            List<RouteLeg> legs = List.of(
                    new RouteLeg(LegMode.RAIL, "가", "나", q.origin(), q.destination(), at.plusMinutes(10),
                            at.plusMinutes(40), 1800, 30000, "1", "철도", false),
                    new RouteLeg(LegMode.WALK, "나", null, q.destination(), q.destination(), at.plusMinutes(40),
                            at.plusMinutes(100), 3600, 4500, null, null, false));
            return RouteResult.found("otp-kr", List.of(new RouteItinerary(at, at.plusMinutes(100), 6000, 0, legs,
                    List.of(), new RouteSource("otp-kr", RouteBasis.GTFS_SCHEDULE, false, "t", null, null))));
        };
        TransportLeg estimate = new TransportLeg("BUS", 40, BigDecimal.valueOf(13), BigDecimal.valueOf(1500));

        TransportLeg leg = new TripRoutes(engine, clock).session("KR").legAt(WEDNESDAY, hotel, market, 10 * 60,
                estimate, false);

        assertThat(leg.durationMin()).isEqualTo(40);
        assertThat(leg.route().status()).isEqualTo("NOT_COVERED");
        assertThat(leg.route().note()).contains("60 min walk");
    }

    @Test
    void 긴_걷기_없이_두_배_남짓_걸리는_지방_버스는_그대로_쓴다() {
        TransportRoute route = new TransportRoute("ESTIMATED", "UNVERIFIED_SCHEDULE", "FOUND", "otp-kr", null, null,
                null, null, null, 40, 1, List.of(new TransportRoute.Leg("WALK", null, null, null, null, null, null, 8),
                new TransportRoute.Leg("BUS", "1", null, null, null, null, null, 50)), null, null, null, null);
        TransportLeg rural = new TransportLeg("BUS", 98, BigDecimal.TEN, null, route);
        TransportLeg estimate = new TransportLeg("CAR", 35, BigDecimal.TEN, null);

        assertThat(TripRoutes.Session.dataGap(rural, estimate)).isNull();
        assertThat(TripRoutes.Session.dataGap(new TransportLeg("BUS", 110, BigDecimal.TEN, null, route),
                estimate)).as("세 배를 넘으면 신호 없이도 공백이다").isNotNull();
    }

    @Test
    void 하루_시각을_정하는_숙소_구간과_요청_장소_구간을_먼저_묻는다() {
        DayBuilder.Frame f = new DayBuilder.Frame(2, WEDNESDAY, 9 * 60, 21 * 60, 22 * 60, 19 * 60 + 30, 5 * 60 + 15,
                hotel, null, hotel, false, false, null, false, "transit", List.of(), List.of(), Integer.MAX_VALUE, 1.0,
                null, "KR", 0);
        DayBuilder.Built built = DayBuilder.build(f, List.of(
                new DayBuilder.Stop(museum, "sight", "morning", List.of(), false), sight(market)));

        List<Integer> ranks = built.hops().stream().map(h -> TripRoutes.Session.rank(f, built, h)).toList();
        java.util.Map<String, Integer> byTarget = new java.util.HashMap<>();
        for (int i = 0; i < built.hops().size(); i++) byTarget.put(built.hops().get(i).to().name(), ranks.get(i));

        assertThat(byTarget).containsEntry(hotel.name(), 0).containsEntry(market.name(), 1)
                .containsEntry(museum.name(), 2);
    }

    @Test
    void 이어_붙인_경로는_토막마다_온_승차권을_모두_더한다() {
        answer = q -> {
            OffsetDateTime at = q.departAt().toOffsetDateTime();
            List<RouteLeg> legs = List.of(new RouteLeg(LegMode.BUS, "가", "나", q.origin(), q.destination(),
                    at.plusMinutes(5), at.plusMinutes(20), 900, 4000, "1", "버스", false),
                    new RouteLeg(LegMode.RAIL, "나", "다", q.origin(), q.destination(), at.plusMinutes(25),
                            at.plusMinutes(45), 1200, 9000, "2", "철도", false));
            return RouteResult.found("otp-jp", List.of(new RouteItinerary(at, at.plusMinutes(45), 2700, 1, legs,
                    List.of(new Fare(BigDecimal.valueOf(230), "JPY", Fare.Kind.TICKET),
                            new Fare(BigDecimal.valueOf(330), "JPY", Fare.Kind.TICKET)),
                    new RouteSource("otp-jp", RouteBasis.GTFS_SCHEDULE, false, "t", null, null))));
        };
        TransportLeg estimate = new TransportLeg("BUS", 40, BigDecimal.valueOf(13), BigDecimal.valueOf(1500));

        TransportLeg leg = new TripRoutes(engine, clock).session("KR").legAt(WEDNESDAY, hotel, market, 10 * 60,
                estimate, false);

        assertThat(leg.route().fare().amount()).isEqualByComparingTo("560");
        assertThat(leg.route().fare().currency()).isEqualTo("JPY");
    }

    @Test
    void 사용자별_편집_경로_한도를_넘으면_묻지_않는다() {
        var counters = org.mockito.Mockito.mock(com.shg.trip.shgtrip.domain.routing.ProviderCallBudget.class);
        when(counters.tryAcquireQuietly(org.mockito.ArgumentMatchers.eq("edit-routes-user:7"),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(true, false);
        TripRoutes routes = new TripRoutes(engine, clock, counters);

        assertThat(routes.editRoutingAllowed(7L)).isTrue();
        assertThat(routes.editRoutingAllowed(7L)).isFalse();
        when(engine.enabled()).thenReturn(false);
        assertThat(routes.editRoutingAllowed(8L)).as("엔진이 꺼져 있으면 묻지 않는다").isFalse();
    }

    @Test
    void 받지_못한_답은_기억하지_않아_같은_구간을_다음에_다시_묻는다() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        answer = q -> n.getAndIncrement() == 0
                ? RouteResult.of(RouteStatus.UNAVAILABLE, "otp-kr", "route deadline exceeded")
                : bus(q, 5, 20, 5, null);
        TransportLeg estimate = new TransportLeg("BUS", 40, BigDecimal.valueOf(13), BigDecimal.valueOf(1500));
        TripRoutes.Session session = new TripRoutes(engine, clock).session("KR");

        TransportLeg first = session.legAt(WEDNESDAY, hotel, market, 10 * 60, estimate, false);
        TransportLeg again = session.legAt(WEDNESDAY, hotel, market, 10 * 60, estimate, false);

        assertThat(first.route().status()).isEqualTo("UNAVAILABLE");
        assertThat(again.route().status()).isEqualTo("FOUND");
        assertThat(asked).hasSize(2);
    }
}
