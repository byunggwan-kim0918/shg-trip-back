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
                place(4, "Mid", "Landmarks and Outdoors > Park", 37.510, 127.000, "강남"),
                place(5, "Near", "Landmarks and Outdoors > Park", 37.501, 127.000, "강남")
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
                place(2, "Mid", "Landmarks and Outdoors > Park", 37.510, 127.000, "강남"),
                place(3, "Far", "Landmarks and Outdoors > Park", 37.520, 127.000, "강남"),
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
                place(2, "Middle", "Landmarks and Outdoors > Park", 37.505, 127.000, "강남"),
                place(3, "Far", "Landmarks and Outdoors > Park", 37.510, 127.000, "강남")
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
                place(2, "East2", "Landmarks and Outdoors > Park", 37.5005, 127.0005, "동"),
                place(3, "West", "Landmarks and Outdoors > Park", 37.50, 127.900, "서"),   // ~80km 서
                place(4, "West2", "Landmarks and Outdoors > Park", 37.50, 127.905, "서"),
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
                place(3, "P1", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "P2", "Landmarks and Outdoors > Park", 37.503, 127.003, "강남"),
                place(5, "P3", "Landmarks and Outdoors > Park", 37.504, 127.004, "강남"),
                place(6, "P4", "Landmarks and Outdoors > Park", 37.505, 127.005, "강남"),
                place(7, "P5", "Landmarks and Outdoors > Park", 37.506, 127.006, "강남"),
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
    @DisplayName("야경 테마면 저녁 상한이 넓어져 더 많은 활동이 스케줄된다")
    void repairAndSchedule_nightThemeExtendsEveningCapacity() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.50, 127.000, "강남"),
                place(2, "Dinner", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "P1", "Landmarks and Outdoors > Park", 37.502, 127.002, "강남"),
                place(4, "P2", "Landmarks and Outdoors > Park", 37.503, 127.003, "강남"),
                place(5, "P3", "Landmarks and Outdoors > Park", 37.504, 127.004, "강남"),
                place(6, "P4", "Landmarks and Outdoors > Park", 37.505, 127.005, "강남"),
                place(7, "P5", "Landmarks and Outdoors > Park", 37.506, 127.006, "강남"),
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
                selection, candidates, "tight", "any", null, List.of("야경")).size();

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
        // 숙소는 서쪽(127.000). 두 식당과 FarPark는 동쪽(숙소에서 멂), NearHotel만 숙소 옆.
        // 활동이 2개라 afternoon 버킷이 채워지고, 저녁 버킷이 비어 endDayNearAccommodation이 발동한다.
        List<PlaceCandidate> candidates = List.of(
                place(1, "Meal1", "Dining and Drinking > Restaurant > Korean", 37.50, 127.010, "강남"),
                place(2, "NearHotel", "Landmarks and Outdoors > Park", 37.50, 127.001, "강남"),
                place(3, "Meal2", "Dining and Drinking > Restaurant > Korean", 37.50, 127.030, "강남"),
                place(4, "FarPark", "Landmarks and Outdoors > Park", 37.50, 127.035, "강남"),
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
    @DisplayName("반경 내 같은 대분류 대안이 없으면 반경 밖에서라도 대안을 채운다(대안 0개 회피)")
    void buildAlternatives_fallsBackBeyondRadiusWhenNoneNearby() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "MainRest", "Dining and Drinking > Restaurant > Korean", 37.500, 127.000, "강남"),
                place(2, "Lunch", "Dining and Drinking > Restaurant > Korean", 37.501, 127.001, "강남"),
                place(3, "Lodging", "Lodging > Hotel", 37.502, 127.002, "강남"),
                // 유일한 같은 대분류 대안이 walk 반경(3km) 밖에 있음
                place(4, "FarAlt", "Dining and Drinking > Restaurant > BBQ", 37.60, 127.20, "경기")
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2), 3, null)),
                List.of(),
                List.of(4)
        );

        List<StepData> steps = routeOptimizer.repairAndSchedule(
                selection, candidates, "relaxed", "walk", null, List.of());

        StepData mainStep = steps.stream().filter(s -> "MainRest".equals(s.place().name()))
                .findFirst().orElseThrow();
        assertThat(mainStep.alternatives().stream().map(AlternativeData::name)).contains("FarAlt");
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
}
