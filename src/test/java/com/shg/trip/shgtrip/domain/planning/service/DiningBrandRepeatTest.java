package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 빈 시간 보충·카페 스톱·근처 교체는 일정에 이미 있는 체인의 다른 지점보다 다른 가게를 먼저 넣는다. */
class DiningBrandRepeatTest {

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House";
    private static final String PARK = "Landmarks and Outdoors > Park";

    /** 같은 체인의 다른 지점(4)이 더 유명하고 가깝다. */
    private final List<PlaceCandidate> candidates = List.of(
            place(1, "점심 식당", RESTAURANT, "MEAL", 37.5000, 127.0000, null),
            place(2, "저녁 식당", RESTAURANT, "MEAL", 37.5000, 127.0100, null),
            place(3, "스타벅스 강남점", CAFE, "CAFE", 37.5010, 127.0020, null),
            place(4, "스타벅스 성수점", CAFE, "CAFE", 37.5010, 127.0030, 40),
            place(5, "블루보틀", CAFE, "CAFE", 37.5020, 127.0040, null),
            place(6, "골목 공원", PARK, "SIGHT", 37.5030, 127.0050, null));

    @Test
    @DisplayName("빈 시간 보충은 이미 간 체인의 다른 지점보다 다른 카페를 넣는다")
    void gapFillSkipsVisitedBrand() {
        DayState day = day(1, 3, 2);

        GapFill result = GapFiller.fillGapBeforeMeal(day, candidates, new ArrayDeque<>(List.of(4, 5)),
                new HashSet<>(day.placeIndices), candidates.get(0), 13 * 60, 17 * 60 + 30, candidates.get(1), null,
                "car", 0, new HashSet<>(), new ScheduleConfig(9 * 60, 22 * 60, 0, false, "relaxed"), new int[]{0},
                AlternativeBuilder.TripView.NONE);

        assertThat(result.steps()).extracting(StepData::place).extracting(p -> p.name()).containsExactly("블루보틀");
    }

    @Test
    @DisplayName("카페 스톱은 다른 날 이미 간 체인의 다른 지점보다 다른 카페를 고른다")
    void cafeStopSkipsVisitedBrand() {
        List<DayState> days = List.of(day(6, 3), day(6), day(6));

        MealPlanner.ensureCafeStops(days, candidates, new ArrayDeque<>(List.of(4, 5)),
                new ScheduleConfig(9 * 60, 22 * 60, 0, false, "normal", true), "car", Set.of());

        assertThat(days.get(1).cafeStopIndex).isEqualTo(5);
    }

    @Test
    @DisplayName("빈 시간 보충은 앞 도시 구간에서 간 체인도 이미 간 브랜드로 본다")
    void gapFillSkipsBrandFromEarlierCity() {
        DayState day = day(1, 2);
        AlternativeBuilder.TripView earlier = new AlternativeBuilder.TripView(Set.of(), java.util.Map.of(),
                Integer.MAX_VALUE, Set.of("스타벅스"));

        GapFill result = GapFiller.fillGapBeforeMeal(day, candidates, new ArrayDeque<>(List.of(4, 5)),
                new HashSet<>(day.placeIndices), candidates.get(0), 13 * 60, 17 * 60 + 30, candidates.get(1), null,
                "car", 0, new HashSet<>(), new ScheduleConfig(9 * 60, 22 * 60, 0, false, "relaxed"), new int[]{0},
                earlier);

        assertThat(result.steps()).extracting(StepData::place).extracting(p -> p.name()).containsExactly("블루보틀");
    }

    @Test
    @DisplayName("예비 목록에 이미 간 체인만 남았으면 근처 교체·카페 스톱은 미사용 후보의 다른 가게를 쓴다")
    void spareWithOnlyVisitedBrandFallsBackToOtherPlaces() {
        double[] anchor = {37.5000, 127.0000};

        assertThat(PlanEdits.pickNearbyCandidate(candidates, new ArrayDeque<>(List.of(4)), new HashSet<>(List.of(3)),
                anchor, CandidateTraits::isCafeLike)).isEqualTo(5);

        List<DayState> days = List.of(day(6, 3), day(6), day(6));
        MealPlanner.ensureCafeStops(days, candidates, new ArrayDeque<>(List.of(4)),
                new ScheduleConfig(9 * 60, 22 * 60, 0, false, "normal", true), "car", Set.of());
        assertThat(days.get(1).cafeStopIndex).isEqualTo(5);
    }

    @Test
    @DisplayName("카페 스톱은 앞 도시 구간에서 간 체인도 이미 간 브랜드로 본다")
    void cafeStopSkipsBrandFromEarlierCity() {
        List<DayState> days = List.of(day(6), day(6));

        MealPlanner.ensureCafeStops(days, candidates, new ArrayDeque<>(List.of(4, 5)),
                new ScheduleConfig(9 * 60, 22 * 60, 0, false, "normal", true), "car", Set.of("스타벅스"));

        assertThat(days.get(0).cafeStopIndex).isEqualTo(5);
    }

    @Test
    @DisplayName("근처 교체는 가장 가까운 곳이 이미 간 체인이면 2km 안쪽의 다른 가게를 고르고, 그보다 멀면 가까운 곳을 쓴다")
    void nearbyPickSkipsVisitedBrandOnlyNearby() {
        double[] anchor = {37.5000, 127.0000};
        List<PlaceCandidate> far = new ArrayList<>(candidates);
        far.set(4, place(5, "먼 카페", CAFE, "CAFE", 37.5400, 127.0000, null));

        assertThat(PlanEdits.pickNearbyCandidate(candidates, new ArrayDeque<>(), new HashSet<>(List.of(3)), anchor,
                CandidateTraits::isCafeLike)).isEqualTo(5);
        assertThat(PlanEdits.pickNearbyCandidate(far, new ArrayDeque<>(), new HashSet<>(List.of(3)), anchor,
                CandidateTraits::isCafeLike)).isEqualTo(4);
    }

    @Test
    @DisplayName("지점명이 붙어 쓰인 상호는 같은 브랜드로 보고, 짧은 일반어나 지점명이 아닌 뒷말은 묶지 않는다")
    void matchesBrandsWrittenWithoutSpaces() {
        assertThat(CandidateTraits.sameBrand("흑본오겹함덕점", "흑본오겹")).isTrue();
        assertThat(CandidateTraits.sameBrand("본가국밥", "본가국밥")).isTrue();
        assertThat(CandidateTraits.sameBrand("국밥", "국밥천국")).isFalse();
        assertThat(CandidateTraits.sameBrand("칼국수", "칼국수집해운대")).isFalse();
        assertThat(CandidateTraits.sameBrand("STARBUCKS", "Starbucks")).isTrue();
        assertThat(CandidateTraits.repeatsBrand(Set.of("스타벅스"),
                place(9, "스타벅스해운대점", CAFE, "CAFE", 37.5, 127.0, null))).isTrue();
    }

    private static DayState day(Integer... places) {
        return new DayState(new SelectionOutput.DayPlan(1, null, new ArrayList<>(List.of(places)), null, null));
    }

    private static PlaceCandidate place(int index, String name, String category, String role,
                                        double lat, double lng, Integer sitelinks) {
        return new PlaceCandidate(index, (long) index, name, "서울 성동구 테스트로 " + index, category, List.of(), "Seoul",
                "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.9, null, null,
                null, 60, false, null, null, null, sitelinks, role, null, false, null);
    }
}
