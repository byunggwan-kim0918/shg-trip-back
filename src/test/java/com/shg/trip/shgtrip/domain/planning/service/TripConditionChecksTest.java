package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Severity;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 여행 조건이 있는 판정 입력에서만 보는 규칙을 {@link ItineraryRules}로 확인한다. */
class TripConditionChecksTest {

    private static final LocalDate START = LocalDate.of(2027, 5, 1);
    private static final String BEACH = "Landmarks and Outdoors > Beach";
    private static final String KOREAN = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String SEAFOOD = "Dining and Drinking > Restaurant > Seafood Restaurant";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";
    private static final String STATION = "Travel and Transportation > Transport Hub > Rail Station";

    private static final PlaceCandidate BEACH_SPOT = candidate(1, "해운대해수욕장", BEACH, "SIGHT", null);
    private static final PlaceCandidate LUNCH = candidate(2, "할매국밥", KOREAN, "MEAL", null);
    private static final PlaceCandidate SASHIMI = candidate(3, "바다횟집", SEAFOOD, "MEAL", "seafood");
    private static final PlaceCandidate HOTEL_SPOT = candidate(4, "해운대호텔", HOTEL, "LODGING", null);
    private static final PlaceCandidate BUSAN_STATION = candidate(5, "부산역", STATION, "TRANSIT", null);
    private static final List<PlaceCandidate> ALL = List.of(BEACH_SPOT, LUNCH, SASHIMI, HOTEL_SPOT, BUSAN_STATION);

    private final ItineraryRules rules = new ItineraryRules();

    @Test
    @DisplayName("요구한 장소가 없으면 지킬 수 없는 위반이고, 요구한 음식이 없으면 저장을 막지 않는다")
    void placeWantIsHardFoodWantIsSoft() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH));
        TripConditions conditions = conditions(List.of(
                new TripConditions.Want("감천문화마을", null, List.of(), null),
                new TripConditions.Want("돼지국밥", null, List.of(), "돼지국밥")), null, null, List.of());

        List<Violation> out = evaluate(day, 1, conditions);

        assertThat(out).filteredOn(v -> v.rule() == Rule.WANT_MISSING).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.HARD))
                .extracting(Violation::evidence).asList().containsExactly("감천문화마을");
        assertThat(out).filteredOn(v -> v.rule() == Rule.WANT_FOOD_MISSING).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.SOFT));
    }

    @Test
    @DisplayName("이름으로만 맞는 스텝은 요구를 채운 것으로 보지 않는다")
    void wantNeedsPlaceId() {
        StepData unnamed = new StepData(1, 1, "10:00", "11:30",
                new PlaceData("해운대해수욕장", null, BEACH, "Busan", "KR"), List.of(), null, null, null, null, null, null);
        TripConditions conditions = conditions(List.of(new TripConditions.Want("해운대해수욕장", null, List.of(), null)),
                null, null, List.of());

        assertThat(evaluate(List.of(unnamed), 1, conditions)).extracting(Violation::rule).contains(Rule.WANT_MISSING);
    }

    @Test
    @DisplayName("조건이 있으면 늦게 도착한 첫날의 빈 날은 위반이 아니고, 빈 날은 조건 규칙 하나로만 센다")
    void emptyDayUsesConditions() {
        List<StepData> secondDayOnly = List.of(step(1, 2, "10:00", "11:30", BEACH_SPOT));

        List<Violation> lateArrival = evaluate(secondDayOnly, 2,
                conditions(List.of(), LocalTime.of(19, 0), null, List.of()));
        List<Violation> morningArrival = evaluate(secondDayOnly, 2,
                conditions(List.of(), LocalTime.of(9, 0), null, List.of()));

        assertThat(lateArrival).extracting(Violation::rule).doesNotContain(Rule.DAY_MISSING);
        assertThat(morningArrival).filteredOn(v -> v.rule() == Rule.DAY_MISSING)
                .extracting(Violation::message).containsExactly("1일차에 일정이 없음");
    }

    @Test
    @DisplayName("역으로 떠나는 날은 짓는 쪽과 같은 수속 여유를 두고 출발 시각을 판정한다")
    void departureUsesHubBuffer() {
        TripConditions conditions = conditions(List.of(), null, LocalTime.of(15, 0), List.of());

        assertThat(evaluate(lastDayAt("14:30"), 1, conditions)).extracting(Violation::rule)
                .doesNotContain(Rule.DEPARTURE_MISSED);
        assertThat(evaluate(lastDayAt("14:50"), 1, conditions)).extracting(Violation::rule)
                .contains(Rule.DEPARTURE_MISSED);
    }

    @Test
    @DisplayName("관광 없이 허브로 가는 출발일이 늦으면 편집으로 고칠 수 없는 출발 초과다")
    void hubOnlyDepartureDayIsChecked() {
        TripConditions conditions = conditions(List.of(), null, LocalTime.of(10, 0), List.of());
        StepData station = new StepData(1, 2, "09:29", "09:59",
                new PlaceData(BUSAN_STATION.name(), null, STATION, "Busan", "KR", null, null, 5L, null), List.of(),
                "BUS", 189, null, null, null, null);
        List<StepData> steps = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), station);

        assertThat(evaluate(steps, 2, conditions)).extracting(Violation::rule)
                .contains(Rule.DEPARTURE_UNREACHABLE).doesNotContain(Rule.DEPARTURE_MISSED);
        assertThat(Rule.DEPARTURE_UNREACHABLE.fromRequest).isTrue();
    }

    @Test
    @DisplayName("도착 허브에서 숙소까지 저녁 늦게 닿는 첫날은 착륙이 이르더라도 빈 날로 세지 않는다")
    void longTransferArrivalDayIsNotMissing() {
        StepData station = step(1, 1, "15:00", "16:00", BUSAN_STATION);
        StepData hotel = new StepData(2, 1, "21:57", "22:27",
                new PlaceData(HOTEL_SPOT.name(), null, HOTEL, "Busan", "KR", null, null, 4L, null), List.of(),
                "BUS", 357, null, null, null, null);
        StepData nearHotel = new StepData(2, 1, "16:40", "17:10",
                new PlaceData(HOTEL_SPOT.name(), null, HOTEL, "Busan", "KR", null, null, 4L, null), List.of(),
                "BUS", 40, null, null, null, null);
        List<StepData> secondDay = List.of(step(3, 2, "10:00", "11:30", BEACH_SPOT));
        TripConditions conditions = conditions(List.of(), LocalTime.of(15, 0), null, List.of());

        List<StepData> far = new ArrayList<>(List.of(station, hotel));
        far.addAll(secondDay);
        List<StepData> near = new ArrayList<>(List.of(station, nearHotel));
        near.addAll(secondDay);
        assertThat(evaluate(far, 2, conditions)).extracting(Violation::rule).doesNotContain(Rule.DAY_MISSING);
        assertThat(evaluate(near, 2, conditions)).extracting(Violation::rule).contains(Rule.DAY_MISSING);
    }

    @Test
    @DisplayName("피할 조건에 걸린 장소는 지킬 수 없는 위반이고, 읽지 못한 제약 이름은 건너뛴다")
    void avoidIgnoresUnknownRestriction() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", SASHIMI));
        List<PlaceRestriction> avoid = new ArrayList<>(Arrays.asList(PlaceRestriction.SEAFOOD, null));

        assertThat(evaluate(day, 1, conditions(List.of(), null, null, avoid)))
                .filteredOn(v -> v.rule() == Rule.AVOID).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.HARD));
    }

    @Test
    @DisplayName("출발 허브까지 추정한 이동에는 여유를 더해, 여유 없이는 빠듯한 도착을 지킬 수 있다고 보지 않는다")
    void departureAddsSlackToEstimatedLeg() {
        TripConditions conditions = conditions(List.of(), null, LocalTime.of(15, 0), List.of());
        StepData station = new StepData(3, 1, "14:35", "14:35",
                new PlaceData(BUSAN_STATION.name(), null, STATION, "Busan", "KR", null, null, 5L, null), List.of(),
                "TAXI", 40, null, null, null, null);
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH),
                station);

        assertThat(evaluate(day, 1, conditions)).filteredOn(v -> v.rule() == Rule.DEPARTURE_MISSED).singleElement()
                .satisfies(v -> assertThat(v.message()).contains("추정 이동 여유 10분"));
    }

    @Test
    @DisplayName("일정에 없는 요구는 넣을 수 있었는지·사용자가 직접 정했는지·알고 두기로 했는지로 판정한다")
    void unmetWantRuleFollowsReachAndConsent() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH));
        TripConditions conditions = conditions(List.of(
                new TripConditions.Want("나카스 야타이", null, List.of(), null, TripConditions.Reach.NOT_FOUND, true,
                        false),
                new TripConditions.Want("캐널시티 하카타", null, List.of(), null, TripConditions.Reach.NOT_FOUND, true,
                        true),
                new TripConditions.Want("광안리 야경", "evening", List.of(), null, TripConditions.Reach.NO_SLOT, true,
                        false)), null, null, List.of());

        List<Violation> out = evaluate(day, 1, conditions);

        assertThat(out).filteredOn(v -> v.rule() == Rule.REQUIRED_NOT_FOUND).singleElement().satisfies(v -> {
            assertThat(v.severity()).isEqualTo(Severity.HARD);
            assertThat(v.rule().fromRequest).isTrue();
            assertThat(v.evidence()).containsExactly("나카스 야타이");
        });
        assertThat(out).filteredOn(v -> v.rule() == Rule.WANT_ACCEPTED_UNMET).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.SOFT))
                .extracting(Violation::evidence).asList().containsExactly("캐널시티 하카타");
        assertThat(out).filteredOn(v -> v.rule() == Rule.REQUIRED_NO_SLOT).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.HARD));
        assertThat(out).extracting(Violation::rule).doesNotContain(Rule.WANT_MISSING);
    }

    @Test
    @DisplayName("요구로 넣은 곳의 영업시간을 모르면 지켰다고 보지 않고 미확인으로 남긴다. 문을 여닫지 않는 야외는 빼고 본다")
    void unknownHoursOfWantedPlaceAreUnknown() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH));
        TripConditions conditions = conditions(List.of(
                new TripConditions.Want("해운대해수욕장", null, List.of(), null),
                new TripConditions.Want("할매국밥", null, List.of(), null)), null, null, List.of());

        assertThat(evaluate(day, 1, conditions)).filteredOn(v -> v.rule() == Rule.HOURS_UNKNOWN).singleElement()
                .satisfies(v -> {
                    assertThat(v.severity()).isEqualTo(Severity.UNKNOWN);
                    assertThat(v.evidence()).containsExactly("할매국밥", "할매국밥");
                });
    }

    private static final TripConditions.Area BUSAN_AREA = new TripConditions.Area("부산", 35.18, 129.08, 20, 25);
    private static final TripConditions.Area GYEONGJU_AREA = new TripConditions.Area("경주", 35.84, 129.21, 15, 25);

    @Test
    @DisplayName("들르기로 한 지역에 활동이 하나도 없으면 지킬 수 없는 위반이고 편집 대상이다")
    void visitAreaWithoutActivityIsHard() {
        PlaceCandidate bulguksa = candidateAt(6, "불국사", BEACH, "SIGHT", 35.79, 129.33);
        List<StepData> busanOnly = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH));
        List<StepData> both = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH),
                step(3, 1, "15:00", "16:30", bulguksa));
        TripConditions conditions = layoutConditions(
                new TripConditions.Layout(List.of(BUSAN_AREA, GYEONGJU_AREA), Map.of(), List.of()));
        List<PlaceCandidate> candidates = withAll(bulguksa);

        assertThat(evaluate(busanOnly, 1, conditions, candidates)).filteredOn(v -> v.rule() == Rule.AREA_MISSING)
                .singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.HARD))
                .extracting(Violation::evidence).asList().containsExactly("경주");
        assertThat(Rule.AREA_MISSING.fromRequest).isFalse();
        assertThat(evaluate(both, 1, conditions, candidates)).extracting(Violation::rule)
                .doesNotContain(Rule.AREA_MISSING);
    }

    @Test
    @DisplayName("밤 숙소가 묵기로 한 도시 범위 밖이면 편집으로 고칠 수 없는 지킬 수 없는 위반이다")
    void lodgingOutsidePlannedCityIsHard() {
        PlaceCandidate gyeongjuHotel = candidateAt(7, "경주호텔", HOTEL, "LODGING", 35.83, 129.22);
        TripConditions conditions = layoutConditions(new TripConditions.Layout(List.of(),
                Map.of(1, GYEONGJU_AREA, 2, BUSAN_AREA), List.of()));
        List<PlaceCandidate> candidates = withAll(gyeongjuHotel);
        List<StepData> busanNight = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "21:00", "21:00",
                HOTEL_SPOT));
        List<StepData> gyeongjuNight = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "21:00", "21:00",
                gyeongjuHotel));

        assertThat(evaluate(busanNight, 3, conditions, candidates)).filteredOn(v -> v.rule() == Rule.STAY_MISMATCH)
                .singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.HARD))
                .extracting(Violation::evidence).asList().containsExactly("경주", "해운대호텔");
        assertThat(Rule.STAY_MISMATCH.fromRequest).isTrue();
        assertThat(evaluate(gyeongjuNight, 3, conditions, candidates)).extracting(Violation::rule)
                .doesNotContain(Rule.STAY_MISMATCH);
        TripConditions booked = new TripConditions(List.of(), Map.of(), List.of(), false, "transit", null, null, "normal",
                java.util.Set.of(HOTEL_SPOT.placeId()), conditions.layout());
        assertThat(evaluate(busanNight, 3, booked, candidates)).as("예약한 숙소는 저장 관문에서도 id로 따른다")
                .extracting(Violation::rule).doesNotContain(Rule.STAY_MISMATCH);
    }

    @Test
    @DisplayName("렌터카와 버스·택시를 숙소·역이 아닌 곳에서 바꾸면 안내하고, 숙소에서 바꾸거나 걸어서 잇는 것은 그대로 둔다")
    void rentalHandoffAtOrdinaryPlace() {
        PlaceCandidate lake = candidateAt(8, "아오이이케", BEACH, "SIGHT", 35.30, 129.20);
        List<PlaceCandidate> candidates = withAll(lake);
        TripConditions conditions = conditions(List.of(), null, null, List.of());
        List<StepData> handoff = List.of(step(1, 1, "09:00", "09:00", HOTEL_SPOT),
                legStep(2, "10:00", "11:00", lake, "CAR", 50, 30), legStep(3, "12:00", "13:00", LUNCH, "BUS", 50, 30),
                legStep(4, "14:00", "15:00", BEACH_SPOT, "TAXI", 10, 3));
        List<StepData> fromHotel = List.of(step(1, 1, "09:00", "09:00", HOTEL_SPOT),
                legStep(2, "10:00", "11:00", lake, "CAR", 50, 30), legStep(3, "11:10", "12:00", BEACH_SPOT, "WALK", 5, 0.3),
                legStep(4, "12:30", "13:30", LUNCH, "CAR", 40, 30));

        assertThat(evaluate(handoff, 1, conditions, candidates)).filteredOn(v -> v.rule() == Rule.RENTAL_HANDOFF)
                .singleElement().extracting(Violation::evidence).asList().containsExactly("아오이이케");
        assertThat(evaluate(fromHotel, 1, conditions, candidates)).extracting(Violation::rule)
                .doesNotContain(Rule.RENTAL_HANDOFF);
        List<StepData> driveOn = List.of(step(1, 1, "09:00", "09:00", HOTEL_SPOT),
                legStep(2, "10:00", "11:00", lake, "CAR", 50, 30), legStep(3, "13:00", "14:00", LUNCH, "DRIVE", 90, 100));
        assertThat(evaluate(driveOn, 1, conditions, candidates)).as("도시 간 자동차 구간").extracting(Violation::rule)
                .doesNotContain(Rule.RENTAL_HANDOFF);
    }

    @Test
    @DisplayName("렌터카 지역에서 떠나는 구간은 여행 전체의 대중교통 상한이 아니라 차 상한으로 잰다")
    void carAreaLegUsesCarCap() {
        PlaceCandidate farm = candidateAt(9, "팜도미타", BEACH, "SIGHT", 35.84, 129.21);
        PlaceCandidate lake = candidateAt(10, "아오이이케", BEACH, "SIGHT", 35.84, 129.56);
        List<PlaceCandidate> candidates = new ArrayList<>(withAll(farm));
        candidates.add(lake);
        List<StepData> day = List.of(step(1, 1, "10:00", "11:00", farm),
                legStep(2, "12:00", "13:00", lake, "CAR", 40, 32 * 1.3));
        TripConditions.Layout carInGyeongju = new TripConditions.Layout(List.of(), Map.of(),
                List.of(new TripConditions.AreaMode(GYEONGJU_AREA, "car")));

        assertThat(evaluate(day, 1, conditions(List.of(), null, null, List.of()), candidates))
                .extracting(Violation::rule).contains(Rule.LEG_OVER_CAP);
        assertThat(evaluate(day, 1, layoutConditions(carInGyeongju), candidates))
                .extracting(Violation::rule).doesNotContain(Rule.LEG_OVER_CAP);
    }

    private static StepData legStep(int order, String start, String end, PlaceCandidate c, String mode, int minutes,
                                    double roadKm) {
        return new StepData(order, 1, start, end,
                new PlaceData(c.name(), null, c.category(), c.region(), "KR", null, null, c.placeId(), null),
                List.of(), mode, minutes, BigDecimal.valueOf(roadKm), null, null, null);
    }

    private static TripConditions layoutConditions(TripConditions.Layout layout) {
        return new TripConditions(List.of(), Map.of(), List.of(), false, "transit", null, null, "normal",
                java.util.Set.of(), layout);
    }

    private static List<PlaceCandidate> withAll(PlaceCandidate extra) {
        List<PlaceCandidate> out = new ArrayList<>(ALL);
        out.add(extra);
        return out;
    }

    private static PlaceCandidate candidateAt(long id, String name, String category, String role, double lat,
                                              double lng) {
        return new PlaceCandidate((int) id, id, name, null, category, List.of(), "Gyeongju", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, role, null, false, null, null, null, null);
    }

    private List<StepData> lastDayAt(String stationStart) {
        return List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH),
                step(3, 1, stationStart, stationStart, BUSAN_STATION));
    }

    private List<Violation> evaluate(List<StepData> steps, int days, TripConditions conditions) {
        return evaluate(steps, days, conditions, ALL);
    }

    private List<Violation> evaluate(List<StepData> steps, int days, TripConditions conditions,
                                     List<PlaceCandidate> candidates) {
        ItineraryRules.Context ctx = new ItineraryRules.Context("normal", "any", START, days, null, Map.of(),
                conditions, "KR", List.of(new ItineraryRules.City("부산", 35.18, 129.08)));
        return rules.evaluate(steps, candidates, ctx, Map.of()).violations();
    }

    private static TripConditions conditions(List<TripConditions.Want> wants, LocalTime arrival, LocalTime departure,
                                             List<PlaceRestriction> avoid) {
        return new TripConditions(wants, Map.of(), avoid, false, "transit", arrival, departure, "normal");
    }

    private static StepData step(int order, int day, String start, String end, PlaceCandidate c) {
        return new StepData(order, day, start, end,
                new PlaceData(c.name(), null, c.category(), "Busan", "KR", null, null, c.placeId(), null),
                List.of(), null, null, null, null, null, null);
    }

    private static PlaceCandidate candidate(long id, String name, String category, String role, String cuisine) {
        return new PlaceCandidate((int) id, id, name, null, category, List.of(), "Busan", "KR",
                BigDecimal.valueOf(35.16), BigDecimal.valueOf(129.16), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, role, cuisine, false, null, null, null, null);
    }

    @Test
    @DisplayName("조건이 있는 새 일정은 첫 스텝 뒤 이동 시간이 없는 구간을 지킬 수 없는 위반으로 본다")
    void missingLegIsHardForNewItinerary() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH));
        ItineraryRules.Context old = new ItineraryRules.Context("normal", "any", START, 1, null);

        assertThat(evaluate(day, 1, conditions(List.of(), null, null, List.of())))
                .filteredOn(v -> v.rule() == Rule.TRAVEL_MISSING).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.HARD))
                .extracting(Violation::evidence).asList().containsExactly("해운대해수욕장", "할매국밥", "");
        assertThat(rules.evaluate(day, ALL, old, Map.of()).violations()).extracting(Violation::rule)
                .doesNotContain(Rule.TRAVEL_MISSING);
    }

    @Test
    @DisplayName("데이터에 없는 요구는 안내만 하고, 피할 조건과 부딪치는 요구는 짓는 쪽의 위반으로 세지 않는다")
    void wantsOutsideGeneratorReach() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH));
        TripConditions conditions = conditions(List.of(
                new TripConditions.Want("오타루 운하", null, List.of(), null, TripConditions.Reach.NOT_FOUND, false,
                        false),
                new TripConditions.Want("흑돼지", null, List.of(), "흑돼지")), null, null,
                List.of(PlaceRestriction.PORK));

        List<Violation> out = evaluate(day, 1, conditions);

        assertThat(out).filteredOn(v -> v.rule() == Rule.WANT_NOT_FOUND).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.SOFT));
        assertThat(out).extracting(Violation::rule).doesNotContain(Rule.WANT_MISSING, Rule.WANT_FOOD_MISSING);
    }

    @Test
    @DisplayName("해수욕장의 공식 이용시간은 입수 시간이라 일출 방문을 영업시간 밖으로 보지 않는다")
    void beachHoursDoNotBlockSunrise() {
        PlaceCandidate beach = new PlaceCandidate(9, 9L, "해운대해수욕장", null,
                "Landmarks and Outdoors > Tourist Attraction", List.of(), "Busan", "KR", BigDecimal.valueOf(35.16),
                BigDecimal.valueOf(129.16), null, null, 0.0, null, "월요일: 09:00 ~ 18:00, 토요일: 09:00 ~ 18:00",
                null, null, false, null, null, null, null, "SIGHT", null, false, null, null, null, null);
        List<StepData> day = List.of(new StepData(1, 1, "06:59", "07:59",
                new PlaceData(beach.name(), null, beach.category(), "Busan", "KR", null, null, 9L, null), List.of(),
                null, null, null, null, null, null));
        ItineraryRules.Context ctx = new ItineraryRules.Context("normal", "any", START, 1, null, Map.of(),
                conditions(List.of(), null, null, List.of()), "KR", List.of());

        assertThat(rules.evaluate(day, List.of(beach), ctx, Map.of()).violations()).extracting(Violation::rule)
                .doesNotContain(Rule.OUTSIDE_HOURS);
    }

    @Test
    @DisplayName("사용자가 직접 고른 곳은 피할 조건에 걸려도, 넣을 날이 없다고 알린 요구는 빠져도 저장을 막지 않는다")
    void chosenPlaceAndInfeasibleWantAreNotHard() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", SASHIMI));
        TripConditions conditions = new TripConditions(
                List.of(new TripConditions.Want("반포 야경", "evening", List.of(), null, TripConditions.Reach.NO_SLOT,
                        false, false)), Map.of(),
                List.of(PlaceRestriction.SEAFOOD), false, "transit", null, null, "normal", java.util.Set.of(3L));

        assertThat(evaluate(day, 1, conditions)).extracting(Violation::rule)
                .contains(Rule.WANT_NO_SLOT).doesNotContain(Rule.AVOID, Rule.WANT_MISSING, Rule.WANT_TIME);
    }
}
