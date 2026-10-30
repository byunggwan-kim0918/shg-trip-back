package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
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
        return routeOptimizer.schedule(selection, candidates, "normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant")).steps();
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
        List<PlaceCandidate> out = routeOptimizer.applyIntentHints(
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

        List<PlaceCandidate> out = routeOptimizer.applyIntentHints(withIntent(List.of(
                new SelectionOutput.TimingHint(meal, "evening"),
                new SelectionOutput.TimingHint(CHEONJIYEON, "evening"),
                new SelectionOutput.TimingHint(SEONGSAN, "midnight")), List.of(CHEONJIYEON)), candidates);

        assertThat(out.get(meal - 1)).isSameAs(candidates.get(meal - 1));
        assertThat(out.get(CHEONJIYEON - 1)).isSameAs(candidates.get(CHEONJIYEON - 1));
        assertThat(out.get(SEONGSAN - 1)).isSameAs(candidates.get(SEONGSAN - 1));
    }

    @Test
    @DisplayName("보호는 날짜별 상한까지, timing 장소가 highlight보다 먼저 보호된다")
    void protectionIsCappedPerDayWithTimingFirst() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        SelectionOutput base = Itinerary115Fixture.selection();
        List<Integer> all = base.days().stream().flatMap(d -> d.placeIndices().stream()).toList();
        // 3일차(12,13,14,22) 비식당 3곳 중 마지막(14)에만 timing → highlight 둘보다 먼저 보호돼야 한다
        List<PlaceCandidate> out = routeOptimizer.applyIntentHints(withIntent(
                List.of(new SelectionOutput.TimingHint(14, "evening")), all), candidates);

        Map<Integer, Long> protectedPerDay = base.days().stream().collect(Collectors.toMap(
                SelectionOutput.DayPlan::dayNumber,
                d -> d.placeIndices().stream().filter(i -> out.get(i - 1).intentProtected()).count()));
        assertThat(protectedPerDay.values()).allSatisfy(n ->
                assertThat(n).isLessThanOrEqualTo((long) ScheduleTuning.INTENT_PROTECT_PER_DAY));
        assertThat(out.get(14 - 1).intentProtected()).isTrue();
    }
}
