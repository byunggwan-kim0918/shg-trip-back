package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SerialPlannerTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String MEAL = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";

    private final SerialPlanner planner = new SerialPlanner();

    private SerialPlanner.Result schedule(TraceFile trace) {
        return planner.schedule(trace.selection(), trace.candidates(), trace.args());
    }

    @Test
    @DisplayName("고른 장소는 그날에 남거나 빠질 뿐 다른 날로 옮겨지지 않는다")
    void picksStayOnTheirDay() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            Map<Long, Integer> pickedDay = new HashMap<>();
            for (SelectionOutput.DayPlan plan : trace.selection().days()) {
                for (Integer idx : plan.placeIndices()) {
                    pickedDay.putIfAbsent(trace.candidates().get(idx - 1).placeId(), plan.dayNumber());
                }
            }

            for (StepData step : schedule(trace).steps()) {
                Integer day = pickedDay.get(step.place().placeId());
                if (day != null) assertThat(step.dayNumber()).as(step.place().name()).isEqualTo(day);
            }
        }
    }

    @Test
    @DisplayName("날마다 고른 수는 남은 수와 빠진 수의 합이다")
    void reportsAccountForEveryPick() {
        TraceFile trace = Itinerary91Fixture.load();
        SerialPlanner.Result result = schedule(trace);

        assertThat(result.days()).extracting(SerialPlanner.DayReport::dayNumber).containsExactly(1, 2, 3, 4, 5, 6);
        for (SerialPlanner.DayReport day : result.days()) {
            SelectionOutput.DayPlan plan = trace.selection().days().get(day.dayNumber() - 1);
            List<String> stepNames = result.steps().stream().filter(s -> s.dayNumber() == day.dayNumber())
                    .map(s -> s.place().name()).toList();
            long kept = plan.placeIndices().stream().distinct()
                    .filter(idx -> stepNames.contains(trace.candidates().get(idx - 1).name())).count();

            assertThat(day.picked()).as("day %d", day.dayNumber()).isEqualTo((int) kept + day.dropped().size());
            assertThat(day.dropped()).noneMatch(d -> stepNames.contains(d.name()));
        }
    }

    @Test
    @DisplayName("부산 5박 6일 기록의 날짜별 집계가 고정돼 있다")
    void reportsForBusanTrip() {
        assertThat(schedule(Itinerary91Fixture.load()).days()).containsExactly(
                new SerialPlanner.DayReport(1, 4, List.of(), 1, 0),
                new SerialPlanner.DayReport(2, 5, List.of(), 0, 0),
                new SerialPlanner.DayReport(3, 5,
                        List.of(new SerialPlanner.Dropped("이기대해안산책로", DropReason.TIME_BUDGET)), 1, 0),
                new SerialPlanner.DayReport(4, 5,
                        List.of(new SerialPlanner.Dropped("부산타워 (Busan Tower)", DropReason.CLOSED_AT_TIME)), 1, 0),
                new SerialPlanner.DayReport(5, 5,
                        List.of(new SerialPlanner.Dropped("범어사(부산)", DropReason.TIME_BUDGET)), 1, 0),
                new SerialPlanner.DayReport(6, 3, List.of(), 1, 0));
    }

    @Test
    @DisplayName("운영 기록과 여러 도시 입력 모두 구조 검증을 통과하고 날짜가 빠지지 않는다")
    void producesStructurallyValidItineraries() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            List<StepData> steps = schedule(trace).steps();

            assertThat(new HardValidator().validate(new ItineraryData("t", trace.destination(), BigDecimal.ZERO,
                    List.of(), steps)).failureReason()).isNull();
            assertThat(steps.stream().map(StepData::dayNumber).distinct().count()).isEqualTo(trace.dayCount());
        }
    }

    @Test
    @DisplayName("여러 도시는 도시 구간마다 그 도시 장소만 쓰고 옮기는 날을 도시 간 이동으로 시작한다")
    void cityBlocksAreScheduledSeparately() {
        TraceFile trace = JoinedSeoulBusanFixture.load();
        Map<String, String> regionOf = new HashMap<>();
        trace.candidates().forEach(c -> regionOf.put(c.name(), c.region()));

        List<StepData> steps = schedule(trace).steps();

        assertThat(steps).allSatisfy(s -> assertThat(regionOf.get(s.place().name())).as(s.place().name())
                .isEqualTo(s.dayNumber() <= 3 ? "Seoul" : "Busan"));
        assertThat(steps.stream().filter(s -> s.dayNumber() == 4).findFirst().orElseThrow().transportationMode())
                .isEqualTo("TRAIN");
    }

    @Test
    @DisplayName("관광지만 고른 날은 그날 근처 식당으로 점심·저녁을 채우고, 뒷날에 고른 식당은 가져오지 않는다")
    void fillsMissingMealsWithinTheDay() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        pool.add(place(2, "한옥마을", SIGHT, "SIGHT", 37.5826, 126.9836));
        pool.add(place(3, "호텔", HOTEL, "LODGING", 37.5665, 126.9910));
        pool.add(place(4, "뒷날 고른 식당", MEAL, "MEAL", 37.5760, 126.9850));
        pool.add(place(5, "남는 식당 하나", MEAL, "MEAL", 37.5740, 126.9860));
        pool.add(place(6, "남는 식당 둘", MEAL, "MEAL", 37.5720, 126.9900));
        pool.add(place(7, "시장", SIGHT, "SIGHT", 37.5700, 126.9990));
        pool.add(place(8, "뒷날 식당 둘", MEAL, "MEAL", 37.5710, 126.9980));
        SelectionOutput selection = new SelectionOutput("이틀", List.of(
                new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null),
                new SelectionOutput.DayPlan(2, null, List.of(7, 4, 8), null, null)), List.of(), List.of());
        SerialPlanner.Result result = planner.schedule(selection, pool, carArgs());

        List<String> day1 = result.steps().stream().filter(s -> s.dayNumber() == 1).map(s -> s.place().name()).toList();
        assertThat(day1).contains("남는 식당 하나", "남는 식당 둘").doesNotContain("뒷날 고른 식당", "뒷날 식당 둘");
        assertThat(day1.get(day1.size() - 1)).isEqualTo("호텔");
        assertThat(result.days().get(0)).extracting(SerialPlanner.DayReport::picked, SerialPlanner.DayReport::filled)
                .containsExactly(2, 2);
    }

    @Test
    @DisplayName("고른 날에서 거리로 빠진 장소는 다른 날의 빈자리를 채우는 데 쓰이지 않는다")
    void droppedPickIsNotReusedOnAnotherDay() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        pool.add(place(2, "먼 사적지", SIGHT, "SIGHT", 37.1000, 126.9800));
        pool.add(place(3, "호텔", HOTEL, "LODGING", 37.5665, 126.9910));
        pool.add(place(4, "첫날 점심", MEAL, "MEAL", 37.5760, 126.9850));
        pool.add(place(5, "첫날 저녁", MEAL, "MEAL", 37.5720, 126.9900));
        pool.add(place(6, "둘째 날 점심", MEAL, "MEAL", 37.1400, 126.9800));
        pool.add(place(7, "둘째 날 저녁", MEAL, "MEAL", 37.1300, 126.9850));
        SelectionOutput selection = new SelectionOutput("이틀", List.of(
                new SelectionOutput.DayPlan(1, null, List.of(1, 2, 4, 5), 3, null),
                new SelectionOutput.DayPlan(2, null, List.of(6, 7), null, null)), List.of(), List.of());

        SerialPlanner.Result result = planner.schedule(selection, pool, carArgs());

        assertThat(result.days().get(0).dropped())
                .containsExactly(new SerialPlanner.Dropped("먼 사적지", DropReason.DISTANCE));
        assertThat(result.steps()).extracting(s -> s.place().name()).doesNotContain("먼 사적지");
    }

    @Test
    @DisplayName("고른 장소가 모두 빠진 날은 거점 근처 관광지로 다시 시작해 날짜가 비지 않는다")
    void emptyDayIsSeeded() {
        List<PlaceCandidate> pool = closedMuseumTrip();
        for (int i = 0; i < 6; i++) {
            pool.add(place(8 + i, "남는 궁 " + i, SIGHT, "SIGHT", 37.5826 + i * 0.002, 126.9836));
        }

        SerialPlanner.Result result = planner.schedule(closedMuseumSelection(), pool, carArgs());

        List<StepData> day2 = result.steps().stream().filter(s -> s.dayNumber() == 2).toList();
        assertThat(day2).extracting(s -> s.place().name()).doesNotContain("수요일에 쉬는 박물관")
                .anyMatch(name -> name.startsWith("남는 궁"));
        assertThat(result.days().get(1).dropped())
                .containsExactly(new SerialPlanner.Dropped("수요일에 쉬는 박물관", DropReason.CLOSED_AT_TIME));
        assertThat(result.days().get(1).filled()).isEqualTo(day2.size());
    }

    @Test
    @DisplayName("관광지가 남지 않은 빈 날은 식당으로라도 시작한다")
    void emptyDayWithoutSightsIsSeededWithAMeal() {
        SerialPlanner.Result result = planner.schedule(closedMuseumSelection(), closedMuseumTrip(), carArgs());

        assertThat(result.steps()).filteredOn(s -> s.dayNumber() == 2)
                .extracting(s -> s.place().name()).contains("남는 식당");
    }

    @Test
    @DisplayName("다시 넣은 장소가 또 빠지면 그 장소를 빼고 다음 장소로 시작한다")
    void seedDroppedAgainIsReplaced() {
        List<PlaceCandidate> pool = closedMuseumTrip();
        pool.set(5, new PlaceCandidate(6, 6L, "수요일 밤에만 여는 전망대", "서울특별시 중구 을지로 6", SIGHT, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(37.5666), BigDecimal.valueOf(126.9911), null, null, 0.8, null,
                "화요일: 휴무일, 수요일: 오후 9:00 ~ 오후 11:00", null, 60, false, null, null, null, null, "SIGHT", null,
                false, null));

        SerialPlanner.Result result = planner.schedule(closedMuseumSelection(), pool, carArgs());

        assertThat(result.steps()).filteredOn(s -> s.dayNumber() == 2).extracting(s -> s.place().name())
                .contains("남는 식당").doesNotContain("수요일 밤에만 여는 전망대");
    }

    /** 1일차는 궁과 두 끼, 2일차는 수요일에 쉬는 박물관 하나만 고른 이틀 여행. 2일차가 수요일이다. */
    private static List<PlaceCandidate> closedMuseumTrip() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        pool.add(place(2, "호텔", HOTEL, "LODGING", 37.5665, 126.9910));
        pool.add(place(3, "점심 식당", MEAL, "MEAL", 37.5760, 126.9850));
        pool.add(place(4, "저녁 식당", MEAL, "MEAL", 37.5720, 126.9900));
        pool.add(new PlaceCandidate(5, 5L, "수요일에 쉬는 박물관", "서울특별시 종로구 율곡로 5", SIGHT, List.of(), "Seoul", "KR",
                BigDecimal.valueOf(37.5750), BigDecimal.valueOf(126.9800), null, null, 0.8, null, "수요일: 휴무일", null,
                60, false, null, null, null, null, "SIGHT", null, false, null));
        pool.add(place(6, "창덕궁", SIGHT, "SIGHT", 37.5826, 126.9836));
        pool.add(place(7, "남는 식당", MEAL, "MEAL", 37.5740, 126.9860));
        return pool;
    }

    private static SelectionOutput closedMuseumSelection() {
        return new SelectionOutput("이틀", List.of(
                new SelectionOutput.DayPlan(1, null, List.of(1, 3, 4), 2, null),
                new SelectionOutput.DayPlan(2, null, List.of(5), null, null)), List.of(), List.of());
    }

    @Test
    @DisplayName("다른 도시 날에 고른 장소는 고른 날에서 빠진 것으로 세고, 옮겨 간 날에서는 채운 장소로 세지 않는다")
    void cityBlockStraysCountAsDroppedOnPickedDay() {
        TraceFile trace = JoinedSeoulBusanFixture.load();
        List<SelectionOutput.DayPlan> days = new ArrayList<>(trace.selection().days());
        SelectionOutput.DayPlan seoulDay = days.get(0);
        SelectionOutput.DayPlan busanDay = days.get(3);
        int busanPick = busanDay.placeIndices().get(0);
        List<Integer> seoulPicks = new ArrayList<>(seoulDay.placeIndices());
        seoulPicks.add(busanPick);
        days.set(0, new SelectionOutput.DayPlan(1, null, seoulPicks, seoulDay.accommodationIndex(), null));
        days.set(3, new SelectionOutput.DayPlan(4, null, busanDay.placeIndices().stream()
                .filter(idx -> idx != busanPick).toList(), busanDay.accommodationIndex(), null));
        String stray = trace.candidates().get(busanPick - 1).name();

        SerialPlanner.Result result = planner.schedule(trace.selection().withDays(days), trace.candidates(),
                trace.args());

        assertThat(result.days().get(0).picked()).isEqualTo(seoulPicks.size());
        assertThat(result.days().get(0).dropped()).contains(new SerialPlanner.Dropped(stray, DropReason.OTHER));
        assertThat(result.steps()).filteredOn(s -> s.place().name().equals(stray)).singleElement()
                .satisfies(s -> assertThat(s.dayNumber()).isGreaterThanOrEqualTo(4));
        long visits = result.steps().stream().filter(s -> !isLodgingOrHub(s)).count();
        int keptOrFilled = result.days().stream().mapToInt(d -> d.picked() - d.dropped().size() + d.filled()).sum();
        assertThat(visits).isEqualTo(keptOrFilled + 1);
    }

    private static boolean isLodgingOrHub(StepData step) {
        return PlaceCategoryConstants.isAccommodation(step.place().category())
                || PlaceCategoryConstants.isTransitHub(step.place().name(), step.place().category());
    }

    private static ScheduleArgs carArgs() {
        return ScheduleTestArgs.of("normal", "car", LocalDate.of(2026, 12, 1), List.of(), null, false, List.of());
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                       double lng) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index, category, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60,
                false, null, null, null, null, role, null, false, null);
    }
}
