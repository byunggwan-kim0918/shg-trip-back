package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
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

/** {@link InvariantChecker} 판정을 원하는 하루를 직접 만들어 확인한다. */
class InvariantCheckerTest {

    private final InvariantChecker checker = new InvariantChecker();

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String ATTRACTION = "Landmarks and Outdoors > Tourist Attraction";
    private static final String AIRPORT =
            "Travel and Transportation > Transport Hub > Airport > Airport Terminal";

    @Test
    @DisplayName("도시를 옮기는 열차·비행기 구간은 하루 이동 예산에 넣지 않는다")
    void intercityLegIsOutsideDayBudget() {
        PlaceData osaka = new PlaceData("道頓堀", null, ATTRACTION, "Osaka", "JP");
        StepData train = new StepData(1, 3, "12:30", "14:00", osaka, List.of(), "TRAIN", 197,
                BigDecimal.valueOf(524), null, null, null);
        StepData car = new StepData(1, 3, "12:30", "14:00", osaka, List.of(), "CAR", 40,
                BigDecimal.valueOf(30), null, null, null);

        assertThat(InvariantChecker.isOutsideDayBudget(List.of(train), 0, false, false)).isTrue();
        assertThat(InvariantChecker.isOutsideDayBudget(List.of(car), 0, false, false)).isFalse();
    }

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

            Map<Integer, DayStatus> result = checker.evaluate(days, steps, "car", List.of(), "normal");

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

            Map<Integer, DayStatus> result = checker.evaluate(days, steps, "car", List.of(), "normal");

            assertThat(result.get(2).lodgingRequired()).isFalse();
        }

        @Test
        @DisplayName("점심·저녁 시간창 안의 식당만 충족으로 센다")
        void countsMealsOnlyInsideWindow() {
            List<DayState> days = List.of(day(1, 5));
            List<StepData> steps = List.of(
                    step(1, "12:00", "13:10", "점심식당", RESTAURANT),
                    step(1, "15:00", "16:10", "애매한시간식당", RESTAURANT)); // 저녁 창 밖

            Map<Integer, DayStatus> result = checker.evaluate(days, steps, "car", List.of(), "normal");

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

            assertThat(checker.evaluate(days, earlyDeparture, "car", null, "normal").get(2).dinnerRequired())
                    .as("오전 비행기면 저녁이 필요 없다")
                    .isFalse();
            assertThat(checker.evaluate(days, lateDeparture, "car", null, "normal").get(2).dinnerRequired())
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

            DayStatus status = checker.evaluate(days, steps, "car", List.of(), "normal").get(1);

            assertThat(status.totalKm()).isCloseTo(200.0 / 1.3, org.assertj.core.data.Offset.offset(1.0));
            assertThat(status.withinBudget()).isFalse();
        }

        @Test
        @DisplayName("해외 도착 허브에서 나오는 구간과 출발 허브로 가는 구간은 하루 거리에 넣지 않는다. 국내 허브는 넣는다")
        void excludesOverseasHubTransfers() {
            List<DayState> days = List.of(day(1, null));
            List<StepData> overseas = List.of(
                    stepIn(1, "10:00", "10:30", "Narita International Airport", AIRPORT, null, "JP"),
                    stepIn(1, "12:00", "13:00", "식당", RESTAURANT, new BigDecimal("78.0"), "JP"),
                    stepIn(1, "13:30", "15:00", "명소", ATTRACTION, new BigDecimal("13.0"), "JP"),
                    stepIn(1, "17:00", "17:30", "Haneda Airport", AIRPORT, new BigDecimal("26.0"), "JP"));
            List<StepData> domestic = overseas.stream()
                    .map(st -> stepIn(1, st.startTime(), st.endTime(), st.place().name(), st.place().category(),
                            st.transportationDistance(), "KR"))
                    .toList();

            assertThat(checker.evaluate(days, overseas, "car", List.of(), "normal").get(1).totalKm())
                    .isCloseTo(13.0 / 1.3, org.assertj.core.data.Offset.offset(0.1));
            assertThat(checker.evaluate(days, domestic, "car", List.of(), "normal").get(1).totalKm())
                    .isCloseTo(117.0 / 1.3, org.assertj.core.data.Offset.offset(0.1));
        }
    }

    @Nested
    @DisplayName("끼니 판정 기준")
    class MealJudge {

        private static final String GASTROPUB = "Dining and Drinking > Restaurant > Gastropub";

        private PlaceCandidate candidate(int index, String name, String category, String role) {
            return new PlaceCandidate(index, (long) index, name, "주소", category, List.of(), "Kyoto", "JP",
                    BigDecimal.ONE, BigDecimal.ONE, null, null, 0.9, null, null, null, null, false, null, null,
                    null, null, role, null);
        }

        @Test
        @DisplayName("그날 후보의 역할이 MEAL이면 이름·카테고리가 술집처럼 보여도 끼니로 본다")
        void usesCandidateRole() {
            DayState day = new DayState(new SelectionOutput.DayPlan(1, null, List.of(1), null, null));
            List<PlaceCandidate> candidates = List.of(candidate(1, "高木与三右衛門商店", GASTROPUB, "MEAL"));
            List<StepData> steps = List.of(step(1, "11:30", "12:40", "高木与三右衛門商店", GASTROPUB));

            assertThat(InvariantChecker.hasMealInWindow(steps, ScheduleTuning.LUNCH_START, ScheduleTuning.LUNCH_END))
                    .isFalse();
            assertThat(InvariantChecker.hasMealInWindow(steps, ScheduleTuning.LUNCH_START, ScheduleTuning.LUNCH_END,
                    InvariantChecker.mealJudge(day, candidates))).isTrue();
        }

        @Test
        @DisplayName("그날 방문 목록에 없는 스텝은 이름·카테고리로 판정한다")
        void fallsBackOutsideDay() {
            DayState day = new DayState(new SelectionOutput.DayPlan(1, null, List.of(), null, null));
            List<PlaceCandidate> candidates = List.of(candidate(1, "高木与三右衛門商店", GASTROPUB, "MEAL"));
            List<StepData> steps = List.of(step(1, "11:30", "12:40", "高木与三右衛門商店", GASTROPUB));

            assertThat(InvariantChecker.hasMealInWindow(steps, ScheduleTuning.LUNCH_START, ScheduleTuning.LUNCH_END,
                    InvariantChecker.mealJudge(day, candidates))).isFalse();
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
        return new DayStatus(lunch, dinner, true, true, lodging, true, 10, 5, true, 20 * 60, false, false, false);
    }

    private static StepData step(int day, String start, String end, String name, String category) {
        return stepWithDistance(day, start, end, name, category, null);
    }

    private static StepData stepWithDistance(int day, String start, String end, String name,
                                             String category, BigDecimal distanceKm) {
        return stepIn(day, start, end, name, category, distanceKm, "KR");
    }

    private static StepData stepIn(int day, String start, String end, String name,
                                   String category, BigDecimal distanceKm, String country) {
        return new StepData(0, day, start, end,
                new PlaceData(name, name + " 주소", category, "Jeju", country),
                List.of(), distanceKm != null ? "CAR" : null, null, distanceKm, null, null, null);
    }
}
