package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static org.assertj.core.api.Assertions.assertThat;

/** 선택 결과 없이 {@link SerialPlanner#plan}이 날마다 직접 고른 일정을 본다. */
class DayPickerTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String MEAL = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";

    private static final SelectionOutput NO_HINTS = new SelectionOutput("", List.of(), List.of(), List.of());

    private final SerialPlanner planner = new SerialPlanner();

    private SerialPlanner.Result plan(TraceFile trace) {
        return planner.plan(trace.selection(), trace.candidates(), trace.args(), trace.dayCount(),
                SerialPlanner.Picks.IGNORE);
    }

    @Test
    @DisplayName("날마다 고른 장소는 그날 첫 관광지에서 한 구간 안에 있다")
    void picksStayWithinOneLegOfTheDayBase() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            double cap = ScheduleTuning.distanceLimits(trace.args().transportPref(), trace.args().pace())[0];

            for (SelectionOutput.DayPlan day : plan(trace).selection().days()) {
                List<PlaceCandidate> picks = day.placeIndices().stream()
                        .map(idx -> trace.candidates().get(idx - 1)).toList();
                PlaceCandidate base = picks.stream().filter(c -> CandidatePolicy.roleOf(c).isVisit())
                        .findFirst().orElse(null);
                if (base == null) continue;
                assertThat(picks).as("day %d", day.dayNumber()).allSatisfy(c ->
                        assertThat(GeoUtils.haversine(coordsOf(base), coordsOf(c))).isLessThanOrEqualTo(cap));
            }
        }
    }

    @Test
    @DisplayName("같은 장소를 두 날에 고르지 않고, 같은 입력이면 같은 일정이 나온다")
    void picksAreUniqueAndRepeatable() {
        TraceFile trace = Itinerary91Fixture.load();
        SerialPlanner.Result result = plan(trace);

        List<Integer> picks = result.selection().days().stream().flatMap(d -> d.placeIndices().stream()).toList();
        assertThat(picks).doesNotHaveDuplicates();
        assertThat(plan(trace).steps()).isEqualTo(result.steps());
    }

    @Test
    @DisplayName("직접 고른 일정은 구조 검증을 통과하고 날마다 점심이 있다")
    void everyDayHasLunch() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            List<StepData> steps = plan(trace).steps();

            assertThat(new HardValidator().validate(new com.shg.trip.shgtrip.domain.planning.dto.ItineraryData("t",
                    trace.destination(), BigDecimal.ZERO, List.of(), steps)).failureReason()).isNull();
            CandidateLookup lookup = new CandidateLookup(trace.candidates());
            for (int day = 1; day <= trace.dayCount(); day++) {
                int dayNumber = day;
                assertThat(steps).as("day %d", day).anyMatch(s -> s.dayNumber() == dayNumber && lookup.isMeal(s)
                        && s.startTime().compareTo("11:00") >= 0 && s.startTime().compareTo("14:00") < 0);
            }
        }
    }

    @Test
    @DisplayName("요청 의도 장소는 서로 붙어 있어도 날마다 기준 장소가 된다")
    void requestedPlacesBecomeDayBases() {
        List<PlaceCandidate> pool = walkablePool();
        // 궁 0에서 5km 떨어진 유명한 곳. 요청 의도가 없으면 떨어진 곳부터 기준으로 삼아 이곳이 기준이 된다.
        pool.add(new PlaceCandidate(14, 14L, "유명한 성곽", "서울특별시 성동구 왕십리로 14", SIGHT, List.of(), "Seoul",
                "KR", BigDecimal.valueOf(37.5760), BigDecimal.valueOf(127.0365), null, null, 0.9, null, null, null,
                60, false, null, null, null, 40, "SIGHT", null, false, null));
        SelectionOutput hints = new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(2), null,
                null), new SelectionOutput.DayPlan(2, null, List.of(4), null, null)), List.of(), List.of(),
                List.of(2, 4), List.of());

        SerialPlanner.Result result = planner.plan(hints, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        assertThat(result.selection().days()).extracting(d -> d.placeIndices().get(0))
                .containsExactlyInAnyOrder(2, 4);
        assertThat(result.steps()).extracting(s -> s.place().name()).contains("궁 0", "궁 2");
    }

    @Test
    @DisplayName("저녁 의도 장소가 여행이 끝나는 날의 기준이 되면 다른 날과 맞바꾼다")
    void eveningRequestMovesOffTheLastDay() {
        SelectionOutput hints = new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(2), null,
                null), new SelectionOutput.DayPlan(2, null, List.of(4), null, null)), List.of(), List.of(),
                List.of(2), List.of(), List.of(new SelectionOutput.TimingHint(4, "evening")));

        SerialPlanner.Result result = planner.plan(hints, walkablePool(), walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        assertThat(result.selection().days()).extracting(d -> d.placeIndices().get(0)).containsExactly(4, 2);
    }

    @Test
    @DisplayName("숙소에서 먼 요청 의도 장소는 맞바꿀 날이 없어도 기준 장소로 남는다")
    void farRequestedBaseIsKept() {
        List<PlaceCandidate> pool = walkablePool();
        pool.add(new PlaceCandidate(14, 14L, "먼 궁", "서울특별시 송파구 올림픽로 14", SIGHT, List.of(), "Seoul", "KR",
                BigDecimal.valueOf(37.5120), BigDecimal.valueOf(127.1020), null, null, 0.9, null, null, null, 60,
                false, null, null, null, null, "SIGHT", null, false, null));
        SelectionOutput hints = new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(14),
                null, null)), List.of(), List.of(), List.of(14), List.of());

        SerialPlanner.Result result = planner.plan(hints, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        assertThat(result.selection().days()).flatExtracting(SelectionOutput.DayPlan::placeIndices).contains(14);
    }

    @Test
    @DisplayName("사용자가 고른 식당은 관광 자리로 고르지 않아 세 끼째로 빠지지 않는다")
    void userSelectedMealsAreNotTakenAsSights() {
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool().stream()
                .map(c -> c.mealEligible() && c.index() <= 11 ? c.asUserSelected() : c).toList());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        assertThat(result.days()).allSatisfy(d -> assertThat(d.dropped()).isEmpty());
        assertThat(result.steps()).extracting(s -> s.place().name())
                .contains("식당 0", "식당 1", "식당 2", "식당 3");
    }

    @Test
    @DisplayName("마지막 날은 저녁을 고르지 않아 저녁 시간에 관광지를 넣지 않는다")
    void lastDayLeavesTheEveningEmpty() {
        List<PlaceCandidate> pool = walkablePool();
        // 저녁에도 가는 실내 명소. 낮 예산이 차면 저녁 예산으로 넘어간다.
        for (int i = 0; i < 4; i++) {
            pool.add(new PlaceCandidate(14 + i, 14L + i, "미술관 " + i, "서울특별시 종로구 삼청로 " + i,
                    "Arts and Entertainment > Museum > Art Museum " + i, List.of(), "Seoul", "KR",
                    BigDecimal.valueOf(37.5720 + i * 0.002), BigDecimal.valueOf(126.9810), null, null, 0.8, null,
                    null, null, 150, false, null, null, null, null, "SIGHT", null, false, null));
        }
        ScheduleArgs tight = ScheduleTestArgs.of("tight", "walk", LocalDate.of(2026, 12, 1), List.of(), null, false,
                List.of());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, tight, 1, SerialPlanner.Picks.IGNORE);

        assertThat(result.steps()).filteredOn(s -> !"DINING".equals(PlaceCategoryConstants.majorCategory(
                        s.place().category())))
                .allSatisfy(s -> assertThat(s.startTime()).isLessThan("17:30"));
    }

    @Test
    @DisplayName("선택 결과의 날짜 수가 여행 일수와 다르면 선택 장소를 따로 대우하지 않는다")
    void dayCountMismatchFallsBackToIntentOnly() {
        List<PlaceCandidate> pool = walkablePool();
        SelectionOutput oneDay = new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(12, 13),
                null, null)), List.of(), List.of());

        List<Integer> picks = planner.plan(oneDay, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER)
                .selection().days().get(0).placeIndices();

        assertThat(picks).isNotEmpty().doesNotContain(12, 13);
    }

    @Test
    @DisplayName("사용자가 고른 식당은 먼 고리에 있어도 가까운 고리의 다른 식당보다 먼저 고른다")
    void userSelectedMealInTheFarRingComesFirst() {
        List<PlaceCandidate> pool = walkablePool();
        // 기준 장소와 숙소에서 5km 안팎이라 가까운 고리(4km) 밖이고 한 구간(8km) 안이다.
        pool.add(place(14, "사용자가 고른 먼 식당", MEAL, "MEAL", 37.5760, 127.0365).asUserSelected());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        assertThat(result.selection().days()).flatExtracting(SelectionOutput.DayPlan::placeIndices).contains(14);
    }

    @Test
    @DisplayName("같은 거리 고리 안에서는 선택 단계가 고른 식당을 먼저 고른다")
    void prefersSelectionPicksWithinTheSameRing() {
        List<PlaceCandidate> pool = walkablePool();
        SelectionOutput selection = selectionPicking(12, 13);

        List<Integer> preferred = planner.plan(selection, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER)
                .selection().days().get(0).placeIndices();
        List<Integer> ignored = planner.plan(selection, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE)
                .selection().days().get(0).placeIndices();

        assertThat(preferred).contains(12, 13);
        assertThat(ignored).doesNotContain(12, 13);
    }

    @Test
    @DisplayName("선택 단계가 고른 식당이 그날 점심과 같은 묶음이면 같은 고리의 다른 식당을 고른다")
    void selectionMealYieldsWhenItRepeatsTheGroup() {
        List<PlaceCandidate> pool = walkablePool();
        pool.set(11, place(12, "바다 횟집", MEAL, "MEAL", 37.5740, 126.9810));
        pool.set(12, place(13, "항구 조개구이", MEAL, "MEAL", 37.5745, 126.9815));

        List<Integer> dayOne = planner.plan(selectionPicking(12, 13), pool, walkArgs(), 2, SerialPlanner.Picks.PREFER)
                .selection().days().get(0).placeIndices();

        assertThat(dayOne.stream().filter(idx -> idx == 12 || idx == 13)).hasSize(1);
    }

    @Test
    @DisplayName("그날 기준 장소에서 먼 예비 장소는 그날 보충에 쓰이지 않게 예비 목록에서 빠진다")
    void farSpareIsDroppedForTheDay() {
        List<PlaceCandidate> pool = walkablePool();
        pool.add(place(14, "먼 식당", MEAL, "MEAL", 37.6300, 126.9800));
        PlanState s = PlanState.of(new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(2),
                1, null)), List.of(), List.of(9, 14)), pool, walkArgs());

        DayPicker.keepSpareNearBase(s, s.days.get(0));

        assertThat(s.spare).containsExactly(9);
    }

    @Test
    @DisplayName("선택 단계가 고른 식당이 먼 고리에 있으면 가까운 고리의 다른 식당을 고른다")
    void nearerPlacesBeatFarSelectionPicks() {
        List<PlaceCandidate> pool = walkablePool();
        pool.add(place(14, "먼 식당", MEAL, "MEAL", 37.6300, 126.9800));

        SerialPlanner.Result result = planner.plan(selectionPicking(14), pool, walkArgs(), 2,
                SerialPlanner.Picks.PREFER);

        assertThat(result.selection().days()).flatExtracting(SelectionOutput.DayPlan::placeIndices)
                .isNotEmpty().doesNotContain(14);
    }

    @Test
    @DisplayName("숙소에서 한 구간 밖에 있는 유명한 곳은 기준 장소로 쓰지 않는다")
    void baseStaysWithinReachOfLodging() {
        List<PlaceCandidate> pool = walkablePool();
        pool.add(new PlaceCandidate(14, 14L, "먼 궁", "서울특별시 송파구 올림픽로 14", SIGHT, List.of(), "Seoul", "KR",
                BigDecimal.valueOf(37.5120), BigDecimal.valueOf(127.1020), null, null, 0.9, null, null, null, 60,
                false, null, null, null, 40, "SIGHT", null, false, null));

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        assertThat(result.selection().days()).flatExtracting(SelectionOutput.DayPlan::placeIndices)
                .isNotEmpty().doesNotContain(14);
        assertThat(result.days()).allSatisfy(day -> assertThat(day.dropped()).isEmpty());
    }

    @Test
    @DisplayName("숙소에서 자는 날은 점심과 저녁을 직접 고른다")
    void picksLunchAndDinner() {
        List<PlaceCandidate> pool = walkablePool();

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        SelectionOutput.DayPlan first = result.selection().days().get(0);
        long meals = first.placeIndices().stream().filter(idx -> pool.get(idx - 1).mealEligible()).count();
        assertThat(meals).isEqualTo(2);
        assertThat(result.days().get(0).dropped()).isEmpty();
    }

    @Test
    @DisplayName("닿는 범위 안 관광지가 하루 하한보다 적으면 모자란 수를 남긴다")
    void reportsShortfallWhenTheReachIsThin() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        pool.add(place(2, "호텔", HOTEL, "LODGING", 37.5760, 126.9800));
        pool.add(place(3, "점심 식당", MEAL, "MEAL", 37.5780, 126.9790));
        pool.add(place(4, "저녁 식당", MEAL, "MEAL", 37.5770, 126.9810));

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.IGNORE);

        assertThat(result.days()).extracting(SerialPlanner.DayReport::shortfall).containsExactly(1, 2);
    }

    @Test
    @DisplayName("저녁이 없는 마지막 날도 하루 관광 수보다 한 곳까지만 더 고른다")
    void lastDayTakesAtMostOneExtraStop() {
        TraceFile trace = Itinerary91Fixture.load();
        int most = ScheduleTuning.sightsPerDay(trace.args().pace())[1];

        SelectionOutput.DayPlan last = plan(trace).selection().days().get(trace.dayCount() - 1);

        assertThat(last.placeIndices().stream().map(idx -> trace.candidates().get(idx - 1))
                .filter(c -> !c.mealEligible())).hasSizeLessThanOrEqualTo(most + 1);
    }

    /** 1일차에 주어진 장소를, 2일차에는 아무것도 고르지 않은 선택. */
    private static SelectionOutput selectionPicking(Integer... dayOnePicks) {
        return new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(dayOnePicks), null, null),
                new SelectionOutput.DayPlan(2, null, List.of(), null, null)), List.of(), List.of());
    }

    /** 숙소 하나와 그 둘레 2km 안의 관광지 여섯, 식당 여섯. */
    private static List<PlaceCandidate> walkablePool() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "호텔", HOTEL, "LODGING", 37.5760, 126.9800));
        for (int i = 0; i < 6; i++) {
            pool.add(place(2 + i, "궁 " + i, SIGHT, "SIGHT", 37.5700 + i * 0.003, 126.9770 + i * 0.002));
            pool.add(place(8 + i, "식당 " + i, MEAL, "MEAL", 37.5710 + i * 0.003, 126.9790 + i * 0.002));
        }
        pool.sort(java.util.Comparator.comparingInt(PlaceCandidate::index));
        return pool;
    }

    private static ScheduleArgs walkArgs() {
        return ScheduleTestArgs.of("normal", "walk", LocalDate.of(2026, 12, 1), List.of(), null, false, List.of());
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                        double lng) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index, category, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60,
                false, null, null, null, null, role, null, false, null);
    }
}
