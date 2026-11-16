package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DaySchedulerTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String MEAL = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";
    private static final String STATION = "Travel and Transportation > Transport Hub > Rail Station";
    private static final String BAR = "Dining and Drinking > Bar";

    private static final int PREVIOUS_HOTEL = 1;
    private static final int HOTEL_TODAY = 6;

    /** 1 전날 숙소, 2~3 관광, 4~5 식당, 6 그날 숙소. 모두 종로 2km 안이다. */
    private static List<PlaceCandidate> oneDayPool() {
        List<PlaceCandidate> out = new ArrayList<>();
        out.add(place(1, "전날 호텔", HOTEL, "LODGING", 37.5700, 126.9830));
        out.add(place(2, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        out.add(place(3, "한옥마을", SIGHT, "SIGHT", 37.5826, 126.9836));
        out.add(place(4, "점심 식당", MEAL, "MEAL", 37.5760, 126.9850));
        out.add(place(5, "저녁 식당", MEAL, "MEAL", 37.5720, 126.9900));
        out.add(place(6, "그날 호텔", HOTEL, "LODGING", 37.5665, 126.9910));
        return out;
    }

    /** 여행 중간 날 하나만 담은 상태. 도착·출발 거점이 없고 그날도 숙소에서 끝난다. */
    private static PlanState midTripDay(List<PlaceCandidate> candidates, List<Integer> picks) {
        return oneDay(candidates, new SelectionOutput.DayPlan(3, null, picks, HOTEL_TODAY, null), List.of(), "car",
                null);
    }

    /** 3일차(목요일) 하루만 담은 상태. 앞뒤 날이 있는 여행이라 도착·출발 거점은 그날 구성에 준 것만 쓴다. */
    private static PlanState oneDay(List<PlaceCandidate> candidates, SelectionOutput.DayPlan plan,
                                    List<Integer> spare, String transportPref, String departureTime) {
        ScheduleArgs args = ScheduleTestArgs.of("normal", transportPref, LocalDate.of(2026, 12, 1), List.of(), null,
                false, List.of()).forCityBlock(null, null, departureTime, true, departureTime == null);
        PlanState s = PlanState.of(new SelectionOutput("하루", List.of(plan), List.of(), spare), candidates, args);
        s.beginScheduling();
        return s;
    }

    /** {@link #oneDayPool}에서 그날 장소를 모두 전날 숙소의 도보권 밖으로 옮긴 것. */
    private static List<PlaceCandidate> walkDayPool() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.set(1, place(2, "궁", SIGHT, "SIGHT", 37.5980, 126.9800));
        pool.set(2, place(3, "한옥마을", SIGHT, "SIGHT", 37.6020, 126.9790));
        pool.set(3, place(4, "점심 식당", MEAL, "MEAL", 37.5990, 126.9810));
        pool.set(4, place(5, "저녁 식당", MEAL, "MEAL", 37.6005, 126.9830));
        pool.set(5, place(6, "그날 호텔", HOTEL, "LODGING", 37.6010, 126.9840));
        return pool;
    }

    private static List<DropReason> reasons(DayScheduler.DayResult result) {
        return result.dropped().stream().map(DayState.Drop::reason).toList();
    }

    @Test
    @DisplayName("그날 후보만 담은 상태로 여행 중간 날을 확정하면 전날 숙소에서 출발해 그날 숙소에서 끝난다")
    void schedulesOneDayFromPreviousLodging() {
        PlanState s = midTripDay(oneDayPool(), List.of(2, 4, 3, 5));
        DayState day = s.days.get(0);

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, day, PREVIOUS_HOTEL);

        List<StepData> steps = result.steps();
        assertThat(steps).extracting(StepData::dayNumber).containsOnly(3);
        assertThat(steps).extracting(step -> step.place().name())
                .containsExactly("궁", "점심 식당", "한옥마을", "저녁 식당", "그날 호텔");
        assertThat(steps.get(0).transportationDistance()).isNotNull();
        assertThat(toMinutesOrZero(steps.get(1).startTime())).isEqualTo(ScheduleTuning.LUNCH_START);
        assertThat(toMinutesOrZero(steps.get(3).startTime())).isEqualTo(ScheduleTuning.DINNER_START);
        assertThat(day.dayOfWeek).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(day.startLodgingIndex).isEqualTo(PREVIOUS_HOTEL);
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    @DisplayName("날짜를 직접 고른 날은 아침 의도 장소를 오전 첫 방문으로 둬, 숙소에서 가까운 곳 뒤로 밀려 잘리지 않는다")
    void morningIntentLeadsTheMorningWhenFlagged() {
        List<PlaceCandidate> pool = oneDayPool();
        // 그날 숙소가 일출 명소 쪽이라 경로상 궁을 먼저 지난다. 둘 다 오전 예산에 들어가고, 궁 뒤에 두면 11시를 넘겨 시작한다.
        pool.set(1, place(2, "궁", SIGHT, "SIGHT", 37.6000, 126.9800, 80, null));
        pool.set(5, place(6, "그날 호텔", HOTEL, "LODGING", 37.7850, 126.9850));
        pool.add(place(7, "일출 명소", SIGHT, "SIGHT", 37.7800, 126.9800, 30, null).withIntent(true, "morning"));

        PlanState nearestFirst = midTripDay(pool, List.of(2, 7, 4, 5));
        DayScheduler.DayResult kept = DayScheduler.scheduleOne(nearestFirst, nearestFirst.days.get(0), PREVIOUS_HOTEL);
        PlanState morningFirst = midTripDay(pool, List.of(2, 7, 4, 5));
        morningFirst.days.get(0).morningOnlyFirst = true;
        DayScheduler.DayResult led = DayScheduler.scheduleOne(morningFirst, morningFirst.days.get(0), PREVIOUS_HOTEL);

        assertThat(reasons(kept)).contains(DropReason.LATE_START);
        assertThat(led.steps().get(0).place().name()).isEqualTo("일출 명소");
        assertThat(led.dropped()).isEmpty();
    }

    @Test
    @DisplayName("도보 여행에서 전날 숙소에서 출발하는 날은 아침 의도 장소를 앞당겨도 숙소가 첫 스텝으로 남는다")
    void walkDayKeepsLodgingFirstWhenMorningLeads() {
        List<PlaceCandidate> pool = oneDayPool();
        // 그날 장소가 모두 전날 호텔에서 3km 넘게 떨어져 도보 연속성 보정이 전날 호텔을 첫 스텝으로 붙인다.
        pool.set(1, place(2, "궁", SIGHT, "SIGHT", 37.5980, 126.9800));
        pool.set(2, place(3, "한옥마을", SIGHT, "SIGHT", 37.6020, 126.9790));
        pool.set(3, place(4, "점심 식당", MEAL, "MEAL", 37.5990, 126.9810));
        pool.set(4, place(5, "저녁 식당", MEAL, "MEAL", 37.6005, 126.9830));
        pool.set(5, place(6, "그날 호텔", HOTEL, "LODGING", 37.6010, 126.9840));
        pool.add(place(7, "일출 명소", SIGHT, "SIGHT", 37.6000, 126.9820, 30, null).withIntent(true, "morning"));
        PlanState s = oneDay(pool, new SelectionOutput.DayPlan(3, null, List.of(2, 7, 4, 5), HOTEL_TODAY, null),
                List.of(), "walk", null);
        s.days.get(0).morningOnlyFirst = true;

        List<String> names = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL).steps().stream()
                .map(step -> step.place().name()).toList();

        assertThat(names.get(0)).isEqualTo("전날 호텔");
        assertThat(names.get(1)).isEqualTo("일출 명소");
        assertThat(names).containsOnlyOnce("전날 호텔");
    }

    @Test
    @DisplayName("그날 숙소가 하루 구성에 섞여 있어도 체크인은 마지막 한 번이다")
    void checkInStaysLastWhenLodgingIsAmongPicks() {
        PlanState s = midTripDay(oneDayPool(), List.of(2, HOTEL_TODAY, 4, 3, 5));

        List<String> names = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL).steps().stream()
                .map(step -> step.place().name()).toList();

        assertThat(names).endsWith("그날 호텔").containsOnlyOnce("그날 호텔");
    }

    @Test
    @DisplayName("도보 여행에서 숙소를 바꾸는 날의 아침 출발은 숙소 시간대가 저녁으로 보강돼 있어도 맨 앞이다")
    void walkCheckoutLeadsTheDayEvenForEveningLodging() {
        List<PlaceCandidate> pool = walkDayPool();
        pool.set(0, new PlaceCandidate(1, 1L, "전날 호텔", "서울 종로구", HOTEL, List.of(), "Seoul", "KR",
                BigDecimal.valueOf(37.5700), BigDecimal.valueOf(126.9830), null, null, 0.8, null, null,
                List.of("저녁"), 60, false, null, null, null, null, "LODGING", null, false, null));
        PlanState s = oneDay(pool, new SelectionOutput.DayPlan(3, null, List.of(2, 3, 4, 5), HOTEL_TODAY, null),
                List.of(), "walk", null);

        List<StepData> steps = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL).steps();

        assertThat(steps.get(0).place().name()).isEqualTo("전날 호텔");
        assertThat(toMinutesOrZero(steps.get(0).startTime())).isLessThan(ScheduleTuning.LUNCH_START);
        assertThat(steps.get(steps.size() - 1).place().name()).isEqualTo("그날 호텔");
    }

    @Test
    @DisplayName("도보 여행에서 같은 숙소에 연박하는 날은 아침 출발 스텝 없이 저녁 체크인으로 끝난다")
    void walkStayInSameLodgingEndsAtLodging() {
        PlanState s = oneDay(walkDayPool(), new SelectionOutput.DayPlan(3, null, List.of(2, 3, 4, 5),
                PREVIOUS_HOTEL, null), List.of(), "walk", null);

        List<String> names = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL).steps().stream()
                .map(step -> step.place().name()).toList();

        assertThat(names).endsWith("전날 호텔").containsOnlyOnce("전날 호텔");
    }

    @Test
    @DisplayName("모두 보호된 날의 거리 교체는 다른 날 몫으로 잡아 둔 예비 장소를 쓰지 않는다")
    void distanceSwapSkipsPlacesReservedForOtherDays() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "먼 전망대", SIGHT, "SIGHT", 36.3500, 127.3800));
        pool.add(place(8, "가까운 사적지", SIGHT, "SIGHT", 37.5750, 126.9800));

        assertThat(swapFarSight(pool, Set.of())).contains(8).doesNotContain(7);
        assertThat(swapFarSight(pool, Set.of(8))).contains(7).doesNotContain(8);
    }

    /** 궁·먼 전망대·한옥마을을 모두 보호한 채 거리 예산을 맞춘 순서. 예비 후보는 가까운 사적지 하나다. */
    private static List<Integer> swapFarSight(List<PlaceCandidate> pool, Set<Integer> reservedElsewhere) {
        DayState day = new DayState(new SelectionOutput.DayPlan(3, null, new ArrayList<>(List.of(2, 7, 3)), null, null));
        List<Integer> seq = new ArrayList<>(List.of(2, 7, 3));
        Set<Integer> used = new HashSet<>(List.of(2, 7, 3));
        used.addAll(reservedElsewhere);
        DistanceBudget.trimSequenceOverBudget(day, seq, pool, new ArrayDeque<>(List.of(8)), "car", Set.of(2, 7, 3),
                null, new ScheduleConfig(9 * 60, 22 * 60, 0, false, "normal"), Set.of(), used);
        return seq;
    }

    @Test
    @DisplayName("전날 숙소가 없으면 첫 스텝에 이동 정보를 붙이지 않는다")
    void firstStepHasNoLegWithoutPreviousLodging() {
        PlanState s = midTripDay(oneDayPool(), List.of(2, 4, 3, 5));

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), null);

        assertThat(result.steps().get(0).transportationDistance()).isNull();
        assertThat(s.days.get(0).startLodgingIndex).isNull();
    }

    @Test
    @DisplayName("하루 세 끼를 넘는 식당과 구간 상한을 넘는 관광지는 사유와 함께 뺀 목록에 나온다")
    void reportsDroppedPlacesWithReasons() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "식당 셋", MEAL, "MEAL", 37.5740, 126.9860));
        pool.add(place(8, "식당 넷", MEAL, "MEAL", 37.5730, 126.9870));
        pool.add(place(9, "먼 전망대", SIGHT, "SIGHT", 36.3500, 127.3800));
        PlanState s = midTripDay(pool, List.of(2, 4, 7, 8, 3, 5, 9));
        DayState day = s.days.get(0);

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, day, PREVIOUS_HOTEL);

        assertThat(result.dropped()).extracting(DayState.Drop::reason)
                .containsExactlyInAnyOrder(DropReason.DISTANCE, DropReason.EXTRA_MEAL);
        assertThat(result.dropped()).filteredOn(d -> d.reason() == DropReason.DISTANCE)
                .extracting(DayState.Drop::index, DayState.Drop::replacedBy).containsExactly(tuple(9, null));
        assertThat(result.steps()).extracting(step -> step.place().name()).doesNotContain("먼 전망대");
        assertThat(day.placeIndices).doesNotContainAnyElementsOf(
                result.dropped().stream().map(DayState.Drop::index).toList());
    }

    @Test
    @DisplayName("먼 관광지를 가까운 예비 후보로 바꾸면 대신 넣은 장소와 함께 나온다")
    void reportsSwapWithReplacement() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "먼 전망대", SIGHT, "SIGHT", 36.3500, 127.3800));
        pool.add(place(8, "가까운 사적지", SIGHT, "SIGHT", 37.5750, 126.9800));
        PlanState s = oneDay(pool, new SelectionOutput.DayPlan(3, null, List.of(2, 4, 3, 5, 7), HOTEL_TODAY, null),
                List.of(8), "car", null);

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(result.dropped()).extracting(DayState.Drop::index, DayState.Drop::reason,
                DayState.Drop::replacedBy).containsExactly(tuple(7, DropReason.DISTANCE, 8));
        assertThat(result.steps()).extracting(step -> step.place().name()).contains("가까운 사적지");
    }

    @Test
    @DisplayName("시간 예산에 다 들어가지 않는 긴 관광지는 TIME_BUDGET으로 빠진다")
    void longSightsOverTimeBudget() {
        List<PlaceCandidate> pool = oneDayPool();
        List<Integer> picks = new ArrayList<>(List.of(2, 4, 3, 5));
        for (int i = 7; i <= 11; i++) {
            pool.add(place(i, "긴 관광 " + i, SIGHT, "SIGHT", 37.5700 + i * 0.001, 126.9800, 180, null));
            picks.add(i);
        }
        PlanState s = midTripDay(pool, picks);

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(reasons(result)).isNotEmpty().containsOnly(DropReason.TIME_BUDGET);
    }

    @Test
    @DisplayName("배정된 시각에 닫혀 있는 관광지는 CLOSED_AT_TIME으로 빠진다")
    void closedAtAssignedTime() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "새벽 시장", SIGHT, "SIGHT", 37.5710, 126.9820, 60, "목요일: 오전 5:00 ~ 오전 7:00"));
        PlanState s = midTripDay(pool, List.of(2, 4, 3, 5, 7));

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(result.dropped()).extracting(DayState.Drop::index, DayState.Drop::reason)
                .containsExactly(tuple(7, DropReason.CLOSED_AT_TIME));
    }

    @Test
    @DisplayName("어느 끼니 시각에도 열지 않는 식당은 NO_MEAL_SLOT으로 빠진다")
    void mealOpenAtNoSlot() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "오후만 여는 식당", MEAL, "MEAL", 37.5740, 126.9860, 60, "목요일: 오후 3:00 ~ 오후 4:00"));
        PlanState s = midTripDay(pool, List.of(2, 4, 7, 3, 5));

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(result.dropped()).extracting(DayState.Drop::index, DayState.Drop::reason)
                .containsExactly(tuple(7, DropReason.NO_MEAL_SLOT));
    }

    @Test
    @DisplayName("세 끼 중 첫 식당이 그날 숙소에서 멀면 BREAKFAST로 빠진다")
    void distantBreakfast() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.set(0, place(1, "전날 호텔", HOTEL, "LODGING", 37.7000, 126.9830));
        pool.add(place(7, "전날 호텔 옆 식당", MEAL, "MEAL", 37.7010, 126.9830));
        PlanState s = midTripDay(pool, List.of(7, 2, 4, 3, 5));

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(result.dropped()).extracting(DayState.Drop::index, DayState.Drop::reason)
                .containsExactly(tuple(7, DropReason.BREAKFAST));
    }

    @Test
    @DisplayName("도착 거점에서 시작하는 날은 세 끼 중 첫 식당이 BREAKFAST로 빠지고 전날 숙소를 출발지로 쓰지 않는다")
    void arrivalHubDayHasNoBreakfast() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "서울역", STATION, "TRANSIT", 37.5547, 126.9707));
        pool.add(place(8, "역 앞 식당", MEAL, "MEAL", 37.5560, 126.9720));
        PlanState s = oneDay(pool, new SelectionOutput.DayPlan(3, 7, List.of(8, 2, 4, 3, 5), HOTEL_TODAY, null),
                List.of(), "car", null);
        DayState day = s.days.get(0);

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, day, PREVIOUS_HOTEL);

        assertThat(reasons(result)).containsExactly(DropReason.BREAKFAST);
        assertThat(result.steps().get(0).place().name()).isEqualTo("서울역");
        assertThat(day.startLodgingIndex).isNull();
    }

    @Test
    @DisplayName("저녁 뒤 활동이 숙소에서 멀면 FAR_FROM_LODGING으로 빠진다")
    void eveningActivityFarFromLodging() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "먼 술집", BAR, "NIGHTLIFE", 37.6800, 126.9910));
        PlanState s = midTripDay(pool, List.of(2, 4, 3, 5, 7));

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(result.dropped()).extracting(DayState.Drop::index, DayState.Drop::reason)
                .containsExactly(tuple(7, DropReason.FAR_FROM_LODGING));
    }

    @Test
    @DisplayName("출발 기한을 넘기는 날은 뒤쪽 활동이 DEPARTURE_TIME으로 빠진다")
    void trimmedForDepartureDeadline() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "서울역", STATION, "TRANSIT", 37.5547, 126.9707));
        pool.add(place(8, "북쪽 사적지", SIGHT, "SIGHT", 37.7000, 126.9770));
        pool.add(place(9, "남쪽 사적지", SIGHT, "SIGHT", 37.4500, 126.9770));
        PlanState s = oneDay(pool, new SelectionOutput.DayPlan(3, null, List.of(8, 9), null, 7), List.of(), "car",
                "13:00");

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(result.dropped()).extracting(DayState.Drop::index, DayState.Drop::reason)
                .containsExactly(tuple(9, DropReason.DEPARTURE_TIME));
        assertThat(result.steps()).extracting(step -> step.place().name()).containsExactly("북쪽 사적지", "서울역");
    }

    @Test
    @DisplayName("도보 여행에서 앞에 붙인 전날 숙소는 프런트 운영시간과 관계없이 아침 출발로 남고 뺀 목록에 나오지 않는다")
    void walkAnchorIgnoresFrontDeskHours() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.set(0, place(1, "전날 호텔", HOTEL, "LODGING", 37.6100, 126.9830, 60, "목요일: 오후 3:00 ~ 오후 11:00"));
        PlanState s = oneDay(pool, new SelectionOutput.DayPlan(3, null, List.of(2, 4, 3, 5), HOTEL_TODAY, null),
                List.of(), "walk", null);

        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, s.days.get(0), PREVIOUS_HOTEL);

        assertThat(result.steps().get(0).place().name()).isEqualTo("전날 호텔");
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    @DisplayName("대신 넣은 장소가 같은 확정에서 다시 빠지면 원래 구성이 아니라 뺀 목록에 나오지 않는다")
    void replacementDroppedAgainIsNotReported() {
        DayState day = new DayState(new SelectionOutput.DayPlan(1, null, List.of(2, 3), null, null));
        day.resetDrops();

        day.noteSwap(2, DropReason.DISTANCE, 9);
        PlanEdits.replaceInDay(day, 2, 9);
        day.drop(9, DropReason.TIME_BUDGET);
        day.drop(3, DropReason.TIME_BUDGET);
        day.placeIndices.add(3);
        day.drop(3, DropReason.LATE_START);

        assertThat(day.dropped()).extracting(DayState.Drop::index, DayState.Drop::reason, DayState.Drop::replacedBy)
                .containsExactly(tuple(2, DropReason.DISTANCE, 9), tuple(3, DropReason.TIME_BUDGET, null));
    }

    @Test
    @DisplayName("다시 확정하면 앞 확정의 뺀 목록이 남지 않는다")
    void dropsAreResetOnEachRun() {
        List<PlaceCandidate> pool = oneDayPool();
        pool.add(place(7, "먼 전망대", SIGHT, "SIGHT", 36.3500, 127.3800));
        PlanState s = midTripDay(pool, List.of(2, 4, 3, 5, 7));
        DayState day = s.days.get(0);

        assertThat(DayScheduler.scheduleOne(s, day, PREVIOUS_HOTEL).dropped()).hasSize(1);
        assertThat(DayScheduler.scheduleOne(s, day, PREVIOUS_HOTEL).dropped()).isEmpty();
    }

    @Test
    @DisplayName("운영 기록의 모든 날에서 확정 중 그날 구성에서 사라진 장소는 빠짐없이 사유가 남는다")
    void everyRemovedPlaceHasReason() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            PlanState s = PlanState.of(trace.selection(), trace.candidates(), trace.args());
            s.beginScheduling();
            for (int i = 0; i < s.days.size(); i++) {
                DayState day = s.days.get(i);
                List<Integer> before = new ArrayList<>(day.placeIndices);
                Integer previousLodging = i > 0 ? s.days.get(i - 1).accommodationIndex : null;

                DayScheduler.DayResult result = DayScheduler.scheduleOne(s, day, previousLodging);

                List<Integer> removed = before.stream().filter(idx -> !day.placeIndices.contains(idx)).toList();
                assertThat(result.dropped()).as("day %d", day.dayNumber)
                        .extracting(DayState.Drop::index).containsExactlyInAnyOrderElementsOf(removed);
            }
        }
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                       double lng) {
        return place(index, name, category, role, lat, lng, 60, null);
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                       double lng, int visitMinutes, String openingHours) {
        return new PlaceCandidate(index, (long) index, name, "서울 종로구", category, List.of(), "Seoul", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, openingHours, null,
                visitMinutes, false, null, null, null, null, role, null, false, null);
    }
}
