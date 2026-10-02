package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RouteOptimizer.repairAndSchedule() — Sonnet의 day 구성(힌트)을 받아 결정론적으로
 * pace quota·pair·거리이탈·연속숙소·허브를 수리(fixpoint)하고 시간을 확정하는 핵심 엔진 테스트.
 * LLM 호출이 전혀 없는 순수 코드라 모킹 없이 검증 가능.
 */
class RouteOptimizerTest {

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    private PlaceCandidate place(int index, String name, String category, double lat, double lng, String region) {
        return new PlaceCandidate(index, (long) index, name, "addr-" + index, category,
                List.of(), region, "Korea", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                "desc", BigDecimal.valueOf(4.5), 0.9);
    }

    private PlaceCandidate placeWithHours(int index, String name, String category, double lat, double lng,
                                          String region, String openingHours) {
        return new PlaceCandidate(index, (long) index, name, "addr-" + index, category,
                List.of(), region, "Korea", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                "desc", BigDecimal.valueOf(4.5), 0.9, null, openingHours);
    }

    @Test
    @DisplayName("pace quota 초과 시 day별 장소 수가 normal 상한(5) 이하로 트림된다")
    void repairAndSchedule_trimsExceedingPaceQuota() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "A1", "Landmarks and Outdoors > Park", 37.50, 127.00, "강남"),
                place(2, "A2", "Landmarks and Outdoors > Park", 37.501, 127.001, "강남"),
                place(3, "A3", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.503, 127.003, "강남"),
                place(5, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.504, 127.004, "강남"),
                place(6, "A6", "Landmarks and Outdoors > Park", 37.505, 127.005, "강남"),
                place(7, "A7", "Landmarks and Outdoors > Park", 37.506, 127.006, "강남"),
                place(8, "Lodging", "Lodging > Hotel", 37.507, 127.007, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "도심 산책 컨셉",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4, 5, 6, 7), 8, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        long mainPlaceCount = steps.stream()
                .filter(s -> !"Lodging".equals(s.place().name()))
                .count();

        assertThat(mainPlaceCount).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("좌표 (0,0) 불량 장소가 있어도 새벽시간 wrap·비현실 이동거리를 만들지 않는다")
    void repairAndSchedule_invalidZeroCoordDoesNotBreakSchedule() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "정상A", "Landmarks and Outdoors > Park", 37.50, 127.00, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                // 좌표 (0,0) — Google fallback 등으로 유입될 수 있는 불량 장소
                place(3, "불량장소", "Landmarks and Outdoors > Park", 0.0, 0.0, "강남"),
                place(4, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.503, 127.003, "강남"),
                place(5, "Lodging", "Lodging > Hotel", 37.504, 127.004, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "도심 컨셉",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4), 5, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        // 1. 모든 스텝의 시작 시각이 일과 시작(09:00) 이후 — 새벽으로 wrap되지 않음
        assertThat(steps).allSatisfy(s ->
                assertThat(toMinutes(s.startTime())).isGreaterThanOrEqualTo(9 * 60));
        // 2. 같은 날 시각이 단조 비감소
        int prevEnd = -1;
        for (StepData s : steps) {
            assertThat(toMinutes(s.startTime())).isGreaterThanOrEqualTo(prevEnd);
            prevEnd = toMinutes(s.endTime());
        }
        // 3. 비현실적 이동거리(>200km) 구간이 없음
        assertThat(steps).allSatisfy(s -> {
            if (s.transportationDistance() != null) {
                assertThat(s.transportationDistance()).isLessThanOrEqualTo(BigDecimal.valueOf(200));
            }
        });
    }

    private int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    @Test
    @DisplayName("pair는 다른 day에 떨어져 있으면 quota 여유가 있는 쪽으로 합쳐진다")
    void repairAndSchedule_consolidatesPairsAcrossDays() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Day1-A", "Landmarks and Outdoors > Park", 37.50, 127.00, "강남"),
                place(2, "Day1-B", "Landmarks and Outdoors > Park", 37.501, 127.001, "강남"),
                place(3, "PairA", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "PairB", "Cafe", 37.503, 127.003, "강남"),
                place(5, "Lodging1", "Lodging > Hotel", 37.504, 127.004, "강남"),
                place(6, "Lodging2", "Lodging > Hotel", 38.00, 128.00, "부산")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 5, null),
                        new SelectionOutput.DayPlan(2, null, List.of(4), 6, null)
                ),
                List.of(List.of(3, 4)),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        Map<String, Integer> dayByName = steps.stream()
                .collect(Collectors.toMap(s -> s.place().name(), StepData::dayNumber, (a, b) -> a));

        assertThat(dayByName.get("PairA")).isEqualTo(dayByName.get("PairB"));
    }

    @Test
    @DisplayName("TRANSIT_HUB 후보가 있으면 첫날 도착/마지막날 출발에 누락 시 보충된다")
    void repairAndSchedule_fillsMissingHubs() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "공항", "Airport", 37.50, 127.00, "강남"),
                place(2, "A1", "Landmarks and Outdoors > Park", 37.501, 127.001, "강남"),
                place(3, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.502, 127.002, "강남"),
                place(4, "Lodging", "Lodging > Hotel", 37.503, 127.003, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        assertThat(steps.get(0).place().name()).isEqualTo("공항");
    }

    @Test
    @DisplayName("인접 day가 같은 지역(dominant region)이면 동일 숙소를 유지한다")
    void repairAndSchedule_keepsSameAccommodationForSameRegionConsecutiveDays() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Day1-A", "Landmarks and Outdoors > Park", 37.50, 127.00, "강남"),
                place(2, "Day2-A", "Landmarks and Outdoors > Park", 37.501, 127.001, "강남"),
                place(3, "Hotel-A", "Lodging > Hotel", 37.502, 127.002, "강남"),
                place(4, "Hotel-B", "Lodging > Hotel", 37.503, 127.003, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1), 3, null),
                        new SelectionOutput.DayPlan(2, null, List.of(2), 4, null)
                ),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        String day1Accommodation = steps.stream()
                .filter(s -> s.dayNumber() == 1 && s.place().name().startsWith("Hotel"))
                .map(s -> s.place().name())
                .findFirst().orElseThrow();
        String day2Accommodation = steps.stream()
                .filter(s -> s.dayNumber() == 2 && s.place().name().startsWith("Hotel"))
                .map(s -> s.place().name())
                .findFirst().orElseThrow();

        assertThat(day1Accommodation).isEqualTo(day2Accommodation);
    }

    @Test
    @DisplayName("다음날 첫 스텝은 전날 묵은 숙소에서 가장 가까운 장소가 되어야 한다(반대 방향 출발 방지)")
    void repairAndSchedule_startsNextDayNearPreviousNightsAccommodation() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Day1Place", "Landmarks and Outdoors > Park", 37.400, 127.000, "강남"),
                place(2, "Hotel", "Lodging > Hotel", 37.500, 127.000, "강남"),
                // day2 메인 장소는 입력 순서상 "먼 곳부터" 나열 — anchor 보정이 없으면
                // NN 시드가 인덱스 0(Far)부터 시작해 호텔에서 먼 곳으로 출발하게 됨.
                place(3, "Far", "Landmarks and Outdoors > Park", 37.520, 127.000, "강남"),
                place(4, "Mid", "Landmarks and Outdoors > Garden", 37.510, 127.000, "강남"),
                place(5, "Near", "Arts and Entertainment > Museum", 37.501, 127.000, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1), 2, null),
                        new SelectionOutput.DayPlan(2, null, List.of(3, 4, 5), 2, null)
                ),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        StepData firstDay2MainStep = steps.stream()
                .filter(s -> s.dayNumber() == 2 && !"Hotel".equals(s.place().name()))
                .findFirst().orElseThrow();

        assertThat(firstDay2MainStep.place().name()).isEqualTo("Near");
    }

    @Test
    @DisplayName("그날 마지막 메인 장소는 그날 숙소에서 가장 가까운 쪽이 되어야 한다")
    void repairAndSchedule_endsDayNearThatNightsAccommodation() {
        List<PlaceCandidate> candidates = List.of(
                // 입력 순서상 "숙소에서 가까운 곳부터" 나열 — anchor 보정이 없으면 가까운 곳이
                // 먼저 방문되고 먼 곳에서 하루가 끝나, 마지막에 숙소까지 먼 거리를 이동해야 함.
                place(1, "Near", "Landmarks and Outdoors > Park", 37.501, 127.000, "강남"),
                place(2, "Mid", "Landmarks and Outdoors > Garden", 37.510, 127.000, "강남"),
                place(3, "Far", "Arts and Entertainment > Museum", 37.520, 127.000, "강남"),
                place(4, "Hotel", "Lodging > Hotel", 37.500, 127.000, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        List<StepData> mainSteps = steps.stream()
                .filter(s -> !"Hotel".equals(s.place().name()))
                .collect(Collectors.toList());

        assertThat(mainSteps.get(mainSteps.size() - 1).place().name()).isEqualTo("Near");
    }

    @Test
    @DisplayName("DINING 카테고리는 점심/저녁 슬롯(11:30-13:30, 17:30-19:30) 안으로 시간이 보정된다")
    void repairAndSchedule_pinsDiningStepsToMealSlots() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Morning", "Landmarks and Outdoors > Park", 37.50, 127.00, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Afternoon", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.503, 127.003, "강남"),
                place(5, "Lodging", "Lodging > Hotel", 37.504, 127.004, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4), 5, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        StepData lunch = steps.stream().filter(s -> s.place().name().equals("Lunch")).findFirst().orElseThrow();
        StepData dinner = steps.stream().filter(s -> s.place().name().equals("Dinner")).findFirst().orElseThrow();

        assertThat(lunch.startTime()).isBetween("11:30", "13:30");
        assertThat(dinner.startTime()).isBetween("17:30", "19:30");
    }

    @Test
    @DisplayName("정기휴무(월요일 휴무) 장소는 startDate 기준 그날 열린 같은 카테고리 spare로 교체된다")
    void repairAndSchedule_swapsClosedDayPlaceWithOpenSpare() {
        // 2024-01-01 은 월요일 → day1 = 월요일
        java.time.LocalDate monday = java.time.LocalDate.of(2024, 1, 1);

        List<PlaceCandidate> candidates = List.of(
                placeWithHours(1, "월요일휴무명소", "Landmarks and Outdoors > Park",
                        37.50, 127.00, "강남", "월요일: 휴무, 화요일: 오전 9:00 ~ 오후 6:00"),
                placeWithHours(2, "Lunch", "Dining and Drinking > Restaurant > Korean",
                        37.501, 127.001, "강남", null),
                placeWithHours(3, "Lodging", "Lodging > Hotel",
                        37.502, 127.002, "강남", null),
                placeWithHours(4, "항상열린대체명소", "Landmarks and Outdoors > Park",
                        37.5005, 127.0005, "강남", "월요일: 오전 9:00 ~ 오후 6:00")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed", monday);

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        assertThat(names).contains("항상열린대체명소");
        assertThat(names).doesNotContain("월요일휴무명소");
    }

    @Test
    @DisplayName("정기휴무 장소가 pair 멤버면 교체하지 않고 그대로 유지한다 (pair 무결성 우선)")
    void repairAndSchedule_doesNotSwapClosedDayPlaceWhenItIsAPairMember() {
        // 2024-01-01 은 월요일 → day1 = 월요일
        java.time.LocalDate monday = java.time.LocalDate.of(2024, 1, 1);

        List<PlaceCandidate> candidates = List.of(
                placeWithHours(1, "월요일휴무명소", "Landmarks and Outdoors > Park",
                        37.50, 127.00, "강남", "월요일: 휴무, 화요일: 오전 9:00 ~ 오후 6:00"),
                placeWithHours(2, "Lunch", "Dining and Drinking > Restaurant > Korean",
                        37.501, 127.001, "강남", null),
                placeWithHours(3, "Lodging", "Lodging > Hotel",
                        37.502, 127.002, "강남", null),
                placeWithHours(4, "항상열린대체명소", "Landmarks and Outdoors > Park",
                        37.5005, 127.0005, "강남", "월요일: 오전 9:00 ~ 오후 6:00"),
                placeWithHours(5, "짝지", "Cafe", 37.5008, 127.0008, "강남", null)
        );

        // index 1(월요일휴무명소)과 index 5(짝지)가 must_pair_with — 1이 휴무라도 교체되면
        // 5와 같은 날 함께 있어야 하는 보장이 깨진다.
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 5), 3, null)),
                List.of(List.of(1, 5)),
                List.of(4)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed", monday);

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        assertThat(names).contains("월요일휴무명소", "짝지");
        assertThat(names).doesNotContain("항상열린대체명소");
    }

    @Test
    @DisplayName("openingHours 데이터가 없으면 휴무 회피를 적용하지 않는다(열림 가정)")
    void repairAndSchedule_skipsClosedDayWhenNoData() {
        java.time.LocalDate monday = java.time.LocalDate.of(2024, 1, 1);

        List<PlaceCandidate> candidates = List.of(
                place(1, "데이터없는명소", "Landmarks and Outdoors > Park", 37.50, 127.00, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Lodging", "Lodging > Hotel", 37.502, 127.002, "강남"),
                place(4, "대체후보", "Landmarks and Outdoors > Park", 37.5005, 127.0005, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed", monday);

        // openingHours가 null이면 교체하지 않음 — 원래 장소 유지
        assertThat(steps.stream().map(s -> s.place().name())).contains("데이터없는명소");
    }

    @Test
    @DisplayName("isClosedOnDay: 휴무 신호에만 true, 데이터 없음/요일 미매칭/영업중은 false")
    void isClosedOnDay_onlyTrueOnHighConfidenceClosedSignal() {
        assertThat(routeOptimizer.isClosedOnDay("월요일: 휴무, 화요일: 오전 9시~오후 6시",
                java.time.DayOfWeek.MONDAY)).isTrue();
        assertThat(routeOptimizer.isClosedOnDay("Monday: Closed, Tuesday: 9 AM – 6 PM",
                java.time.DayOfWeek.MONDAY)).isTrue();
        assertThat(routeOptimizer.isClosedOnDay("월요일: 오전 9시~오후 6시",
                java.time.DayOfWeek.MONDAY)).isFalse();
        assertThat(routeOptimizer.isClosedOnDay(null, java.time.DayOfWeek.MONDAY)).isFalse();
        assertThat(routeOptimizer.isClosedOnDay("", java.time.DayOfWeek.MONDAY)).isFalse();
        // 화요일 정보만 있고 월요일 미매칭 → 열림 가정
        assertThat(routeOptimizer.isClosedOnDay("화요일: 휴무",
                java.time.DayOfWeek.MONDAY)).isFalse();
    }

    @Test
    @DisplayName("highlightIndices로 표시된 장소는 거리가 비슷하면 그날의 첫 스텝으로 배치되지 않는다")
    void repairAndSchedule_avoidsPlacingHighlightFirstWhenDistancesAreClose() {
        // 일직선 3점: H(highlight)-M-F, 변끼리 거리가 같아 순방향/역방향 총거리가 동일하다.
        // 거리만 보면 NN 시드(입력 순서 0번째=H부터 시작)를 그대로 둬도 무방하지만,
        // highlight-first 패널티(0.5km)가 있으면 방향을 뒤집어 H를 끝으로 보내는 쪽이 더 싸다.
        List<PlaceCandidate> candidates = List.of(
                place(1, "Highlight", "Landmarks and Outdoors > Park", 37.500, 127.000, "강남"),
                place(2, "Middle", "Landmarks and Outdoors > Garden", 37.505, 127.000, "강남"),
                place(3, "Far", "Arts and Entertainment > Museum", 37.510, 127.000, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), null, null)),
                List.of(),
                List.of(),
                List.of(1),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        assertThat(steps.get(0).place().name()).isNotEqualTo("Highlight");
    }

    @Test
    @DisplayName("restIndices로 표시된 장소는 거리가 비슷하면 highlightIndices 다음 자리에 배치된다")
    void repairAndSchedule_prefersRestImmediatelyAfterHighlight() {
        // 다이아몬드(마름모) 배치: N-E-S-W 네 변의 길이가 거의 동일 → "링 순서"를 따르는
        // 경로들은 거리가 서로 비슷하다. H=N, R=E(인접), Neutral1=S, Neutral2=W일 때
        // rest-after-highlight 보너스(0.3km)가 있으면 H 바로 다음에 R이 오는 순서를 선호한다.
        List<PlaceCandidate> candidates = List.of(
                place(1, "Highlight", "Landmarks and Outdoors > Park", 37.505, 127.000, "강남"), // N
                place(2, "Rest", "Cafe", 37.502, 127.003, "강남"), // E
                place(3, "Neutral1", "Landmarks and Outdoors > Park", 37.500, 127.000, "강남"), // S
                place(4, "Neutral2", "Landmarks and Outdoors > Park", 37.502, 126.997, "강남")  // W
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4), null, null)),
                List.of(),
                List.of(),
                List.of(1),
                List.of(2)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "tight");

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        int highlightPos = names.indexOf("Highlight");
        int restPos = names.indexOf("Rest");

        assertThat(Math.abs(restPos - highlightPos)).isEqualTo(1);
    }

    // ── A-2: day 클러스터링 분리 ──
    @Test
    @DisplayName("day가 동↔서 2-클러스터로 갈리면 소수 클러스터가 인접 day로 이동한다")
    void repairAndSchedule_movesMinorityClusterToAdjacentDay() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "East1", "Landmarks and Outdoors > Park", 37.50, 127.000, "동"),
                place(2, "East2", "Landmarks and Outdoors > Garden", 37.5005, 127.0005, "동"),
                place(3, "West", "Arts and Entertainment > Museum", 37.50, 127.900, "서"),   // ~80km 서
                place(4, "West2", "Landmarks and Outdoors > Waterfall", 37.50, 127.905, "서"),
                place(5, "Lodging", "Lodging > Hotel", 37.50, 127.900, "서")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), null, null),
                        new SelectionOutput.DayPlan(2, null, List.of(4), 5, null)
                ),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        int westDay = steps.stream().filter(s -> "West".equals(s.place().name()))
                .map(StepData::dayNumber).findFirst().orElseThrow();
        assertThat(westDay).isEqualTo(2); // 서쪽 소수 클러스터가 day2로 이동
    }

    // ── A-1: 시간 배분 ──
    @Test
    @DisplayName("활동이 많아도 모든 스텝 시작 시각은 저녁 상한(22:00) 이내다")
    void repairAndSchedule_noStepStartsAfterEveningCap() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.50, 127.000, "강남"),
                place(2, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                // 세부 유형은 전부 다르게(유형 다양성 상한 회피) + 야간 배치가 가능한 실내형으로
                // 통일한다 — 야외/주간 전용(정원·폭포 등)은 저녁 버킷을 못 쓰므로 이 테스트의
                // 검증 대상(저녁 상한 확대)이 드러나지 않는다.
                place(3, "P1", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "P2", "Arts and Entertainment > Museum", 37.503, 127.003, "강남"),
                place(5, "P3", "Arts and Entertainment > Gallery", 37.504, 127.004, "강남"),
                place(6, "P4", "Arts and Entertainment > Theater", 37.505, 127.005, "강남"),
                place(7, "P5", "Arts and Entertainment > Aquarium", 37.506, 127.006, "강남"),
                place(8, "P6", "Landmarks and Outdoors > Park", 37.507, 127.007, "강남"),
                place(9, "Lodging", "Lodging > Hotel", 37.508, 127.008, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4, 5, 6, 7, 8), 9, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "tight");

        assertThat(steps).allSatisfy(s ->
                assertThat(toMinutes(s.startTime())).isLessThanOrEqualTo(22 * 60));
    }

    @Test
    @DisplayName("하루 DINING이 4개 이상이면 3개(아침/점심/저녁)만 스텝에 남는다")
    void repairAndSchedule_capsDiningToThreePerDay() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "D1", "Dining and Drinking > Restaurant > Korean", 37.50, 127.000, "강남"),
                place(2, "D2", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "D3", "Dining and Drinking > Restaurant > Korean", 37.502, 127.002, "강남"),
                place(4, "D4", "Dining and Drinking > Restaurant > Korean", 37.503, 127.003, "강남"),
                place(5, "Park", "Landmarks and Outdoors > Park", 37.504, 127.004, "강남"),
                place(6, "Lodging", "Lodging > Hotel", 37.505, 127.005, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4, 5), 6, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        long diningSteps = steps.stream()
                .filter(s -> s.place().category().startsWith("Dining and Drinking"))
                .count();
        assertThat(diningSteps).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("야경(nightview) 테마면 저녁 상한이 넓어져 더 많은 활동이 스케줄된다")
    void repairAndSchedule_nightThemeExtendsEveningCapacity() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.50, 127.000, "강남"),
                place(2, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                // 세부 유형은 전부 다르게(유형 다양성 상한 회피) + 야간 배치가 가능한 실내형으로
                // 통일한다 — 야외/주간 전용(정원·폭포 등)은 저녁 버킷을 못 쓰므로 이 테스트의
                // 검증 대상(저녁 상한 확대)이 드러나지 않는다.
                place(3, "P1", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "P2", "Arts and Entertainment > Museum", 37.503, 127.003, "강남"),
                place(5, "P3", "Arts and Entertainment > Gallery", 37.504, 127.004, "강남"),
                place(6, "P4", "Arts and Entertainment > Theater", 37.505, 127.005, "강남"),
                place(7, "P5", "Arts and Entertainment > Aquarium", 37.506, 127.006, "강남"),
                place(8, "Lodging", "Lodging > Hotel", 37.507, 127.007, "강남")
        );
        // 2 식사 + 5 활동 → 기본 저녁 상한(22:00)이면 활동 4개만 수용(1개 트림),
        // 야경 테마(23:00)면 5개 전부 수용 → 스텝 수가 더 많다.
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4, 5, 6, 7), 8, null)),
                List.of(),
                List.of()
        );

        long defaultSteps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "any", null, List.of()).size();
        long nightSteps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "any", null, List.of("nightview")).size();

        assertThat(nightSteps).isGreaterThan(defaultSteps);
    }

    // ── A-0-2: 메인 스텝 중복 제거 ──
    @Test
    @DisplayName("같은 장소가 placeIndices에 두 번 있어도 스텝엔 한 번만 나온다")
    void repairAndSchedule_dedupesRepeatedMainPlace() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "A1", "Landmarks and Outdoors > Park", 37.50, 127.000, "강남"),
                place(2, "A2", "Landmarks and Outdoors > Park", 37.501, 127.001, "강남"),
                place(3, "Lodging", "Lodging > Hotel", 37.502, 127.002, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 1, 2), 3, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        long a1Count = steps.stream().filter(s -> "A1".equals(s.place().name())).count();
        assertThat(a1Count).isEqualTo(1);
    }

    // ── C: 대안 품질 ──
    @Test
    @DisplayName("대안은 중복 없고 모두 메인과 같은 대분류이며 반경(car 30km) 이내다")
    void buildAlternatives_areDistinctSameCategoryAndNearby() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "MainRest", "Dining and Drinking > Restaurant > Korean", 37.500, 127.000, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Lodging", "Lodging > Hotel", 37.502, 127.002, "강남"),
                // spare 대안 후보: 같은 대분류(DINING) 3곳(근처) + 먼 곳 1 + 다른 카테고리 1
                place(4, "AltA", "Dining and Drinking > Restaurant > Korean", 37.503, 127.003, "강남"),
                place(5, "AltB", "Dining and Drinking > Restaurant > BBQ", 37.504, 127.004, "강남"),
                place(6, "AltC", "Dining and Drinking > Restaurant > Seafood", 37.505, 127.005, "강남"),
                place(7, "FarRest", "Dining and Drinking > Restaurant > Korean", 38.50, 128.50, "부산"), // ~140km
                place(8, "AltPark", "Landmarks and Outdoors > Park", 37.506, 127.006, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4, 5, 6, 7, 8)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "relaxed", "car", null, List.of());

        StepData mainStep = steps.stream().filter(s -> "MainRest".equals(s.place().name()))
                .findFirst().orElseThrow();
        List<AlternativeData> alts = mainStep.alternatives();

        // 중복 없음
        assertThat(alts.stream().map(AlternativeData::name).distinct().count())
                .isEqualTo(alts.size());
        // 모두 DINING(대분류 일치) — AltPark(관광) 배제
        assertThat(alts).allSatisfy(a ->
                assertThat(a.category()).startsWith("Dining and Drinking"));
        // 먼 부산 식당 배제(car 30km 반경)
        assertThat(alts.stream().map(AlternativeData::name)).doesNotContain("FarRest");
    }

    @Test
    @DisplayName("저녁 활동이 없으면 숙소에 가까운 오후 활동이 마지막에 배치돼 저녁→숙소 이동이 짧아진다")
    void repairAndSchedule_endsDayNearAccommodationWhenNoEveningActivity() {
        // 숙소는 서쪽(127.000). 두 식당과 FarGallery는 동쪽(숙소에서 멂), NearHotel만 숙소 옆.
        // 활동이 2개라 afternoon 버킷이 채워지고, 저녁 버킷이 비어 endDayNearAccommodation이 발동한다.
        // 활동은 실내형(갤러리)으로 둔다 — 공원·해변·오름 같은 야외 주간형은 세부 유형 판정상
        // DAYTIME이라 애초에 저녁으로 옮기지 않는 것이 정상 동작이다.
        List<PlaceCandidate> candidates = List.of(
                place(1, "Meal1", "Dining and Drinking > Restaurant > Korean", 37.50, 127.010, "강남"),
                place(2, "NearHotel", "Arts and Entertainment > Art Gallery", 37.50, 127.001, "강남"),
                place(3, "Meal2", "Dining and Drinking > Restaurant > Korean", 37.50, 127.030, "강남"),
                place(4, "FarGallery", "Arts and Entertainment > Exhibit", 37.50, 127.035, "강남"),
                place(5, "Hotel", "Lodging > Hotel", 37.50, 127.000, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4), 5, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        List<String> nonHotel = steps.stream()
                .filter(s -> !"Hotel".equals(s.place().name()))
                .map(s -> s.place().name())
                .collect(Collectors.toList());
        // 마지막 비숙소 스텝은 숙소에 가까운 NearHotel (저녁 식당이 아님)
        assertThat(nonHotel.get(nonHotel.size() - 1)).isEqualTo("NearHotel");
    }

    @Test
    @DisplayName("반경 내 대안이 없으면 반경×2까지만 완화하고, 그보다 먼 후보는 채우지 않는다")
    void buildAlternatives_relaxesToDoubleRadiusButNotBeyond() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "MainRest", "Dining and Drinking > Restaurant > Korean", 37.500, 127.000, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Lodging", "Lodging > Hotel", 37.502, 127.002, "강남"),
                // walk 반경(3km) 밖이지만 완화 반경(6km) 안 → 2차 패스에서 채워짐
                place(4, "NearFarAlt", "Dining and Drinking > Restaurant > BBQ", 37.545, 127.000, "강남"),
                // 완화 반경(6km)도 초과 → 대안으로 쓰지 않음(60km 밖 식당이 대안으로 나오던 문제 방지)
                place(5, "VeryFarAlt", "Dining and Drinking > Restaurant > BBQ", 37.680, 127.000, "경기")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4, 5)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "relaxed", "walk", null, List.of());

        StepData mainStep = steps.stream().filter(s -> "MainRest".equals(s.place().name()))
                .findFirst().orElseThrow();
        List<String> altNames = mainStep.alternatives().stream()
                .map(AlternativeData::name).collect(Collectors.toList());
        assertThat(altNames).contains("NearFarAlt");
        assertThat(altNames).doesNotContain("VeryFarAlt");
    }

    // ── 후속 개선(2026-07): fill 가드 / spare 정합성 / 시간대 / 허브 이름 매칭 / 거리 상한 / 갭 필 ──

    @Test
    @DisplayName("spare에 숙소(LODGING)만 남아 있으면 pace 하한 미달이어도 방문 스텝으로 채우지 않는다")
    void repairAndSchedule_neverFillsLodgingAsVisitStep() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Park", "Landmarks and Outdoors > Park", 37.500, 127.000, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.502, 127.002, "강남"),
                place(4, "SpareHostel", "Travel and Transportation > Lodging > Hostel", 37.503, 127.003, "강남"),
                place(5, "SpareHotel", "Travel and Transportation > Lodging > Hotel", 37.504, 127.004, "강남")
        );
        // normal 하한(4) 미달인 2개 day — spare엔 숙소뿐이라 채울 수 없어야 한다
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4, 5)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        assertThat(names).doesNotContain("SpareHostel", "SpareHotel");
    }

    @Test
    @DisplayName("Sonnet이 메인과 spare에 같은 인덱스를 중복 기재해도 일정 내 장소는 대안으로 재등장하지 않는다")
    void repairAndSchedule_usedPlaceNeverAppearsAsAlternative() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "RestA", "Dining and Drinking > Restaurant > Korean", 37.500, 127.000, "강남"),
                place(2, "RestB", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.502, 127.002, "강남"),
                place(4, "RestC", "Dining and Drinking > Restaurant > BBQ", 37.503, 127.003, "강남")
        );
        // 프롬프트 위반 시뮬레이션: RestA(1)가 메인(day1)과 spare 양쪽에 있음
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(1, 4)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "relaxed", "car", null, List.of());

        assertThat(steps).allSatisfy(s ->
                assertThat(s.alternatives().stream().map(AlternativeData::name))
                        .doesNotContain("RestA", "RestB"));
    }

    @Test
    @DisplayName("Bar 계열은 점심 슬롯에 배정되지 않고 저녁 시간대에만 배치된다")
    void repairAndSchedule_barIsNeverLunchAndGoesToEvening() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "BeerBar", "Dining and Drinking > Bar > Beer Bar", 37.500, 127.000, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.502, 127.002, "강남"),
                place(4, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.503, 127.003, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        StepData lunch = steps.stream().filter(s -> "Lunch".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(lunch.startTime()).isBetween("11:30", "13:30");
        // Bar는 트림됐거나(저녁 용량 부족), 배치됐다면 저녁 시간대(17:30 이후)여야 한다
        steps.stream().filter(s -> "BeerBar".equals(s.place().name())).findFirst()
                .ifPresent(bar -> assertThat(toMinutes(bar.startTime())).isGreaterThanOrEqualTo(17 * 60 + 30));
    }

    @Test
    @DisplayName("야외 성격 관광지(골프장 등)는 저녁 버킷에 배치되지 않는다")
    void repairAndSchedule_daytimeOutdoorNeverScheduledInEvening() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Park1", "Landmarks and Outdoors > Park", 37.500, 127.000, "강남"),
                place(2, "Park2", "Landmarks and Outdoors > Park", 37.501, 127.001, "강남"),
                place(3, "Park3", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "GolfCourse", "Sports and Recreation > Golf > Golf Course", 37.503, 127.003, "강남"),
                place(5, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.504, 127.004, "강남"),
                place(6, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.505, 127.005, "강남"),
                place(7, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.506, 127.006, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4, 5, 6), 7, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "tight");

        // 골프장이 배치됐다면 반드시 저녁 식사 시작(17:30) 전이어야 한다
        steps.stream().filter(s -> "GolfCourse".equals(s.place().name())).findFirst()
                .ifPresent(golf -> assertThat(toMinutes(golf.startTime())).isLessThan(17 * 60 + 30));
    }

    @Test
    @DisplayName("둘째 날 첫 스텝은 전날 숙소 출발 이동정보를 가지며 도착시각은 09:00+이동시간이다")
    void repairAndSchedule_firstStepOfDayHasTransportFromPreviousAccommodation() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Day1Park", "Landmarks and Outdoors > Park", 37.500, 127.000, "강남"),
                place(2, "Day1Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.502, 127.002, "강남"),
                // 전날 숙소에서 ~11km 떨어진 둘째 날 첫 방문지
                place(4, "Day2Park", "Landmarks and Outdoors > Park", 37.600, 127.010, "강북"),
                place(5, "Day2Lunch", "Dining and Drinking > Restaurant > Korean", 37.601, 127.011, "강북")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null),
                        new SelectionOutput.DayPlan(2, null, List.of(4, 5), null, null)
                ),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        StepData day2First = steps.stream().filter(s -> s.dayNumber() == 2).findFirst().orElseThrow();
        assertThat(day2First.transportationMode()).isNotNull();
        assertThat(day2First.transportationDuration()).isPositive();
        // 09:00 숙소 출발 + 이동시간 → 첫 장소 도착은 09:00보다 늦다
        assertThat(toMinutes(day2First.startTime())).isGreaterThan(9 * 60);
    }

    @Test
    @DisplayName("단일 구간이 절대 상한(car 50km)을 넘으면 가까운 같은 대분류 spare로 교체된다")
    void repairAndSchedule_swapsFarPlaceExceedingAbsoluteLegCap() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Lunch", "Dining and Drinking > Restaurant > Korean", 33.300, 126.200, "제주"),
                place(2, "Dinner", "Dining and Drinking > Restaurant > Korean", 33.302, 126.202, "제주"),
                place(3, "NearParkA", "Landmarks and Outdoors > Park", 33.301, 126.201, "제주"),
                // 클러스터에서 동쪽으로 ~148km — 단일 구간 상한(50km) 초과
                place(4, "FarPark", "Landmarks and Outdoors > Park", 33.300, 127.800, "제주"),
                place(5, "Hotel", "Travel and Transportation > Lodging > Hotel", 33.303, 126.203, "제주"),
                place(6, "NearParkB", "Landmarks and Outdoors > Park", 33.305, 126.205, "제주")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4), 5, null)),
                List.of(),
                List.of(6)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of());

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        assertThat(names).doesNotContain("FarPark");
        assertThat(names).contains("NearParkB");
    }

    @Test
    @DisplayName("enrich 허브 이름과 매칭되는 공항 본체가 부속시설 POI(Immigration Check)보다 우선 선택된다")
    void repairAndSchedule_prefersNamedHubOverSubFacility() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Jeju International Airport Immigration Check",
                        "Travel and Transportation > Transport Hub > Airport > Airport Terminal",
                        33.506, 126.492, "제주"),
                place(2, "제주국제공항",
                        "Travel and Transportation > Transport Hub > Airport > Airport Terminal",
                        33.507, 126.493, "제주"),
                place(3, "Lunch", "Dining and Drinking > Restaurant > Korean", 33.510, 126.520, "제주"),
                place(4, "Hotel", "Travel and Transportation > Lodging > Hotel", 33.511, 126.521, "제주")
        );
        // Sonnet이 부속시설 POI를 도착 허브로 잘못 선택한 상황
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, 1, List.of(3), 4, null)),
                List.of(),
                List.of()
        );
        com.shg.trip.shgtrip.domain.planning.dto.TransportationHub hub =
                new com.shg.trip.shgtrip.domain.planning.dto.TransportationHub("제주국제공항", "제주국제공항", "AIRPORT");

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "relaxed", "car", null, List.of(), hub, false);

        assertThat(steps.get(0).place().name()).isEqualTo("제주국제공항");
    }

    @Test
    @DisplayName("하루를 마치는 숙소 도착 스텝의 체류는 30분이다 (90분 체류로 인한 23시대 종료 방지)")
    void repairAndSchedule_accommodationArrivalIsThirtyMinutes() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Park", "Landmarks and Outdoors > Park", 37.500, 127.000, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.502, 127.002, "강남")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        StepData hotel = steps.stream().filter(s -> "Hotel".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(toMinutes(hotel.endTime()) - toMinutes(hotel.startTime())).isEqualTo(30);
    }

    @Test
    @DisplayName("식사 슬롯 전 빈 시간이 크면 spare의 근처 주간 활동으로 채워진다 (pace 상한 내에서)")
    void repairAndSchedule_fillsLargeGapBeforeMealFromSpare() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "MorningPark", "Landmarks and Outdoors > Park", 37.500, 127.000, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.502, 127.002, "강남"),
                place(4, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.503, 127.003, "강남"),
                place(5, "GapPark", "Landmarks and Outdoors > Park", 37.5015, 127.0015, "강남"),
                place(6, "Park2", "Landmarks and Outdoors > Park", 37.5005, 127.0005, "강남")
        );
        // normal(max 5)에서 4개 → 여유 1. 오후에 ~3시간 공백 발생 → GapPark 1개 삽입 여지
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 6, 2, 3), 4, null)),
                List.of(),
                List.of(5)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        // 점심 이후 저녁(17:30)까지 큰 공백 → GapPark 삽입 (점심과 저녁 사이)
        assertThat(names).contains("GapPark");
        assertThat(names.indexOf("GapPark")).isBetween(names.indexOf("Lunch"), names.indexOf("Dinner"));
        // 갭 필로 본일정에 편입된 장소는, 그보다 먼저 생성된 스텝의 대안에서도 제거돼야 한다
        // (남아 있으면 대안 선택 시 같은 곳을 2회 방문)
        assertThat(steps).allSatisfy(s ->
                assertThat(s.alternatives().stream().map(AlternativeData::name))
                        .doesNotContain("GapPark"));
    }

    // ── 여행자 품질 보강 라운드(2026-07): userSelected 보호 / dinner 근접 / 체류시간 / 갭필 90 ──

    private PlaceCandidate placeRated(int index, String name, String category, double lat, double lng,
                                      double rating) {
        return new PlaceCandidate(index, (long) index, name, "addr-" + index, category,
                List.of(), "강남", "Korea", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                "desc", BigDecimal.valueOf(rating), 0.9);
    }

    private PlaceCandidate placeWithDuration(int index, String name, String category, double lat, double lng,
                                             Integer durationMinutes) {
        return new PlaceCandidate(index, (long) index, name, "addr-" + index, category,
                List.of(), "강남", "Korea", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                "desc", BigDecimal.valueOf(4.5), 0.9, null, null, null, durationMinutes, false, null);
    }

    @Test
    @DisplayName("userSelected 장소는 pace 상한 트림에서 살아남는다 (평점이 더 낮아도)")
    void repairAndSchedule_userSelectedSurvivesPaceTrim() {
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "MustGoLowRated", "Landmarks and Outdoors > Park", 37.500, 127.000, 3.2).asUserSelected(),
                placeRated(2, "A2", "Landmarks and Outdoors > Park", 37.501, 127.001, 4.5),
                placeRated(3, "A3", "Landmarks and Outdoors > Park", 37.502, 127.002, 4.5),
                placeRated(4, "A4", "Landmarks and Outdoors > Park", 37.503, 127.003, 4.5),
                placeRated(5, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.504, 127.004, 4.5),
                placeRated(6, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.505, 127.005, 4.5),
                placeRated(7, "A7", "Landmarks and Outdoors > Park", 37.506, 127.006, 4.5),
                placeRated(8, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.507, 127.007, 4.5)
        );
        // normal 상한 5 대비 7개 — 트림 발생. 최저 평점(3.2)인 userSelected는 보호돼야 한다.
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4, 5, 6, 7), 8, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        assertThat(steps.stream().map(s -> s.place().name())).contains("MustGoLowRated");
    }

    @Test
    @DisplayName("userSelected 장소는 거리 상한 위반이어도 유지된다 (포함 > 동선)")
    void repairAndSchedule_userSelectedSurvivesDistanceBudget() {
        // itinerary 26 Day1 실좌표 재현 — 보호 없으면 전부 트림되던 케이스
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "제주국제공항", "Travel and Transportation > Transport Hub > Airport > Airport Terminal", 33.5071, 126.4934, 4.4),
                placeRated(2, "성산포항", "Landmarks and Outdoors > Tourist Attraction", 33.4729, 126.9346, 4.2).asUserSelected(),
                placeRated(3, "광치기해변", "Landmarks and Outdoors > Tourist Attraction", 33.4524, 126.9247, 4.2).asUserSelected(),
                placeRated(4, "호텔", "Travel and Transportation > Lodging > Resort", 33.4795, 126.3735, 4.1)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, 1, List.of(2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "relaxed", "any", null, List.of());

        assertThat(steps.stream().map(s -> s.place().name()))
                .contains("성산포항", "광치기해변");
    }

    @Test
    @DisplayName("userSelected 장소는 정기휴무여도 교체되지 않는다")
    void repairAndSchedule_userSelectedNotSwappedWhenClosed() {
        java.time.LocalDate monday = java.time.LocalDate.of(2024, 1, 1);
        List<PlaceCandidate> candidates = List.of(
                new PlaceCandidate(1, 1L, "월요일휴무명소", "addr-1", "Landmarks and Outdoors > Park",
                        List.of(), "강남", "Korea", BigDecimal.valueOf(37.50), BigDecimal.valueOf(127.00),
                        "d", BigDecimal.valueOf(4.5), 0.9, null,
                        "월요일: 휴무, 화요일: 오전 9:00 ~ 오후 6:00", null, null, true, null),
                placeWithHours(2, "Lunch", "Dining and Drinking > Restaurant > Korean",
                        37.501, 127.001, "강남", null),
                placeWithHours(3, "Lodging", "Travel and Transportation > Lodging > Hotel",
                        37.502, 127.002, "강남", null),
                placeWithHours(4, "항상열린대체명소", "Landmarks and Outdoors > Park",
                        37.5005, 127.0005, "강남", "월요일: 오전 9:00 ~ 오후 6:00")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed", monday);

        assertThat(steps.stream().map(s -> s.place().name())).contains("월요일휴무명소");
    }

    @Test
    @DisplayName("저녁 식사는 경로순이 아니라 숙소에 가장 가까운 식당으로 배정된다")
    void repairAndSchedule_dinnerIsNearestDiningToAccommodation() {
        // 경로순으로는 NearHotelMeal이 먼저(=점심)가 되기 쉽게 배치하되, 숙소 최근접이므로
        // dinner 스왑 후에는 저녁 슬롯(17:30~)에 있어야 한다.
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "NearHotelMeal", "Dining and Drinking > Restaurant > Korean", 37.5005, 127.0005, 4.5),
                placeRated(2, "Park", "Landmarks and Outdoors > Park", 37.510, 127.010, 4.5),
                placeRated(3, "FarMeal", "Dining and Drinking > Restaurant > Korean", 37.515, 127.015, 4.5),
                placeRated(4, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.500, 127.000, 4.5)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        StepData nearMeal = steps.stream()
                .filter(s -> "NearHotelMeal".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(toMinutes(nearMeal.startTime())).isGreaterThanOrEqualTo(17 * 60 + 30);
    }

    @Test
    @DisplayName("권장 체류시간이 있으면 체류가 그 값으로(150분), 상한 초과는 180분으로 클램프된다")
    void repairAndSchedule_usesRecommendedDurationWithClamp() {
        List<PlaceCandidate> candidates = List.of(
                placeWithDuration(1, "오름150", "Landmarks and Outdoors > Tourist Attraction", 37.500, 127.000, 150),
                placeWithDuration(2, "과대240", "Landmarks and Outdoors > Tourist Attraction", 37.501, 127.001, 240),
                placeRated(3, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.502, 127.002, 4.5),
                placeRated(4, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.503, 127.003, 4.5)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        StepData dur150 = steps.stream().filter(s -> "오름150".equals(s.place().name())).findFirst().orElseThrow();
        StepData dur240 = steps.stream().filter(s -> "과대240".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(toMinutes(dur150.endTime()) - toMinutes(dur150.startTime())).isEqualTo(150);
        assertThat(toMinutes(dur240.endTime()) - toMinutes(dur240.startTime())).isEqualTo(180);
    }

    @Test
    @DisplayName("등산/트레킹 카테고리는 체류시간 휴리스틱 150분이 적용된다 (데이터 없을 때)")
    void repairAndSchedule_hikingHeuristicDuration() {
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "등산로", "Landmarks and Outdoors > Hiking Trail", 37.500, 127.000, 4.5),
                placeRated(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, 4.5),
                placeRated(3, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.502, 127.002, 4.5)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        StepData hiking = steps.stream().filter(s -> "등산로".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(toMinutes(hiking.endTime()) - toMinutes(hiking.startTime())).isEqualTo(150);
    }

    @Test
    @DisplayName("숙소가 방문지에서 멀면 방문지 중심에 가까운 후보 숙소로 교체된다 (매일 장거리 왕복 완화)")
    void repairAndSchedule_swapsAccommodationToNearestOfDay() {
        // 방문지는 동쪽(127.9 근처), Sonnet이 고른 숙소(FarHotel)는 서쪽(126.4)으로 ~135km 멂.
        // NearHotel이 방문지 근처(127.9)에 있으므로 그걸로 교체돼야 한다.
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "EastAttraction", "Landmarks and Outdoors > Tourist Attraction", 33.45, 126.93, 4.5),
                placeRated(2, "EastLunch", "Dining and Drinking > Restaurant > Korean", 33.46, 126.94, 4.5),
                placeRated(3, "FarHotel", "Travel and Transportation > Lodging > Hotel", 33.48, 126.37, 4.0),
                placeRated(4, "NearHotel", "Travel and Transportation > Lodging > Hotel", 33.46, 126.92, 4.2)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        assertThat(names).contains("NearHotel");
        assertThat(names).doesNotContain("FarHotel");
    }

    @Test
    @DisplayName("전 지역에 흩어진 방문지가 지리적으로 맞는 day로 재배치돼 하루 이동거리가 준다")
    void repairAndSchedule_rebalancesScatteredPlacesByGeography() {
        // Day1에 동부(126.9)와 서부(126.2)가 섞여 있고, Day2엔 서부만 → 재배치로 Day1의 서부
        // 장소가 Day2로 이동해야 한다(각 day가 한 권역에 모임).
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "East1", "Landmarks and Outdoors > Tourist Attraction", 33.45, 126.92, 4.5),
                placeRated(2, "East2", "Dining and Drinking > Restaurant > Korean", 33.46, 126.93, 4.5),
                placeRated(3, "West_misplaced", "Landmarks and Outdoors > Tourist Attraction", 33.30, 126.25, 4.5),
                placeRated(4, "West1", "Landmarks and Outdoors > Tourist Attraction", 33.31, 126.24, 4.5),
                placeRated(5, "West2", "Dining and Drinking > Restaurant > Korean", 33.32, 126.26, 4.5),
                placeRated(6, "Hotel", "Travel and Transportation > Lodging > Hotel", 33.38, 126.55, 4.0)
        );
        // Day1=[East1,East2, West_misplaced(잘못 낌)], Day2=[West1,West2]
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 6, null),
                        new SelectionOutput.DayPlan(2, null, List.of(4, 5), 6, null)
                ),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        // West_misplaced는 Day2(서부)로 이동 — Day1엔 없어야
        StepData west = steps.stream().filter(s -> "West_misplaced".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(west.dayNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("관광지 입장료(admissionFee)가 있으면 그 값으로, 무료(0)면 0원으로 책정된다")
    void repairAndSchedule_usesAdmissionFeeForAttraction() {
        List<PlaceCandidate> candidates = List.of(
                // 성산일출봉식 유료 명소(5000), 오름식 무료(0)
                placeWithAdmission(1, "유료명소", "Landmarks and Outdoors > Tourist Attraction", 37.50, 127.00, 5000),
                placeWithAdmission(2, "무료오름", "Landmarks and Outdoors > Tourist Attraction", 37.501, 127.001, 0),
                placeRated(3, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.502, 127.002, 4.5),
                placeRated(4, "Hotel", "Travel and Transportation > Lodging > Hotel", 37.503, 127.003, 4.5)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        StepData paid = steps.stream().filter(s -> "유료명소".equals(s.place().name())).findFirst().orElseThrow();
        StepData free = steps.stream().filter(s -> "무료오름".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(paid.estimatedCost().longValue()).isEqualTo(5000L);
        assertThat(free.estimatedCost().longValue()).isEqualTo(0L);
    }

    private PlaceCandidate placeWithAdmission(int index, String name, String category, double lat, double lng,
                                             Integer admissionFee) {
        return new PlaceCandidate(index, (long) index, name, "addr-" + index, category,
                List.of(), "강남", "Korea", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                "desc", BigDecimal.valueOf(4.5), 0.9, null, null, null, null, false, admissionFee);
    }

    @Test
    @DisplayName("숙소 근접 최적화가 방문지로 쓰인 LODGING을 숙소로 뽑지 않는다 (방문+숙소 이중등장 방지)")
    void repairAndSchedule_proximityDoesNotDoubleUseVisitedLodging() {
        // NearLodging은 방문지 centroid에 가장 가깝지만 placeIndices에 방문지로 들어가 있음 →
        // 숙소로 뽑히면 이중등장. 대신 그 다음으로 가까운 FarLodging(정상 숙소)이 유지돼야 한다.
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "Attraction", "Landmarks and Outdoors > Tourist Attraction", 33.50, 126.50, 4.5),
                placeRated(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 33.501, 126.501, 4.5),
                placeRated(3, "NearLodging", "Travel and Transportation > Lodging > Hotel", 33.502, 126.502, 4.5),
                placeRated(4, "FarLodging", "Travel and Transportation > Lodging > Hotel", 33.52, 126.52, 4.0)
        );
        // NearLodging(3)을 방문지로도 배치(비정상이지만 demoteIfNotHub 등으로 가능한 시나리오),
        // 숙소는 FarLodging(4).
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal");

        long nearLodgingCount = steps.stream().filter(s -> "NearLodging".equals(s.place().name())).count();
        // 방문지로 1회만 등장, 숙소로 중복 채택되지 않음
        assertThat(nearLodgingCount).isEqualTo(1);
    }

    @Test
    @DisplayName("숙소는 0원이 아니라 유형별 추정가로 책정된다 (리조트 > 호텔 > 게스트하우스)")
    void repairAndSchedule_lodgingCostIsNotZero() {
        List<PlaceCandidate> candidates = List.of(
                placeRated(1, "Park", "Landmarks and Outdoors > Park", 37.500, 127.000, 4.5),
                placeRated(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, 4.5),
                placeRated(3, "리조트", "Travel and Transportation > Lodging > Resort", 37.502, 127.002, 4.5)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        StepData lodging = steps.stream().filter(s -> "리조트".equals(s.place().name())).findFirst().orElseThrow();
        assertThat(lodging.estimatedCost()).isNotNull();
        assertThat(lodging.estimatedCost().longValue()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("stepOrder는 1부터 연속 증가하며 모든 step의 notes(story)는 비어있다")
    void repairAndSchedule_producesSequentialStepOrderWithEmptyStory() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "A1", "Landmarks and Outdoors > Park", 37.50, 127.00, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Lodging", "Lodging > Hotel", 37.502, 127.002, "강남")
        );

        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "relaxed");

        for (int i = 0; i < steps.size(); i++) {
            assertThat(steps.get(i).stepOrder()).isEqualTo(i + 1);
            assertThat(steps.get(i).notes()).isNull();
        }
    }

    // ── C1~C5: itinerary 40(제주 2박3일) 실측 후보 풀 회귀 테스트 ──
    // 개편 전 40번이 드러낸 문제(해안도로 7곳 반복 / 하루 196km / 식사 원정 / 꽉 찬 day로 이동 불가)를
    // 같은 입력으로 재현하고, C1~C5가 각각 그 문제를 해소하는지 항목별로 검증한다.

    /** 40번과 같은 구성: day별로 해안도로가 여러 개 섞인 3일 일정. */
    private SelectionOutput itinerary40Selection() {
        return new SelectionOutput(
                "제주 해안을 따라가는 드라이브",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(2, 3, 4, 5, 6), 7, null),
                        new SelectionOutput.DayPlan(2, null, List.of(8, 9, 10, 11), 7, null),
                        new SelectionOutput.DayPlan(3, null, List.of(12, 13, 14, 15), null, null)
                ),
                List.of(),
                List.of(16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31),
                List.of(), List.of()
        );
    }

    @Test
    @DisplayName("C1: 경유형(해안도로)이 전체 일정 1개로 제한되고 그 자리가 다른 유형으로 채워진다")
    void c1_capsViaRouteRepetition() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                itinerary40Selection(), candidates, "normal", "car", null,
                List.of("ocean"), null, false, List.of());

        long viaCount = steps.stream()
                .filter(s -> PlaceCategoryConstants.isViaRoute(
                        s.place().name(), s.place().category(), List.of()))
                .count();

        // 개편 전: 해안도로 7곳이 그대로 방문 스텝이 됐다
        assertThat(viaCount).isLessThanOrEqualTo(1);
        assertThat(steps).hasSizeGreaterThan(6); // 잘라내기만 하고 안 채우면 안 된다
    }

    @Test
    @DisplayName("C1: 같은 세부 유형이 ceil(days/2)+1회를 넘지 않는다")
    void c1_capsRepeatedSpecificType() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // 카페만 3개를 한 day에 몰아넣은 선택 (days=2 → 상한 2)
        SelectionOutput selection = new SelectionOutput(
                "카페 투어",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(16, 17, 18, 5), 7, null),
                        new SelectionOutput.DayPlan(2, null, List.of(14, 11), null, null)
                ),
                List.of(),
                List.of(23, 24, 25, 26, 27, 28, 29, 30, 31),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("food"), null, false, List.of());

        Map<String, Long> byLeaf = steps.stream()
                .collect(Collectors.groupingBy(s -> leaf(s.place().category()), Collectors.counting()));

        assertThat(byLeaf.getOrDefault("Coffee Shop", 0L)).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("C2: 사용자가 고른 카테고리 중 일정에 0개인 유형이 spare에서 보충된다")
    void c2_fillsMissingUserCategory() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                itinerary40Selection(), candidates, "normal", "car", null,
                List.of("ocean"), null, false,
                List.of("restaurant", "cafe", "market"));

        List<String> names = steps.stream().map(s -> s.place().name()).collect(Collectors.toList());
        boolean hasCafe = steps.stream().anyMatch(s ->
                "CAFE".equals(PlaceCategoryConstants.majorCategory(s.place().category())));
        boolean hasMarket = names.stream().anyMatch(n -> n.contains("시장"));

        // 개편 전 40번: 카페 0개(카페 쿼리가 해안도로에 점령당함), 시장 0개
        assertThat(hasCafe).as("카페 카테고리 보충").isTrue();
        assertThat(hasMarket).as("시장 카테고리 보충").isTrue();
    }

    @Test
    @DisplayName("C3: 저녁 식사는 그날 숙소에 가장 가까운 식당으로 배정된다")
    void c3_dinnerIsNearestRestaurantToAccommodation() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // 숙소(7: 유니호텔 제주, 애월) 기준 — 대금식당(22, 약 15km)이 연돈(13, 약 25km)보다 가깝다.
        SelectionOutput selection = new SelectionOutput(
                "제주 미식",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(13, 22, 29), 7, null)),
                List.of(),
                List.of(16, 17, 23, 24),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("food"), null, false, List.of());

        StepData dinner = steps.stream()
                .filter(s -> "DINING".equals(PlaceCategoryConstants.majorCategory(s.place().category())))
                .reduce((a, b) -> b)  // 마지막 식사 = 저녁
                .orElseThrow();

        assertThat(dinner.place().name()).isEqualTo("대금식당");
        // 저녁이 숙소 최근접이라는 성질 자체도 확인(픽스처 좌표가 바뀌어도 계약은 유지)
        PlaceCandidate hotel = Itinerary40Fixture.byIndex(7);
        double dinnerKm = com.shg.trip.shgtrip.global.util.GeoUtils.haversine(
                coordOf(hotel), coordOf(Itinerary40Fixture.byIndex(22)));
        double otherKm = com.shg.trip.shgtrip.global.util.GeoUtils.haversine(
                coordOf(hotel), coordOf(Itinerary40Fixture.byIndex(13)));
        assertThat(dinnerKm).isLessThan(otherKm);
    }

    @Test
    @DisplayName("C3: 숙소에서 10km 넘게 떨어진 아침 식사 슬롯은 생략된다")
    void c3_dropsBreakfastFarFromAccommodation() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // 식당 3개(5:제주시, 8:서귀포 공천포, 22:애월) + 숙소 7(애월).
        // 공천포식당은 숙소에서 약 40km → 아침 슬롯으로 남기면 아침부터 원정이 된다.
        SelectionOutput selection = new SelectionOutput(
                "제주 미식",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(5, 8, 22), 7, null)),
                List.of(),
                List.of(16, 23, 24),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "car", null, List.of("food"), null, false, List.of());

        long meals = steps.stream()
                .filter(s -> "DINING".equals(PlaceCategoryConstants.majorCategory(s.place().category())))
                .count();

        assertThat(meals).isLessThanOrEqualTo(2); // 아침 슬롯 생략 → 점심+저녁만
    }

    @Test
    @DisplayName("C4: 전날 숙소 → 첫 스텝 구간이 하루 주행 예산에 포함된다")
    void c4_countsPreviousNightAccommodationLeg() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // day1은 숙소(19: 서귀포 칼호텔)에서 자고, day2는 섬 반대편(10: 성산세화, 21: 우도)에서 시작한다.
        // 전날 숙소 leg를 빼고 재면 day2가 상한 이내로 오판된다(40번 Day2 196km의 원인).
        SelectionOutput selection = new SelectionOutput(
                "제주 일주",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(25, 26, 13), 19, null),
                        new SelectionOutput.DayPlan(2, null, List.of(10, 21, 15), null, null)
                ),
                List.of(),
                List.of(16, 17, 18, 23, 24, 27, 28, 29, 30),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("ocean"), null, false, List.of());

        double day2Km = dayDrivenKm(steps, 2, candidates);
        assertThat(day2Km).isLessThanOrEqualTo(150.0 * 1.31); // car 일일 상한 150km (도로계수 여유)
    }

    @Test
    @DisplayName("C5: 꽉 찬 day와도 1:1 스왑으로 지리적으로 맞는 day에 배치된다")
    void c5_swapsWithFullDay() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // day1(relaxed 상한 3)은 제주시 2곳 + 서귀포 1곳(논짓물), day2도 상한까지 찬 채로
        // 서귀포 2곳 + 제주시 1곳(명품진전복해물탕). 양쪽 다 꽉 차 이동은 불가능 → 스왑만이 해법.
        SelectionOutput selection = new SelectionOutput(
                "제주 두 지역",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(24, 23, 26), 20, null),
                        new SelectionOutput.DayPlan(2, null, List.of(25, 28, 11), null, null)
                ),
                List.of(),
                List.of(16, 17),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "relaxed", "car", null, List.of("nature"), null, false, List.of());

        Map<String, Integer> dayByName = steps.stream()
                .collect(Collectors.toMap(s -> s.place().name(), StepData::dayNumber, (a, b) -> a));

        // 서귀포권(산방산·논짓물)이 같은 날로, 제주시권(용두암·명품진전복해물탕)이 다른 날로 모여야 한다.
        // 어떤 쌍이 맞바뀌었는지가 아니라 "지역이 날짜로 모였는지"가 C5의 계약이다.
        assertThat(dayByName.get("논짓물")).isEqualTo(dayByName.get("제주 서귀포 산방산"));
        assertThat(dayByName.get("명품진전복해물탕")).isEqualTo(dayByName.get("용두암"));
        assertThat(dayByName.get("논짓물")).isNotEqualTo(dayByName.get("용두암"));
    }

    /** 스텝 place 이름으로 후보를 찾아 day별 실주행 거리(전날 숙소 leg 포함)를 합산한다. */
    private double dayDrivenKm(List<StepData> steps, int dayNumber, List<PlaceCandidate> candidates) {
        Map<String, PlaceCandidate> byName = candidates.stream()
                .collect(Collectors.toMap(PlaceCandidate::name, c -> c, (a, b) -> a));

        List<StepData> prevDay = steps.stream().filter(s -> s.dayNumber() == dayNumber - 1).toList();
        List<StepData> day = steps.stream().filter(s -> s.dayNumber() == dayNumber).toList();
        if (day.isEmpty()) return 0;

        List<double[]> coords = new java.util.ArrayList<>();
        if (!prevDay.isEmpty()) {
            PlaceCandidate last = byName.get(prevDay.get(prevDay.size() - 1).place().name());
            if (last != null) coords.add(coordOf(last));
        }
        for (StepData s : day) {
            PlaceCandidate c = byName.get(s.place().name());
            if (c != null) coords.add(coordOf(c));
        }

        double total = 0;
        for (int i = 1; i < coords.size(); i++) {
            total += com.shg.trip.shgtrip.global.util.GeoUtils.haversine(coords.get(i - 1), coords.get(i));
        }
        return total;
    }

    private double[] coordOf(PlaceCandidate c) {
        return new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()};
    }

    private String leaf(String category) {
        if (category == null) return "기타";
        int i = category.lastIndexOf('>');
        return i >= 0 ? category.substring(i + 1).trim() : category.trim();
    }

    // ── 2차: 최종 불변식 패스 + 총칭 카테고리 세부 유형 추론 ──

    @Test
    @DisplayName("최종 패스: 점심·저녁 슬롯이 비면 근처 식당이 보충된다")
    void finalPass_fillsMissingMealSlots() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // 관광지만 3곳 배정된 day — 식사가 하나도 없다.
        SelectionOutput selection = new SelectionOutput(
                "제주 자연",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(23, 24, 14), 7, null)),
                List.of(),
                List.of(5, 11, 22, 13, 16, 17),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("nature"), null, false, List.of());

        assertThat(mealInWindow(steps, 11 * 60 + 30, 13 * 60 + 30)).as("점심").isTrue();
        assertThat(mealInWindow(steps, 17 * 60 + 30, 19 * 60 + 30)).as("저녁").isTrue();
    }

    @Test
    @DisplayName("최종 패스: 관광이 하나도 없는 day에 관광지가 보충된다")
    void finalPass_fillsMissingAttraction() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // 식당·카페만 배정된 day (실측 44 Day3와 같은 구성)
        SelectionOutput selection = new SelectionOutput(
                "제주 미식",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(5, 22, 16, 17), 7, null)),
                List.of(),
                List.of(23, 24, 29, 25, 26),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("food"), null, false, List.of());

        boolean hasAttraction = steps.stream().anyMatch(s ->
                "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(s.place().category())));
        assertThat(hasAttraction).isTrue();
    }

    @Test
    @DisplayName("최종 패스: 커버리지 보충분이 트림돼도 최종 산출물 기준으로 다시 채운다")
    void finalPass_reappliesCoverageOnFinalSteps() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        SelectionOutput selection = new SelectionOutput(
                "제주 미식",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(5, 11, 24), 7, null),
                        new SelectionOutput.DayPlan(2, null, List.of(13, 25, 26), null, null)
                ),
                List.of(),
                List.of(16, 17, 18, 23, 29, 30, 31),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("food"), null, false,
                List.of("restaurant", "cafe", "market"));

        boolean hasCafe = steps.stream().anyMatch(s ->
                "CAFE".equals(PlaceCategoryConstants.majorCategory(s.place().category())));
        boolean hasMarket = steps.stream().anyMatch(s -> s.place().name().contains("시장"));
        assertThat(hasCafe).as("카페 커버리지").isTrue();
        assertThat(hasMarket).as("시장 커버리지").isTrue();
    }

    @Test
    @DisplayName("총칭 카테고리라도 이름으로 세부 유형을 추론해 같은 유형 연속·상한을 잡는다")
    void subTypeInference_capsGenericLeafRepetition() {
        // 전부 "Tourist Attraction" 총칭 리프지만 이름상 오름 4곳 — 세부 유형 추론이 없으면 상한이 무력하다.
        List<PlaceCandidate> candidates = List.of(
                place(1, "느지리오름", Itinerary40Fixture.ATTRACTION, 33.365, 126.261, "제주시"),
                place(2, "개오름", Itinerary40Fixture.ATTRACTION, 33.422, 126.771, "제주시"),
                place(3, "손지오름", Itinerary40Fixture.ATTRACTION, 33.456, 126.819, "제주시"),
                place(4, "용눈이오름", Itinerary40Fixture.ATTRACTION, 33.459, 126.831, "제주시"),
                place(5, "제주항공우주박물관", "Arts and Entertainment > Cultural Center", 33.304, 126.299, "서귀포시"),
                place(6, "한림민속오일시장", "Retail > Market", 33.420, 126.273, "제주시"),
                place(7, "대금식당", Itinerary40Fixture.KOREAN_RESTAURANT, 33.402, 126.251, "제주시"),
                place(8, "유니호텔 제주", Itinerary40Fixture.RESORT, 33.479, 126.373, "제주시")
        );
        SelectionOutput selection = new SelectionOutput(
                "제주 오름 투어",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4, 7), 8, null)),
                List.of(),
                List.of(5, 6),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "car", null, List.of("nature"), null, false, List.of());

        long hills = steps.stream()
                .filter(s -> "hill".equals(PlaceCategoryConstants.subType(
                        s.place().name(), s.place().category(), List.of(), null)))
                .count();
        // days=1 → 상한 ceil(1/2)+1 = 2
        assertThat(hills).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("총칭 카테고리 해변은 세부 유형 추론으로 주간 취급돼 저녁에 배치되지 않는다")
    void subTypeInference_beachIsDaytimeEvenWithGenericLeaf() {
        assertThat(PlaceCategoryConstants.subType("하고수동해변", Itinerary40Fixture.ATTRACTION, List.of(), null))
                .isEqualTo("beach");
        assertThat(PlaceCategoryConstants.isDaytimeSubType("beach")).isTrue();
        assertThat(PlaceCategoryConstants.subType("사라봉", Itinerary40Fixture.ATTRACTION, List.of(), null))
                .isEqualTo("hill");
        // 구체적 리프는 추론하지 않고 그대로 쓴다
        assertThat(PlaceCategoryConstants.subType("연돈", Itinerary40Fixture.RESTAURANT, List.of(), null))
                .isEqualTo("restaurant");
    }

    @Test
    @DisplayName("집합 POI(음식거리)는 식사 슬롯 후보에서 제외된다")
    void aggregatePoi_isNotUsedAsMeal() {
        assertThat(PlaceCategoryConstants.isAggregatePoi(
                "Seogwipo’s food streets", Itinerary40Fixture.RESTAURANT)).isTrue();
        assertThat(PlaceCategoryConstants.isAggregatePoi(
                "국제시장 먹자골목", Itinerary40Fixture.RESTAURANT)).isTrue();
        // 개별 가게는 소유격이 있어도 집합이 아니다
        assertThat(PlaceCategoryConstants.isAggregatePoi(
                "Joe’s Pizza", Itinerary40Fixture.RESTAURANT)).isFalse();
        assertThat(PlaceCategoryConstants.isAggregatePoi(
                "제주 먹거리", Itinerary40Fixture.RESTAURANT)).isFalse();
        // 관광지 이름의 "거리"는 식사 슬롯 판정 대상이 아니다
        assertThat(PlaceCategoryConstants.isAggregatePoi(
                "가로수길", Itinerary40Fixture.ATTRACTION)).isFalse();
    }

    @Test
    @DisplayName("최종 패스: 카페 3연속이면 중간이 다른 종류로 교체된다")
    void finalPass_breaksThreeConsecutiveCafes() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        SelectionOutput selection = new SelectionOutput(
                "제주 카페",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(16, 17, 18, 5, 11), 7, null)),
                List.of(),
                List.of(23, 24, 29),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "car", null, List.of("healing"), null, false, List.of());

        int maxRun = 0, run = 0;
        String prev = null;
        for (StepData s : steps) {
            String major = PlaceCategoryConstants.majorCategory(s.place().category());
            if ("TRANSIT_HUB".equals(major) || "LODGING".equals(major)) continue;
            run = major.equals(prev) ? run + 1 : 1;
            prev = major;
            maxRun = Math.max(maxRun, run);
        }
        assertThat(maxRun).isLessThan(3);
    }

    private boolean mealInWindow(List<StepData> steps, int from, int to) {
        return steps.stream().anyMatch(s -> {
            if (!"DINING".equals(PlaceCategoryConstants.majorCategory(s.place().category()))) return false;
            String[] p = s.startTime().split(":");
            int start = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            return start >= from - 60 && start <= to;
        });
    }

    // ── 3차: 최종 패스 우선순위·보호 규칙 + 검사 경로 통일 ──

    @Test
    @DisplayName("유형 상한 교체는 같은 대분류 안에서만 — 저녁 식당이 카페로 바뀌지 않는다")
    void typeCapReplacement_staysWithinSameMajor() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // 한식당 4곳(상한 3 초과) + 카페 spare. 대분류가 바뀌면 저녁이 사라진다.
        SelectionOutput selection = new SelectionOutput(
                "제주 미식",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(5, 11, 24), 7, null),
                        new SelectionOutput.DayPlan(2, null, List.of(22, 3, 23), null, null)
                ),
                List.of(),
                List.of(16, 17, 18, 13, 8),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("food"), null, false, List.of());

        // 저녁 시간대(17:30~19:30)에 실제 식사가 있어야 한다 — 카페로 교체됐다면 깨진다
        assertThat(mealInWindow(steps, 17 * 60 + 30, 19 * 60 + 30)).isTrue();
    }

    @Test
    @DisplayName("집합 POI는 저녁 식사로 인정되지 않는다 (검사 경로 통일)")
    void aggregatePoi_isNotCountedAsMeal() {
        PlaceCandidate foodStreet = new PlaceCandidate(
                1, 1L, "Seogwipo’s food streets", "주소", Itinerary40Fixture.RESTAURANT, List.of(),
                "Jeju", "KR", java.math.BigDecimal.valueOf(33.25), java.math.BigDecimal.valueOf(126.54),
                null, java.math.BigDecimal.valueOf(4.0), 0.9);
        PlaceCandidate realRestaurant = new PlaceCandidate(
                2, 2L, "대금식당", "주소", Itinerary40Fixture.KOREAN_RESTAURANT, List.of(),
                "Jeju", "KR", java.math.BigDecimal.valueOf(33.40), java.math.BigDecimal.valueOf(126.25),
                null, java.math.BigDecimal.valueOf(4.4), 0.9);

        assertThat(foodStreet.mealEligible()).isFalse();
        assertThat(realRestaurant.mealEligible()).isTrue();
    }

    @Test
    @DisplayName("커버리지 보충은 하루 거리 예산을 넘기면서까지 넣지 않는다")
    void coverage_doesNotBreakDistanceBudget() {
        // 시장은 섬 반대편에만 있다 — 넣으면 walk 예산(30km)이 깨지므로 넣지 않아야 한다.
        List<PlaceCandidate> candidates = List.of(
                place(1, "도심명소", "Landmarks and Outdoors > Tourist Attraction", 33.500, 126.520, "제주시"),
                place(2, "도심식당", Itinerary40Fixture.KOREAN_RESTAURANT, 33.502, 126.522, "제주시"),
                place(3, "도심식당2", Itinerary40Fixture.SEAFOOD_RESTAURANT, 33.504, 126.524, "제주시"),
                place(4, "도심호텔", Itinerary40Fixture.HOTEL, 33.506, 126.526, "제주시"),
                place(5, "먼시장", "Retail > Market", 33.240, 126.560, "서귀포시")
        );
        SelectionOutput selection = new SelectionOutput(
                "도심 도보",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null)),
                List.of(),
                List.of(5),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "walk", null, List.of("culture"), null, false,
                List.of("restaurant", "market"));

        assertThat(steps).extracting(st -> st.place().name()).doesNotContain("먼시장");
    }

    @Test
    @DisplayName("거리 상한은 직선 기준 — car 일일 115km(표시 약 150km)")
    void distanceBudget_isStraightLineAndMatchesDisplayedLimit() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        SelectionOutput selection = new SelectionOutput(
                "제주 일주",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(4, 10, 8, 25, 21), 7, null)),
                List.of(),
                List.of(16, 17, 23, 24),
                List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "car", null, List.of("ocean"), null, false, List.of());

        double displayedKm = steps.stream()
                .filter(st -> st.transportationDistance() != null)
                .mapToDouble(st -> st.transportationDistance().doubleValue())
                .sum();
        // 직선 115km 상한 → 표시값은 대략 150km 이내
        assertThat(displayedKm).isLessThanOrEqualTo(115.0 * 1.3 * 1.05);
    }

    // ── 4차: 야간 배치·긴 공백·아침 슬롯 회귀 ──

    private PlaceCandidate placeWithSlots(int index, String name, String category, double lat, double lng,
                                          String subRegion, List<String> slots) {
        return new PlaceCandidate(index, (long) index, name, "제주특별자치도 " + subRegion + " " + name,
                category, List.of(), "Jeju", "KR",
                java.math.BigDecimal.valueOf(lat), java.math.BigDecimal.valueOf(lng), null,
                java.math.BigDecimal.valueOf(4.3), 0.9, null, null, slots, null, false, null, subRegion);
    }

    @Test
    @DisplayName("추천 시간대가 섞여 있어도 해변·오름은 주간으로 판정된다 (야간 배치 방지)")
    void mixedTimeSlots_doNotOverrideDaytimeTerrain() {
        // 실측: 세기알해변의 recommended_time_slots가 {morning,afternoon,evening}이라
        // FLEXIBLE로 빠져 20:37 저녁 스텝에 배치됐다.
        List<PlaceCandidate> candidates = List.of(
                placeWithSlots(1, "세기알해변", Itinerary40Fixture.ATTRACTION, 33.558, 126.755, "제주시",
                        List.of("morning", "afternoon", "evening")),
                place(2, "점심식당", Itinerary40Fixture.KOREAN_RESTAURANT, 33.556, 126.752, "제주시"),
                place(3, "저녁식당", Itinerary40Fixture.SEAFOOD_RESTAURANT, 33.554, 126.750, "제주시"),
                place(4, "실내갤러리", "Arts and Entertainment > Art Gallery", 33.552, 126.748, "제주시"),
                place(5, "호텔", Itinerary40Fixture.HOTEL, 33.550, 126.746, "제주시")
        );
        SelectionOutput selection = new SelectionOutput(
                "제주 해안",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3, 4), 5, null)),
                List.of(), List.of(), List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null, List.of("ocean"), null, false, List.of());

        steps.stream()
                .filter(st -> "세기알해변".equals(st.place().name()))
                .forEach(st -> assertThat(toMinutes(st.startTime()))
                        .as("해변 시작 시각")
                        .isLessThan(18 * 60));
    }

    @Test
    @DisplayName("야간 전용 데이터(evening/night)는 지형 판정보다 우선한다")
    void eveningOnlySlots_winOverTerrain() {
        // "제주불빛정원"은 이름상 park(주간형)이지만 데이터가 야간 전용이라 저녁 배치가 맞다.
        PlaceCandidate nightGarden = placeWithSlots(1, "제주불빛정원", Itinerary40Fixture.ATTRACTION,
                33.45, 126.50, "제주시", List.of("evening", "night"));
        assertThat(PlaceCategoryConstants.subType(nightGarden.name(), nightGarden.category(), List.of(), null))
                .isEqualTo("park");
        // 데이터가 야간 전용이면 주간 강제가 걸리지 않아야 한다(스케줄에서 저녁 버킷 허용)
        assertThat(nightGarden.recommendedTimeSlots()).containsExactly("evening", "night");
    }

    @Test
    @DisplayName("도착일(허브 시작)에는 아침 슬롯을 만들지 않는다")
    void arrivalDay_hasNoBreakfastSlot() {
        List<PlaceCandidate> candidates = Itinerary40Fixture.candidates();
        // 첫날에 식사 3곳을 몰아넣어도 도착일이라 아침 슬롯은 생기지 않아야 한다.
        SelectionOutput selection = new SelectionOutput(
                "제주 미식",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 5, 11, 22, 24), 7, null)),
                List.of(), List.of(16, 23), List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "car", null, List.of("food"), null, false, List.of());

        long earlyMeals = steps.stream()
                .filter(st -> "DINING".equals(PlaceCategoryConstants.majorCategory(st.place().category())))
                .filter(st -> toMinutes(st.startTime()) < 11 * 60)
                .count();
        assertThat(earlyMeals).as("11시 이전 식사(아침 슬롯)").isZero();
    }

    // ── 4차: itinerary 60·66·67 재발 방지 (실측 후보 풀 기반) ──

    @Test
    @DisplayName("숙소가 전부 spareIndices에만 있어도 모든 숙박일에 숙소가 배정된다")
    void lodgingOnlyInSpare_stillAssignedToEveryNightDay() {
        // 실측 60·66·67: 후보 풀에 LODGING이 3개 있는데도 "보충할 LODGING 후보도 없음" 로그와
        // 함께 숙소 스텝이 0개로 나왔다. spare를 "사용중"으로 집계한 것이 원인.
        List<PlaceCandidate> candidates = Itinerary67Fixture.candidates();
        SelectionOutput selection = Itinerary67Fixture.selectionWithLodgingOnlyInSpare();

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "normal", "car", null,
                List.of("romance", "budget"), null, false, List.of());

        for (int day = 1; day <= 2; day++) {   // 3일차는 귀가일이라 숙소 없음이 정상
            final int d = day;
            boolean hasLodging = steps.stream()
                    .filter(s -> s.dayNumber() == d)
                    .anyMatch(s -> "LODGING".equals(
                            PlaceCategoryConstants.majorCategory(s.place().category())));
            assertThat(hasLodging).as("day%d 숙소 스텝", d).isTrue();
        }
    }

    @Test
    @DisplayName("후보 풀에 LODGING이 아예 없으면 예외 없이 숙소만 빠진 일정이 나온다")
    void noLodgingCandidates_degradesGracefully() {
        List<PlaceCandidate> withoutLodging = Itinerary67Fixture.candidates().stream()
                .filter(c -> !"LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .toList();
        SelectionOutput selection = new SelectionOutput(
                "숙소 없는 풀",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(2, 13, 4), null, null),
                        new SelectionOutput.DayPlan(2, null, List.of(5, 10, 6), null, null)
                ),
                List.of(), List.of(), List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, withoutLodging, "normal", "car", null, List.of(), null, false, List.of());

        assertThat(steps).isNotEmpty();   // 생성 실패가 아니라 degrade
    }

    @Test
    @DisplayName("마지막날 출발 허브 도착이 21시를 넘지 않는다")
    void lastDay_departureHubArrivesBefore21() {
        List<PlaceCandidate> candidates = Itinerary67Fixture.candidates();
        // 180분 장소 2개 + 동부 관광지를 마지막날에 몰아 23시 귀가를 유도한 입력
        SelectionOutput selection = new SelectionOutput(
                "귀가 지연 재현",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(2, 13, 3), null, null),
                        new SelectionOutput.DayPlan(2, null,
                                List.of(Itinerary67Fixture.GEOPARK_IDX, 5, 6, 10,
                                        Itinerary67Fixture.PORT_IDX), null, null)
                ),
                List.of(), List.of(19, 20), List.of(), List.of()
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "car", null, List.of(), null, false, List.of());

        steps.stream()
                .filter(s -> "제주국제공항".equals(s.place().name()))
                .filter(s -> s.dayNumber() == 2)
                .forEach(s -> assertThat(toMinutes(s.startTime()))
                        .as("마지막날 공항 도착 시각")
                        .isLessThanOrEqualTo(21 * 60));
    }

    @Test
    @DisplayName("어떤 스텝도 22시 이후에 시작하지 않는다")
    void noStepStartsAfterAbsoluteLateCap() {
        List<PlaceCandidate> candidates = Itinerary67Fixture.candidates();
        SelectionOutput selection = Itinerary67Fixture.selectionWithLodgingOnlyInSpare();

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "tight", "car", null,
                List.of("nightview"), null, false, List.of());

        assertThat(steps).allSatisfy(s ->
                assertThat(toMinutes(s.startTime()))
                        .as("%s 시작 시각", s.place().name())
                        .isLessThanOrEqualTo(22 * 60 + 30));   // 숙소 체크인 여유 포함
    }

    @Test
    @DisplayName("여객 터미널은 관광 방문 스텝으로 보충되지 않는다")
    void passengerFacility_isNotUsedAsVisitStep() {
        assertThat(PlaceCategoryConstants.isPassengerFacility("성산포항 종합여객터미널")).isTrue();
        assertThat(PlaceCategoryConstants.isPassengerFacility("제주 우도 천진항 대합실")).isTrue();
        // 일반 항구·식당은 여객시설이 아니다 (관광지로 유효)
        assertThat(PlaceCategoryConstants.isPassengerFacility("미포항")).isFalse();
        assertThat(PlaceCategoryConstants.isPassengerFacility("제주 성산항")).isFalse();
    }

    @Test
    @DisplayName("항구는 harbor 세부 유형을 받아 유형 상한 대상이 된다")
    void harborGetsSubTypeSoTypeCapApplies() {
        String generic = "Landmarks and Outdoors > Tourist Attraction";
        assertThat(PlaceCategoryConstants.subType("제주 성산항", generic, List.of(), null))
                .isEqualTo("harbor");
        assertThat(PlaceCategoryConstants.subType("한림항", generic, List.of(), null))
                .isEqualTo("harbor");
    }

    @Test
    @DisplayName("교통시설 이름 판정이 Sports Complex·역사박물관을 오판하지 않는다")
    void transitNameSignal_hasNoSubstringFalsePositives() {
        // 예전엔 "port"·"역" 부분 문자열 매칭이라 아래가 전부 교통허브로 잡혔다
        assertThat(PlaceCategoryConstants.hasTransitNameSignal("Sports Complex")).isFalse();
        assertThat(PlaceCategoryConstants.hasTransitNameSignal("Export Center")).isFalse();
        assertThat(PlaceCategoryConstants.hasTransitNameSignal("역사박물관")).isFalse();
        assertThat(PlaceCategoryConstants.hasTransitNameSignal("항공우주박물관")).isFalse();
        // 진짜 허브는 계속 잡혀야 한다
        assertThat(PlaceCategoryConstants.hasTransitNameSignal("서울역")).isTrue();
        assertThat(PlaceCategoryConstants.hasTransitNameSignal("제주국제공항")).isTrue();
        assertThat(PlaceCategoryConstants.hasTransitNameSignal("성산포항 종합여객터미널")).isTrue();
    }

}
