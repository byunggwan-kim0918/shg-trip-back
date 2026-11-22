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
    @DisplayName("피할 조건에 걸린 장소는 지킬 수 없는 위반이고, 읽지 못한 제약 이름은 건너뛴다")
    void avoidIgnoresUnknownRestriction() {
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", SASHIMI));
        List<PlaceRestriction> avoid = new ArrayList<>(Arrays.asList(PlaceRestriction.SEAFOOD, null));

        assertThat(evaluate(day, 1, conditions(List.of(), null, null, avoid)))
                .filteredOn(v -> v.rule() == Rule.AVOID).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.HARD));
    }

    private List<StepData> lastDayAt(String stationStart) {
        return List.of(step(1, 1, "10:00", "11:30", BEACH_SPOT), step(2, 1, "12:00", "13:00", LUNCH),
                step(3, 1, stationStart, stationStart, BUSAN_STATION));
    }

    private List<Violation> evaluate(List<StepData> steps, int days, TripConditions conditions) {
        ItineraryRules.Context ctx = new ItineraryRules.Context("normal", "any", START, days, null, Map.of(),
                conditions, "KR", List.of(new ItineraryRules.City("부산", 35.18, 129.08)));
        return rules.evaluate(steps, ALL, ctx, Map.of()).violations();
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
                new TripConditions.Want("오타루 운하", null, List.of(), null, true),
                new TripConditions.Want("흑돼지", null, List.of(), "흑돼지")), null, null,
                List.of(PlaceRestriction.PORK));

        List<Violation> out = evaluate(day, 1, conditions);

        assertThat(out).filteredOn(v -> v.rule() == Rule.WANT_NOT_IN_DATA).singleElement()
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
                List.of(new TripConditions.Want("반포 야경", "evening", List.of(), null, false, true)), Map.of(),
                List.of(PlaceRestriction.SEAFOOD), false, "transit", null, null, "normal", java.util.Set.of(3L));

        assertThat(evaluate(day, 1, conditions)).extracting(Violation::rule)
                .doesNotContain(Rule.AVOID, Rule.WANT_MISSING, Rule.WANT_TIME);
    }
}
