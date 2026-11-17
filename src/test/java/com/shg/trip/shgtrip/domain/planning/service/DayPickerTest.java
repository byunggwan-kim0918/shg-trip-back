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
import java.util.Map;
import java.util.Set;

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
    @DisplayName("날마다 고른 장소는 그날 첫 관광지나 그날 요청 의도 장소에서 한 구간 안에 있고, 요청 의도 장소는 어느 날에든 놓인다")
    void picksStayWithinOneLegOfTheDayBase() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            double cap = ScheduleTuning.distanceLimits(trace.args().transportPref(), trace.args().pace())[0];
            // 맞바꿀 날이 없는 먼 요청 의도 장소는 거리를 감수하고 가장 가까운 날에 둔다.
            Set<Integer> requested = new java.util.HashSet<>(trace.selection().highlightIndices() == null ? List.of()
                    : trace.selection().highlightIndices());
            if (trace.selection().timing() != null) {
                trace.selection().timing().forEach(t -> requested.add(t.index()));
            }
            List<SelectionOutput.DayPlan> days = plan(trace).selection().days();
            assertThat(days).flatExtracting(SelectionOutput.DayPlan::placeIndices).containsAll(requested);

            for (SelectionOutput.DayPlan day : days) {
                // 요청 의도 장소도 그날 기준 장소가 될 수 있다.
                PlaceCandidate base = day.placeIndices().stream().map(idx -> trace.candidates().get(idx - 1))
                        .filter(c -> CandidatePolicy.roleOf(c).isVisit()).findFirst().orElse(null);
                if (base == null) continue;
                // 카페 스톱은 그날 장소들의 중심에서 고르므로 먼 요청 의도 장소 쪽에 놓일 수 있다.
                List<PlaceCandidate> anchors = new ArrayList<>(List.of(base));
                day.placeIndices().stream().filter(requested::contains).map(idx -> trace.candidates().get(idx - 1))
                        .forEach(anchors::add);
                List<PlaceCandidate> picks = day.placeIndices().stream().filter(idx -> !requested.contains(idx))
                        .map(idx -> trace.candidates().get(idx - 1)).toList();
                assertThat(picks).as("day %d", day.dayNumber()).allSatisfy(c -> assertThat(anchors.stream()
                        .mapToDouble(a -> GeoUtils.haversine(coordsOf(a), coordsOf(c))).min().orElseThrow())
                        .isLessThanOrEqualTo(cap));
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
    @DisplayName("숙소를 옮기는 날에는 전날 숙소에서 한 구간 밖인 요청 장소를 맞바꿔 오지 않는다")
    void lodgingChangeDayDoesNotTakeABaseFarFromLastNight() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "서쪽 호텔", HOTEL, "LODGING", 37.5700, 126.9000));
        pool.add(place(2, "동쪽 호텔", HOTEL, "LODGING", 37.5700, 127.0000));
        pool.add(new PlaceCandidate(3, 3L, "동쪽 명소", "서울특별시 광진구 3", SIGHT, List.of(), "Seoul", "KR",
                BigDecimal.valueOf(37.5700), BigDecimal.valueOf(127.0300), null, null, 0.9, null, null, null, 60,
                false, null, null, null, 40, "SIGHT", null, false, null).withIntent(true, null));
        pool.add(place(4, "가운데 궁", SIGHT, "SIGHT", 37.5700, 126.9500));
        pool.add(place(5, "가운데 정원", SIGHT, "SIGHT", 37.5710, 126.9550));
        PlanState s = PlanState.of(new SelectionOutput("", List.of(
                new SelectionOutput.DayPlan(1, null, new ArrayList<>(List.of(3)), 1, null),
                new SelectionOutput.DayPlan(2, null, new ArrayList<>(List.of(5)), 2, null),
                new SelectionOutput.DayPlan(3, null, new ArrayList<>(List.of(4)), 2, null)), List.of(), List.of()),
                pool, walkArgs());

        DayPicker.keepBasesInReach(s, Set.of());

        assertThat(s.days.get(1).placeIndices).containsExactly(5);
        assertThat(s.days.get(2).placeIndices).containsExactly(3);
    }

    @Test
    @DisplayName("다른 날 볼거리와 같은 자리(0.5km 안)의 관광지는 고르지 않는다")
    void skipsSightsAtAnotherDaysSpot() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "호텔", HOTEL, "LODGING", 37.5760, 126.9800));
        pool.add(place(2, "궁", SIGHT, "SIGHT", 37.5700, 126.9770));
        pool.add(place(3, "미술관", "Arts and Entertainment > Museum > Art Museum", "SIGHT", 37.5700, 126.9870));
        pool.add(place(4, "공원", "Landmarks and Outdoors > Park", "SIGHT", 37.5840, 126.9770));
        pool.add(place(5, "사찰", "Landmarks and Outdoors > Temple", "SIGHT", 37.5715, 126.9885));
        for (int i = 0; i < 3; i++) pool.add(place(6 + i, "식당 " + i, MEAL, "MEAL", 37.5710 + i * 0.002, 126.9790));

        // 2일차의 사찰 둘레 0.5km 안에 미술관이 있다.
        assertThat(fillOneDay(pool, null, 5)).doesNotContain(3);
        assertThat(fillOneDay(pool, null)).contains(3);
    }

    @Test
    @DisplayName("영업시간에 걸려 빠진 장소는 그날 다시 넣고 여는 시간대 맨 앞 자리를 준다")
    void placesOpeningHoursDropsBackIntoTheirSlot() {
        List<PlaceCandidate> pool = walkablePool();
        pool.set(3, withHours(pool.get(3), AFTERNOON_ONLY));
        PlanState s = PlanState.of(new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null,
                new ArrayList<>(List.of(2, 4)), 1, null)), List.of(), List.of()), pool, walkArgs());
        s.beginScheduling();
        DayState day = s.days.get(0);
        day.resetDrops();
        day.drop(4, DropReason.CLOSED_AT_TIME);

        assertThat(SerialPlanner.placeByOpeningHours(s, day)).isTrue();
        assertThat(day.placeIndices).contains(4);
        assertThat(day.openingSlots).containsEntry(4, DayState.Slot.AFTERNOON_FIRST);
        assertThat(SerialPlanner.placeByOpeningHours(s, day)).isFalse();
    }

    @Test
    @DisplayName("사용자가 고른 늦게 여는 장소는 빠지지 않으므로 처음부터 여는 시간대 맨 앞 자리를 준다")
    void userSelectedLateOpenerGetsItsSlot() {
        List<PlaceCandidate> pool = walkablePool();
        pool.set(3, withHours(pool.get(3), AFTERNOON_ONLY).asUserSelected());
        PlanState s = PlanState.of(new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null,
                new ArrayList<>(List.of(2, 4)), 1, null)), List.of(), List.of()), pool, walkArgs());
        s.beginScheduling();
        DayState day = s.days.get(0);

        assertThat(SerialPlanner.placeByOpeningHours(s, day)).isTrue();
        assertThat(day.openingSlots).containsExactly(Map.entry(4, DayState.Slot.AFTERNOON_FIRST));
    }

    @Test
    @DisplayName("그 시간대가 시작할 때도 열지 않는 장소는 다시 넣지 않는다")
    void doesNotReAddPlacesClosedAtTheirSlot() {
        List<PlaceCandidate> pool = walkablePool();
        pool.set(3, withHours(pool.get(3), SHORT_AFTERNOON));
        PlanState s = PlanState.of(new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null,
                new ArrayList<>(List.of(2, 4)), 1, null)), List.of(), List.of()), pool, walkArgs());
        s.beginScheduling();
        DayState day = s.days.get(0);
        day.resetDrops();
        day.drop(4, DropReason.CLOSED_AT_TIME);

        assertThat(SerialPlanner.placeByOpeningHours(s, day)).isFalse();
        assertThat(day.placeIndices).doesNotContain(4);
    }

    @Test
    @DisplayName("출발 시각을 아는 마지막 날은 저녁 시각까지 더 고르지 않는다")
    void lastDayWithDepartureDoesNotFillTheAfternoon() {
        TraceFile trace = Itinerary91Fixture.load();
        int most = ScheduleTuning.sightsPerDay(trace.args().pace())[1];
        ScheduleArgs withDeparture = trace.args().forCityBlock(trace.args().hub(), trace.args().arrivalTime(),
                "18:00", false, false);
        TraceFile departing = new TraceFile(trace.itineraryId(), trace.destination(), trace.startDate(),
                trace.endDate(), trace.savedSteps(), trace.candidates(), trace.searchPoolSize(), trace.selection(),
                withDeparture, trace.joined(), trace.pipeline());

        SelectionOutput.DayPlan last = plan(departing).selection().days().get(trace.dayCount() - 1);
        long sights = last.placeIndices().stream().map(idx -> trace.candidates().get(idx - 1))
                .filter(c -> !c.mealEligible()).count();

        assertThat(sights).isLessThanOrEqualTo(most);
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
    @DisplayName("저녁이 없는 마지막 날은 예상 종료가 저녁 시각에 닿을 때까지만 관광지를 더 고른다")
    void lastDayFillsTheAfternoonOnly() {
        TraceFile trace = Itinerary91Fixture.load();
        int most = ScheduleTuning.sightsPerDay(trace.args().pace())[1];

        SerialPlanner.Result result = plan(trace);
        SelectionOutput.DayPlan last = result.selection().days().get(trace.dayCount() - 1);
        long sights = last.placeIndices().stream().map(idx -> trace.candidates().get(idx - 1))
                .filter(c -> !c.mealEligible()).count();

        assertThat(sights).isGreaterThan(most);
        assertThat(result.steps().stream().filter(s -> s.dayNumber() == trace.dayCount()))
                .allSatisfy(s -> assertThat(ScheduleTimes.toMinutesOrZero(s.startTime()))
                        .isLessThan(ScheduleTuning.DINNER_START));
    }

    @Test
    @DisplayName("사용자가 고른 관광지가 날 수보다 많으면 기준 장소에서 한 구간 밖인 곳도 가장 가까운 날에 들어간다")
    void promisedSightBeyondReachIsPlaced() {
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool().stream()
                .map(c -> c.index() == 2 || c.index() == 4 ? c.asUserSelected() : c).toList());
        // 기준 장소들에서 10km 넘게 떨어져 도보 한 구간(8km) 밖이다.
        pool.add(place(14, "먼 고른 궁", SIGHT, "SIGHT", 37.5120, 127.1020).asUserSelected());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER);

        assertThat(result.selection().days()).flatExtracting(SelectionOutput.DayPlan::placeIndices)
                .contains(2, 4, 14);
        assertThat(result.steps()).extracting(s -> s.place().name()).contains("궁 0", "궁 2", "먼 고른 궁");
    }

    @Test
    @DisplayName("좌표가 없는 사용자가 고른 관광지도 가장 덜 찬 날에 놓여 일정에 들어간다")
    void promisedSightWithoutCoordsIsPlaced() {
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool());
        pool.add(new PlaceCandidate(14, null, "직접 입력한 공방", "서울 어딘가", SIGHT, List.of(), "Seoul", "KR",
                null, null, null, null, 0.5, null, null, null, 60, true, null, null, null, null, "SIGHT", null,
                false, null));

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER);

        assertThat(result.selection().days()).flatExtracting(SelectionOutput.DayPlan::placeIndices).contains(14);
        assertThat(result.steps()).extracting(s -> s.place().name()).contains("직접 입력한 공방");
    }

    @Test
    @DisplayName("사용자가 출발 시각을 준 여행에서 고른 먼 관광지는 끝나는 날의 기준 장소가 더 가까워도 다른 날에 둔다")
    void promisedSightAvoidsTheEndingDayWithDeparture() {
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool().stream()
                .map(c -> c.index() == 2 || c.index() == 4 ? c.asUserSelected() : c).toList());
        // 둘째 날 기준 장소(궁 2) 쪽으로 10km쯤 떨어져 있다. 출발 시각 가드는 고른 장소를 자르지 않는다.
        pool.add(place(14, "먼 고른 궁", SIGHT, "SIGHT", 37.6500, 127.0500).asUserSelected());
        ScheduleArgs departing = new ScheduleArgs("normal", "walk", LocalDate.of(2026, 12, 1), List.of(), null, false,
                List.of(), false, null, "18:00", "KR", null, false, false);

        SerialPlanner.Result withDeparture = planner.plan(NO_HINTS, pool, departing, 2, SerialPlanner.Picks.PREFER);
        SerialPlanner.Result noDeparture = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER);

        assertThat(withDeparture.selection().days().get(0).placeIndices()).contains(14);
        assertThat(withDeparture.selection().days().get(1).placeIndices()).doesNotContain(14);
        // 출발 시각이 없으면 지킬 마감이 없어 가까운 끝나는 날에 둔다. 앞날에 몰면 그날이 자정 가까이 끝난다.
        assertThat(noDeparture.selection().days().get(1).placeIndices()).contains(14);
    }

    @Test
    @DisplayName("사용자가 고른 먼 식당은 끼니 하나를 맡아 그날 세 끼가 되지 않는다")
    void promisedMealTakesOneMealSlot() {
        List<PlaceCandidate> pool = walkablePool();
        pool.add(place(14, "먼 고른 식당", MEAL, "MEAL", 37.5120, 127.1020).asUserSelected());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER);

        CandidateLookup lookup = new CandidateLookup(pool);
        assertThat(result.steps()).extracting(s -> s.place().name()).contains("먼 고른 식당");
        for (int day = 1; day <= 2; day++) {
            int dayNumber = day;
            assertThat(result.steps().stream().filter(s -> s.dayNumber() == dayNumber && lookup.isMeal(s)))
                    .as("day %d", day).hasSizeLessThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("사용자가 고른 식당이 점심·저녁 자리보다 많으면 남는 식당은 아침 자리를 맡고 하루 세 끼를 넘지 않는다")
    void extraPromisedMealTakesBreakfast() {
        // 두 날의 점심·저녁 네 자리보다 하나 많다.
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool().stream()
                .map(c -> c.mealEligible() && c.index() <= 12 ? c.asUserSelected() : c).toList());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER);

        List<Integer> promisedMeals = pool.stream().filter(c -> c.userSelected() && c.mealEligible())
                .map(PlaceCandidate::index).toList();
        assertThat(promisedMeals).hasSize(5);
        assertThat(result.selection().days()).flatExtracting(SelectionOutput.DayPlan::placeIndices)
                .containsAll(promisedMeals);
        assertThat(result.selection().days()).allSatisfy(d -> assertThat(d.placeIndices().stream()
                .filter(idx -> pool.get(idx - 1).mealEligible())).hasSizeLessThanOrEqualTo(3));
    }

    @Test
    @DisplayName("당일치기에 꼭 하고 싶은 저녁 경험은 끝나는 날이어도 일정에 들어간다")
    void dayTripKeepsPromisedEveningPlace() {
        List<PlaceCandidate> pool = walkablePool();
        pool.add(place(14, "야경 명소", SIGHT, "SIGHT", 37.5790, 126.9840).withIntent(true, "evening"));

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 1, SerialPlanner.Picks.PREFER);

        assertThat(result.selection().days().get(0).placeIndices()).contains(14);
        assertThat(result.steps()).extracting(s -> s.place().name()).contains("야경 명소");
    }

    @Test
    @DisplayName("당일치기에 고른 식당 두 곳은 하나가 기준 장소에서 멀어도 점심과 저녁을 맡는다")
    void dayTripKeepsBothPromisedMeals() {
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool().stream()
                .map(c -> c.index() == 8 ? c.asUserSelected() : c).toList());
        pool.add(place(14, "먼 고른 식당", MEAL, "MEAL", 37.5120, 127.1020).asUserSelected());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 1, SerialPlanner.Picks.PREFER);

        CandidateLookup lookup = new CandidateLookup(pool);
        assertThat(result.steps()).extracting(s -> s.place().name()).contains("식당 0", "먼 고른 식당");
        assertThat(result.steps().stream().filter(lookup::isMeal)).hasSize(2);
    }

    @Test
    @DisplayName("점심 뒤에 도착하는 날의 고른 식당은 저녁을 맡아 저녁 식당을 하나 더 고르지 않는다")
    void lateArrivalPromisedMealTakesDinner() {
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool().stream()
                .map(c -> c.index() == 8 ? c.asUserSelected() : c).toList());
        ScheduleArgs afternoon = new ScheduleArgs("normal", "walk", LocalDate.of(2026, 12, 1), List.of(), null, false,
                List.of(), false, "15:00", null, "KR", null, false, false);

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, afternoon, 2, SerialPlanner.Picks.PREFER);

        CandidateLookup lookup = new CandidateLookup(pool);
        assertThat(result.steps().stream().filter(s -> s.dayNumber() == 1 && lookup.isMeal(s)))
                .extracting(s -> s.place().name()).containsExactly("식당 0");
    }

    @Test
    @DisplayName("선택 결과의 날짜 수가 어긋나 다시 고를 때도 예약한 숙소로 밤을 보낸다")
    void bookedLodgingSurvivesDayCountMismatch() {
        List<PlaceCandidate> pool = walkablePool();
        pool.add(place(14, "예약한 호텔", HOTEL, "LODGING", 37.5900, 126.9950).asUserSelected());

        SerialPlanner.Result result = planner.plan(NO_HINTS, pool, walkArgs(), 3, SerialPlanner.Picks.PREFER);

        CandidateLookup lookup = new CandidateLookup(pool);
        assertThat(result.steps()).filteredOn(s -> lookup.role(s) == CandidatePolicy.Role.LODGING)
                .isNotEmpty().allSatisfy(s -> assertThat(s.place().name()).isEqualTo("예약한 호텔"));
    }

    @Test
    @DisplayName("날짜를 직접 고르면 날마다 기준 좌표를 남기고, 받은 날짜대로 확정하면 남기지 않는다")
    void anchorsAreRecordedOnlyWhenChoosingDays() {
        List<PlaceCandidate> pool = walkablePool();

        SerialPlanner.Result picked = planner.plan(NO_HINTS, pool, walkArgs(), 2, SerialPlanner.Picks.PREFER);
        SerialPlanner.Result asPlaced = planner.schedule(selectionPicking(2, 8), pool, walkArgs());

        assertThat(picked.anchors()).containsOnlyKeys(1, 2);
        assertThat(asPlaced.anchors()).isEmpty();
    }

    @Test
    @DisplayName("전날 저녁과 같은 종류 식당은 같은 고리에 다른 종류가 있으면 고르지 않는다")
    void avoidsThePreviousDinnerKind() {
        List<PlaceCandidate> pool = walkablePool();
        // 기준 장소(궁 0)에서 가장 가까운 식당이다.
        pool.set(7, place(8, "바다 횟집", MEAL, "MEAL", 37.5710, 126.9790));

        assertThat(fillOneDay(pool, null)).contains(8);
        assertThat(fillOneDay(pool, "raw_fish")).doesNotContain(8);
    }

    @Test
    @DisplayName("전날 저녁이 종류를 모르는 식당이면 종류를 모르는 가까운 식당도 그대로 고른다")
    void genericPreviousDinnerKindIsNotAvoided() {
        List<PlaceCandidate> pool = walkablePool();
        pool.set(7, place(8, "동네 밥집", "Dining and Drinking > Restaurant", "MEAL", 37.5710, 126.9790));

        assertThat(fillOneDay(pool, "restaurant")).contains(8);
    }

    @Test
    @DisplayName("저녁에 여는 식당이 점심과 같은 종류뿐이면 저녁을 두고 점심을 다른 종류로 바꾼다")
    void swapsLunchWhenDinnerCanOnlyRepeatItsKind() {
        List<PlaceCandidate> pool = lunchOnlyPool();

        List<Integer> picked = fillOneDay(pool, null);

        assertThat(picked).contains(14).doesNotContain(8);
        assertThat(picked.stream().filter(idx -> pool.get(idx - 1).mealEligible())).hasSize(2);
    }

    @Test
    @DisplayName("점심을 바꾸지 않는 경우: 요청 의도로 고른 점심, 바꿀 점심이 여행 전체 상한에 걸릴 때")
    void keepsLunchWhenSwapWouldBreakIntentOrTripCap() {
        List<PlaceCandidate> pool = lunchOnlyPool();
        pool.set(7, place(8, "바다 횟집", MEAL, "MEAL", 37.5710, 126.9790).withIntent(true, null));
        assertThat(fillOneDay(pool, null)).contains(8, 14);

        List<PlaceCandidate> gukbap = lunchOnlyPool();
        for (int i = 8; i <= 13; i++) {
            if (i == 8) continue;
            PlaceCandidate c = gukbap.get(i - 1);
            gukbap.set(i - 1, withHours(place(i, "국밥집 " + i, MEAL, "MEAL", c.latitude().doubleValue(),
                    c.longitude().doubleValue()), LUNCH_ONLY));
        }
        PlanState.Earlier capped = new PlanState.Earlier(Set.of(), Set.of(), Map.of("gukbap", 2), 1);
        assertThat(fillOneDay(gukbap, null, capped)).contains(8, 14);
    }

    @Test
    @DisplayName("점심을 바꾸면 빠진 점심은 예비 목록에 있었다면 다시 예비 목록으로 돌아간다")
    void swappedLunchReturnsToSpare() {
        List<PlaceCandidate> pool = lunchOnlyPool();
        PlanState s = PlanState.of(new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(2),
                1, null)), List.of(), List.of(8)), pool, walkArgs());
        s.beginScheduling();

        DayPicker.fill(s, s.days.get(0), null, false, Set.of(), null);

        assertThat(s.days.get(0).placeIndices).doesNotContain(8).contains(14);
        assertThat(s.spare).contains(8);
    }

    /** 식당은 모두 점심에만 열고, 가장 가까운 바다 횟집과 저녁에 여는 항구 횟집만 같은 종류(회)다. */
    private static List<PlaceCandidate> lunchOnlyPool() {
        List<PlaceCandidate> pool = new ArrayList<>(walkablePool().stream()
                .map(c -> c.mealEligible() ? withHours(c, LUNCH_ONLY) : c).toList());
        pool.set(7, place(8, "바다 횟집", MEAL, "MEAL", 37.5710, 126.9790));
        pool.add(place(14, "항구 횟집", MEAL, "MEAL", 37.5745, 126.9805));
        return pool;
    }

    @Test
    @DisplayName("이미 간 체인의 다른 지점은 같은 고리에 다른 식당이 있으면 고르지 않는다")
    void avoidsVisitedBrand() {
        List<PlaceCandidate> pool = walkablePool();
        // 기준 장소(궁 0)에서 가장 가까운 식당이다.
        pool.set(7, place(8, "본가국밥 종로점", MEAL, "MEAL", 37.5710, 126.9790));
        pool.add(place(14, "본가국밥 서면점", MEAL, "MEAL", 37.5900, 126.9900));

        assertThat(fillOneDay(pool, null)).contains(8);
        assertThat(fillOneDay(pool, null, 14)).doesNotContain(8);
    }

    @Test
    @DisplayName("앞 도시 구간에서 여행 전체 상한까지 먹은 종류는 같은 고리에 다른 식당이 있으면 고르지 않는다")
    void countsMealKindsFromEarlierCities() {
        List<PlaceCandidate> pool = walkablePool();
        pool.set(7, place(8, "바다 횟집", MEAL, "MEAL", 37.5710, 126.9790));
        PlanState.Earlier earlier = new PlanState.Earlier(Set.of(), Set.of(), Map.of("raw_fish", 2), 1);

        assertThat(fillOneDay(pool, null, PlanState.Earlier.NONE)).contains(8);
        assertThat(fillOneDay(pool, null, earlier)).doesNotContain(8);
    }

    private static List<Integer> fillOneDay(List<PlaceCandidate> pool, String previousDinnerKind,
                                            Integer... visited) {
        return fillOneDay(pool, previousDinnerKind, PlanState.Earlier.NONE, visited);
    }

    /** @param visited 다른 날 구성에 이미 들어 있는 장소 */
    private static List<Integer> fillOneDay(List<PlaceCandidate> pool, String previousDinnerKind,
                                            PlanState.Earlier earlier, Integer... visited) {
        List<SelectionOutput.DayPlan> days = new ArrayList<>(List.of(new SelectionOutput.DayPlan(1, null, List.of(2),
                1, null)));
        if (visited.length > 0) days.add(new SelectionOutput.DayPlan(2, null, List.of(visited), null, null));
        PlanState s = PlanState.of(new SelectionOutput("", days, List.of(), List.of()), pool, walkArgs());
        s.continueFrom(earlier);
        s.beginScheduling();
        DayPicker.fill(s, s.days.get(0), null, false, Set.of(), previousDinnerKind);
        return s.days.get(0).placeIndices;
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

    private static final String LUNCH_ONLY = "월요일: 11:00 ~ 15:00, 화요일: 11:00 ~ 15:00, 수요일: 11:00 ~ 15:00, "
            + "목요일: 11:00 ~ 15:00, 금요일: 11:00 ~ 15:00, 토요일: 11:00 ~ 15:00, 일요일: 11:00 ~ 15:00";

    private static final String SHORT_AFTERNOON = "월요일: 14:30 ~ 15:30, 화요일: 14:30 ~ 15:30, 수요일: 14:30 ~ 15:30, "
            + "목요일: 14:30 ~ 15:30, 금요일: 14:30 ~ 15:30, 토요일: 14:30 ~ 15:30, 일요일: 14:30 ~ 15:30";

    private static final String AFTERNOON_ONLY = "월요일: 13:00 ~ 18:00, 화요일: 13:00 ~ 18:00, 수요일: 13:00 ~ 18:00, "
            + "목요일: 13:00 ~ 18:00, 금요일: 13:00 ~ 18:00, 토요일: 13:00 ~ 18:00, 일요일: 13:00 ~ 18:00";

    private static PlaceCandidate withHours(PlaceCandidate c, String hours) {
        return new PlaceCandidate(c.index(), c.placeId(), c.name(), c.address(), c.category(), c.tags(), c.region(),
                c.country(), c.latitude(), c.longitude(), null, null, c.similarityScore(), null, hours, null, 60, false,
                null, null, null, null, CandidatePolicy.roleOf(c).name(), null, false, null);
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                        double lng) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index, category, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60,
                false, null, null, null, null, role, null, false, null);
    }
}
