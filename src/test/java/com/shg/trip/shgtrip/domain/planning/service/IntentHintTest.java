package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 요청 의도(timing·highlight)가 스케줄러까지 전달되고, 보호가 정합성 가드를 우회하지 않는지 고정한다. */
class IntentHintTest {

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    private static final int SONGAK_VIEW = 13;   // 송악산전망대 — 3일차, 데이터상 주간 야외 명소
    private static final int SEONGSAN = 10;      // 성산일출봉 — 2일차
    private static final int HYEOPJAE = 15;      // 협재해수욕장 — 마지막 날(출발일)
    private static final int CHEONJIYEON = 34;   // 천지연폭포 — spare
    private static final String ALL_CLOSED =
            "월요일: 휴무, 화요일: 휴무, 수요일: 휴무, 목요일: 휴무, 금요일: 휴무, 토요일: 휴무, 일요일: 휴무";

    private List<StepData> schedule(List<PlaceCandidate> candidates, SelectionOutput selection) {
        return routeOptimizer.schedule(selection, candidates,
                ScheduleTestArgs.of("normal", "car", LocalDate.of(2026, 11, 1), List.of("food", "romance"),
                        new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"), false, List.of("restaurant"))).steps();
    }

    private static SelectionOutput withIntent(List<SelectionOutput.TimingHint> timing, List<Integer> highlights) {
        SelectionOutput base = Itinerary115Fixture.selection();
        return new SelectionOutput(base.concept(), base.days(), base.pairs(), base.spareIndices(),
                highlights, List.of(), timing);
    }

    private static StepData stepOf(List<StepData> steps, List<PlaceCandidate> candidates, int index) {
        String name = candidates.get(index - 1).name();
        return steps.stream().filter(s -> s.place() != null && name.equals(s.place().name()))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("evening 의도 장소는 일정에 남고 17:30 이후, 자정 클램프 전에 시작한다")
    void eveningIntentLandsInEvening() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        StepData view = stepOf(schedule(candidates, withIntent(
                List.of(new SelectionOutput.TimingHint(SONGAK_VIEW, "evening")), List.of())), candidates, SONGAK_VIEW);

        assertThat(view).as("송악산전망대가 일정에 남아야 한다").isNotNull();
        assertThat(view.startTime()).isBetween("17:30", "22:00");
    }

    @Test
    @DisplayName("출발일의 evening 의도 장소는 전날 저녁으로 옮겨진다 (출발일엔 저녁이 없다)")
    void eveningIntentLeavesDepartureDay() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        StepData beach = stepOf(schedule(candidates, withIntent(
                List.of(new SelectionOutput.TimingHint(HYEOPJAE, "evening")), List.of())), candidates, HYEOPJAE);

        assertThat(beach).isNotNull();
        assertThat(beach.dayNumber()).isLessThan(4);
        assertThat(beach.startTime()).isGreaterThanOrEqualTo("17:30");
    }

    @Test
    @DisplayName("morning 의도 장소는 점심 전에 시작한다")
    void morningIntentLandsBeforeLunch() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        StepData seongsan = stepOf(schedule(candidates, withIntent(
                List.of(new SelectionOutput.TimingHint(SEONGSAN, "morning")), List.of())), candidates, SEONGSAN);

        assertThat(seongsan).isNotNull();
        assertThat(seongsan.startTime()).isLessThan("11:30");
    }

    @Test
    @DisplayName("의도 보호는 휴무 가드를 우회하지 않는다 — 매일 휴무인 highlight는 일정에 들어가지 않는다")
    void protectionDoesNotBypassClosedDayGuard() {
        List<PlaceCandidate> candidates = new ArrayList<>(Itinerary115Fixture.candidates());
        PlaceCandidate v = candidates.get(SONGAK_VIEW - 1);
        candidates.set(SONGAK_VIEW - 1, new PlaceCandidate(v.index(), v.placeId(), v.name(), v.address(),
                v.category(), v.tags(), v.region(), v.country(), v.latitude(), v.longitude(), v.description(),
                v.rating(), v.similarityScore(), v.priceLevel(), ALL_CLOSED, v.recommendedTimeSlots(),
                v.recommendedDurationMinutes(), false, v.admissionFee(), v.subRegion(), v.userRatingCount(),
                v.wikidataSitelinks(), v.placeRole(), v.cuisine()));

        List<StepData> steps = schedule(candidates, withIntent(List.of(), List.of(SONGAK_VIEW)));

        assertThat(stepOf(steps, candidates, SONGAK_VIEW)).isNull();
    }

    @Test
    @DisplayName("의도 보호는 userSelected를 켜지 않는다 (Manual 선택과 구분)")
    void protectionIsNotUserSelected() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        List<PlaceCandidate> out = IntentHints.apply(
                withIntent(List.of(), List.of(SONGAK_VIEW)), candidates);

        PlaceCandidate view = out.get(SONGAK_VIEW - 1);
        assertThat(view.intentProtected()).isTrue();
        assertThat(view.userSelected()).isFalse();
    }

    @Test
    @DisplayName("본선택 밖(spare)·식당·알 수 없는 시간대에는 의도를 싣지 않는다")
    void ignoresSparesMealsAndUnknownTimes() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        int meal = candidates.stream().filter(PlaceCandidate::mealEligible)
                .filter(c -> Itinerary115Fixture.selection().days().stream()
                        .anyMatch(d -> d.placeIndices().contains(c.index())))
                .findFirst().orElseThrow().index();
        assertThat(candidates.get(CHEONJIYEON - 1).mealEligible()).isFalse();
        assertThat(Itinerary115Fixture.selection().spareIndices()).contains(CHEONJIYEON);

        List<PlaceCandidate> out = IntentHints.apply(withIntent(List.of(
                new SelectionOutput.TimingHint(meal, "evening"),
                new SelectionOutput.TimingHint(CHEONJIYEON, "evening"),
                new SelectionOutput.TimingHint(SEONGSAN, "midnight")), List.of(CHEONJIYEON)), candidates);

        assertThat(out.get(meal - 1)).isSameAs(candidates.get(meal - 1));
        assertThat(out.get(CHEONJIYEON - 1)).isSameAs(candidates.get(CHEONJIYEON - 1));
        assertThat(out.get(SEONGSAN - 1)).isSameAs(candidates.get(SEONGSAN - 1));
    }

    @Test
    @DisplayName("필수 경험으로 실린 의도는 Sonnet 시간대보다 앞서고 날짜별 보호 상한에 세지 않는다")
    void requiredExperienceIntentWinsAndSkipsCap() {
        List<PlaceCandidate> candidates = new ArrayList<>(Itinerary115Fixture.candidates());
        candidates.set(SONGAK_VIEW - 1, candidates.get(SONGAK_VIEW - 1).withIntent(true, "evening"));
        // 3일차 비식당 12·13·14 중 13이 필수 경험이라 나머지 둘이 상한 2곳을 쓴다
        List<PlaceCandidate> out = IntentHints.apply(withIntent(
                List.of(new SelectionOutput.TimingHint(SONGAK_VIEW, "morning")), List.of(12, 14)), candidates);

        assertThat(out.get(SONGAK_VIEW - 1).intentTime()).isEqualTo("evening");
        assertThat(out.get(SONGAK_VIEW - 1).intentProtected()).isTrue();
        assertThat(out.get(12 - 1).intentProtected()).isTrue();
        assertThat(out.get(14 - 1).intentProtected()).isTrue();
    }

    @Test
    @DisplayName("보호는 날짜별 상한까지, timing 장소가 highlight보다 먼저 보호된다")
    void protectionIsCappedPerDayWithTimingFirst() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        SelectionOutput base = Itinerary115Fixture.selection();
        List<Integer> all = base.days().stream().flatMap(d -> d.placeIndices().stream()).toList();
        // 3일차(12,13,14,22) 비식당 3곳 중 마지막(14)에만 timing → highlight 둘보다 먼저 보호돼야 한다
        List<PlaceCandidate> out = IntentHints.apply(withIntent(
                List.of(new SelectionOutput.TimingHint(14, "evening")), all), candidates);

        Map<Integer, Long> protectedPerDay = base.days().stream().collect(Collectors.toMap(
                SelectionOutput.DayPlan::dayNumber,
                d -> d.placeIndices().stream().filter(i -> out.get(i - 1).intentProtected()).count()));
        assertThat(protectedPerDay.values()).allSatisfy(n ->
                assertThat(n).isLessThanOrEqualTo((long) ScheduleTuning.INTENT_PROTECT_PER_DAY));
        assertThat(out.get(14 - 1).intentProtected()).isTrue();
    }

    @Test
    @DisplayName("사용자가 고른 먼 장소 때문에 출발 시각을 놓치던 기록은 그 장소를 전날로 옮겨 출발 시각을 지킨다")
    void userPicksMoveWhenDepartureIsMissed() {
        TraceFile trace = TraceFixtures.load("/fixtures/promised-109-kyoto.json");

        List<StepData> steps = routeOptimizer.schedule(trace.selection(), trace.candidates(), trace.args()).steps();

        int lastDay = steps.stream().mapToInt(StepData::dayNumber).max().orElseThrow();
        StepData hub = steps.stream()
                .filter(st -> st.dayNumber() == lastDay && st.place().name().startsWith("Kansai International"))
                .findFirst().orElseThrow();
        assertThat(ScheduleTimes.toMinutesOrZero(hub.startTime()))
                .isLessThanOrEqualTo(ScheduleTimes.departureHubDeadline("19:00", "JP"));
        assertThat(steps).filteredOn(st -> List.of("比叡山", "嵐山").contains(st.place().name()))
                .hasSize(2).allSatisfy(st -> assertThat(st.dayNumber()).isLessThan(lastDay));
    }

    @Test
    @DisplayName("출발 시각을 준 마지막 날에서 출발 거점에서 가장 먼 사용자 선택 관광지 하나를 전날로 옮기고, 전날 활동 중 출발 거점에 가까운 것과 맞바꾼다")
    void userPickLeavesDeadlineDayBySwap() {
        List<PlaceCandidate> pool = List.of(
                pick(1, "공항", "Travel and Transportation > Transport Hub > Airport", 37.46, 126.44, false, null),
                pick(2, "먼 산", "Landmarks and Outdoors > Mountain", 37.70, 127.20, true, null),
                pick(3, "공항 쪽 공원", "Landmarks and Outdoors > Park", 37.48, 126.60, false, null),
                pick(4, "시내 궁", "Landmarks and Outdoors > Historic and Protected Site", 37.58, 126.98, false, null),
                pick(5, "전날 고른 궁", "Landmarks and Outdoors > Historic and Protected Site", 37.47, 126.50, true, null),
                pick(6, "점심 식당", "Dining and Drinking > Restaurant", 37.57, 126.98, true, null),
                pick(7, "공항 옆 전망대", "Landmarks and Outdoors > Scenic Lookout", 37.47, 126.45, true, null));
        DayState before = new DayState(new SelectionOutput.DayPlan(1, null, new ArrayList<>(List.of(3, 4, 5)),
                null, null));
        DayState last = new DayState(new SelectionOutput.DayPlan(2, null, new ArrayList<>(List.of(7, 2, 6)), null,
                null));
        last.departureHubIndex = 1;
        last.departureDeadline = 17 * 60;

        boolean moved = IntentHints.moveUserPickOffDeadlineDay(List.of(before, last), pool, null);

        assertThat(moved).isTrue();
        assertThat(before.placeIndices).containsExactlyInAnyOrder(4, 5, 2);
        assertThat(last.placeIndices).containsExactlyInAnyOrder(7, 6, 3);
    }

    @Test
    @DisplayName("사용자가 고른 장소는 출발 시각이 없거나 전날이 그곳 휴무일이면 옮기지 않는다")
    void userPickStaysWithoutDeadlineOrOnClosedDay() {
        List<PlaceCandidate> pool = List.of(
                pick(1, "공항", "Travel and Transportation > Transport Hub > Airport", 37.46, 126.44, false, null),
                pick(2, "월요일 휴관 박물관", "Arts and Entertainment > Museum", 37.70, 127.20, true,
                        "월요일: 휴무, 화요일: 09:00 ~ 18:00, 수요일: 09:00 ~ 18:00, 목요일: 09:00 ~ 18:00, "
                                + "금요일: 09:00 ~ 18:00, 토요일: 09:00 ~ 18:00, 일요일: 09:00 ~ 18:00"));
        DayState before = new DayState(new SelectionOutput.DayPlan(1, null, new ArrayList<>(), null, null));
        DayState last = new DayState(new SelectionOutput.DayPlan(2, null, new ArrayList<>(List.of(2)), null, null));
        last.departureHubIndex = 1;

        assertThat(IntentHints.moveUserPickOffDeadlineDay(List.of(before, last), pool, null)).isFalse();

        last.departureDeadline = 17 * 60;
        // 2026-11-16은 월요일이라 1일차가 휴관일이다.
        assertThat(IntentHints.moveUserPickOffDeadlineDay(List.of(before, last), pool, LocalDate.of(2026, 11, 16)))
                .isFalse();
        assertThat(last.placeIndices).containsExactly(2);
    }

    private static PlaceCandidate pick(int index, String name, String category, double lat, double lng,
                                       boolean userSelected, String openingHours) {
        return new PlaceCandidate(index, (long) index, name, "서울", category, List.of(), "Seoul", "KR",
                java.math.BigDecimal.valueOf(lat), java.math.BigDecimal.valueOf(lng), null, null, 0.8, null,
                openingHours, null, 60, userSelected, null, null, null, null, null, null, false, null);
    }
}
