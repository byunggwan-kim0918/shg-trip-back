package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InvariantChecker} 단위 테스트.
 *
 * <p>이 검사 로직은 {@code RouteOptimizer} 안에 있을 때는 단독 테스트가 불가능했다 —
 * 판정을 확인하려면 4천 줄짜리 {@code schedule()}을 통째로 돌려서 나온 일정을 역산해야 했고,
 * 그래서 "숙소가 검사 항목에 없다"는 사실이 오랫동안 드러나지 않았다.
 * 여기서는 원하는 하루를 직접 만들어 판정만 확인한다.
 */
class InvariantCheckerTest {

    private final InvariantChecker checker = new InvariantChecker();

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String ATTRACTION = "Landmarks and Outdoors > Tourist Attraction";
    private static final String AIRPORT =
            "Travel and Transportation > Transport Hub > Airport > Airport Terminal";

    @Nested
    @DisplayName("evaluate - day별 불변식 판정")
    class Evaluate {

        @Test
        @DisplayName("숙박일에 숙소가 배정되지 않으면 lodging=false로 잡는다")
        void flagsMissingLodgingOnOvernightDay() {
            // 실측 itinerary 60: 4일 전부 숙소가 없는데 "위반 없음"으로 통과했다
            List<DayState> days = List.of(day(1, null), day(2, null));
            List<StepData> steps = List.of(
                    step(1, "12:00", "13:10", "식당", RESTAURANT),
                    step(2, "12:00", "13:10", "식당", RESTAURANT));

            Map<Integer, DayStatus> result = checker.evaluate(days, steps, "car", List.of());

            assertThat(result.get(1).lodging()).isFalse();
            assertThat(result.get(1).lodgingRequired())
                    .as("1일차는 숙박일이므로 숙소가 필요하다")
                    .isTrue();
        }

        @Test
        @DisplayName("마지막날은 숙소가 없어도 위반이 아니다 (귀가일)")
        void lastDayNeedsNoLodging() {
            List<DayState> days = List.of(day(1, 5), day(2, null));
            List<StepData> steps = List.of(
                    step(1, "12:00", "13:10", "식당", RESTAURANT),
                    step(2, "12:00", "13:10", "식당", RESTAURANT));

            Map<Integer, DayStatus> result = checker.evaluate(days, steps, "car", List.of());

            assertThat(result.get(2).lodgingRequired()).isFalse();
        }

        @Test
        @DisplayName("점심·저녁 시간창 안의 식당만 충족으로 센다")
        void countsMealsOnlyInsideWindow() {
            List<DayState> days = List.of(day(1, 5));
            List<StepData> steps = List.of(
                    step(1, "12:00", "13:10", "점심식당", RESTAURANT),
                    step(1, "15:00", "16:10", "애매한시간식당", RESTAURANT)); // 저녁 창 밖

            Map<Integer, DayStatus> result = checker.evaluate(days, steps, "car", List.of());

            assertThat(result.get(1).lunch()).isTrue();
            assertThat(result.get(1).dinner()).isFalse();
        }

        @Test
        @DisplayName("마지막날 저녁은 출발이 19:30 이후일 때만 필수다")
        void lastDayDinnerRequiredOnlyWhenDepartingLate() {
            List<DayState> days = List.of(day(1, 5), lastDayWithHub(2, 9));
            List<StepData> earlyDeparture = List.of(
                    step(1, "12:00", "13:10", "식당", RESTAURANT),
                    step(2, "10:00", "11:00", "제주국제공항", AIRPORT));
            List<StepData> lateDeparture = List.of(
                    step(1, "12:00", "13:10", "식당", RESTAURANT),
                    step(2, "20:00", "21:00", "제주국제공항", AIRPORT));

            assertThat(checker.evaluate(days, earlyDeparture, "car", null).get(2).dinnerRequired())
                    .as("오전 비행기면 저녁이 필요 없다")
                    .isFalse();
            assertThat(checker.evaluate(days, lateDeparture, "car", null).get(2).dinnerRequired())
                    .as("저녁 8시 출발이면 저녁을 먹고 가야 한다")
                    .isTrue();
        }

        @Test
        @DisplayName("이동거리는 도로 환산값을 직선 기준으로 되돌려 예산과 비교한다")
        void comparesStraightLineDistanceAgainstBudget() {
            List<DayState> days = List.of(day(1, 5));
            // car 일일 상한 115km(직선). 표시 150km = 직선 약 115km라 경계에 있다
            List<StepData> steps = List.of(
                    stepWithDistance(1, "09:00", "10:00", "먼곳", ATTRACTION, new BigDecimal("200.0")));

            DayStatus status = checker.evaluate(days, steps, "car", List.of()).get(1);

            assertThat(status.totalKm()).isCloseTo(200.0 / 1.3, org.assertj.core.data.Offset.offset(1.0));
            assertThat(status.withinBudget()).isFalse();
        }
    }

    @Nested
    @DisplayName("findPriorityRegression - 상위 불변식 역전 탐지")
    class PriorityRegression {

        @Test
        @DisplayName("점심이 있던 day에서 점심이 사라지면 역전으로 본다")
        void detectsLunchLoss() {
            Map<Integer, DayStatus> before = Map.of(1, status(true, true, true));
            Map<Integer, DayStatus> after = Map.of(1, status(false, true, true));

            assertThat(checker.findPriorityRegression(before, after)).isEqualTo("day1 점심 소실");
        }

        @Test
        @DisplayName("숙소가 사라지면 역전으로 본다")
        void detectsLodgingLoss() {
            Map<Integer, DayStatus> before = Map.of(1, status(true, true, true));
            Map<Integer, DayStatus> after = Map.of(1, status(true, true, false));

            assertThat(checker.findPriorityRegression(before, after)).isEqualTo("day1 숙소 소실");
        }

        @Test
        @DisplayName("악화가 없으면 null")
        void noRegression() {
            Map<Integer, DayStatus> same = Map.of(1, status(true, true, true));

            assertThat(checker.findPriorityRegression(same, same)).isNull();
        }
    }

    @Nested
    @DisplayName("summarize - 사용자 안내 문구")
    class Summarize {

        @Test
        @DisplayName("모든 불변식을 만족하면 안내 문구가 없다")
        void noNoticesWhenClean() {
            List<DayState> days = List.of(day(1, 5));
            List<StepData> steps = List.of(
                    step(1, "12:00", "13:10", "점심식당", RESTAURANT),
                    step(1, "14:00", "15:30", "관광지", ATTRACTION),
                    step(1, "18:00", "19:10", "저녁식당", RESTAURANT),
                    step(1, "19:30", "20:00", "호텔", "Travel and Transportation > Lodging > Hotel"));

            List<String> notices = checker.summarize(days, steps, "normal", "car", List.of(), null);

            assertThat(notices).isEmpty();
        }

        @Test
        @DisplayName("숙소 미배정은 안내 문구로 사용자에게 노출된다")
        void surfacesMissingLodging() {
            List<DayState> days = List.of(day(1, null), day(2, null));
            List<StepData> steps = List.of(
                    step(1, "12:00", "13:10", "점심식당", RESTAURANT),
                    step(1, "14:00", "15:30", "관광지", ATTRACTION),
                    step(1, "18:00", "19:10", "저녁식당", RESTAURANT),
                    step(2, "12:00", "13:10", "점심식당", RESTAURANT),
                    step(2, "14:00", "15:30", "관광지", ATTRACTION),
                    step(2, "18:00", "19:10", "저녁식당", RESTAURANT));

            List<String> notices = checker.summarize(days, steps, "normal", "car", List.of(), null);

            assertThat(notices).anyMatch(n -> n.contains("1일차") && n.contains("숙소"));
            assertThat(notices)
                    .as("2일차는 귀가일이라 숙소 안내가 없어야 한다")
                    .noneMatch(n -> n.contains("2일차") && n.contains("숙소"));
        }

        @Test
        @DisplayName("relaxed 페이스는 관광지가 없어도 안내하지 않는다")
        void relaxedPaceSkipsAttractionNotice() {
            List<DayState> days = List.of(day(1, 5));
            List<StepData> steps = List.of(
                    step(1, "12:00", "13:10", "점심식당", RESTAURANT),
                    step(1, "18:00", "19:10", "저녁식당", RESTAURANT));

            assertThat(checker.summarize(days, steps, "relaxed", "car", List.of(), null))
                    .noneMatch(n -> n.contains("관광지"));
            assertThat(checker.summarize(days, steps, "normal", "car", List.of(), null))
                    .anyMatch(n -> n.contains("관광지"));
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private static DayState day(int dayNumber, Integer accommodationIndex) {
        return new DayState(new SelectionOutput.DayPlan(
                dayNumber, null, List.of(), accommodationIndex, null));
    }

    private static DayState lastDayWithHub(int dayNumber, int departureHubIndex) {
        return new DayState(new SelectionOutput.DayPlan(
                dayNumber, null, List.of(), null, departureHubIndex));
    }

    private static DayStatus status(boolean lunch, boolean dinner, boolean lodging) {
        return new DayStatus(lunch, dinner, true, true, lodging, true, 10, 5, true, 20 * 60);
    }

    private static StepData step(int day, String start, String end, String name, String category) {
        return stepWithDistance(day, start, end, name, category, null);
    }

    private static StepData stepWithDistance(int day, String start, String end, String name,
                                             String category, BigDecimal distanceKm) {
        return new StepData(0, day, start, end,
                new PlaceData(name, name + " 주소", category, "Jeju", "KR"),
                List.of(), distanceKm != null ? "CAR" : null, null, distanceKm, null, null, null);
    }
}
