package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 하루 확정이 여행 전체 대안 조건을 받아 다른 날 대안과 겹치지 않게 고르는지. */
class TripAlternativesWiringTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String MEAL = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";

    @Test
    @DisplayName("두 날이 같은 동네여도 후보가 넉넉하면 방문 스텝 대안이 서로 겹치지 않는다")
    void alternativesDoNotRepeatAcrossDays() {
        // 모두 숙소 둘레 300m 안이라 다른 날 대안을 보지 않으면 두 날이 같은 가까운 곳을 대안으로 낸다.
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "호텔", HOTEL, "LODGING", 37.5760, 126.9800));
        for (int i = 0; i < 20; i++) {
            pool.add(place(2 + i, "궁 " + i, SIGHT, "SIGHT", 37.5750 + i * 0.0001, 126.9790 + i * 0.0001));
            pool.add(place(22 + i, "식당 " + i, MEAL, "MEAL", 37.5752 + i * 0.0001, 126.9792 + i * 0.0001));
        }
        pool.sort(java.util.Comparator.comparingInt(PlaceCandidate::index));
        SelectionOutput selection = new SelectionOutput("", List.of(
                new SelectionOutput.DayPlan(1, null, List.of(2, 22, 23), 1, null),
                new SelectionOutput.DayPlan(2, null, List.of(3, 24, 25), null, null)), List.of(), List.of());

        List<StepData> steps = new SerialPlanner().schedule(selection, pool, ScheduleTestArgs.of("normal", "walk",
                LocalDate.of(2026, 12, 1), List.of(), null, false, List.of())).steps();

        Set<String> dayOne = alternativesOn(steps, 1);
        Set<String> dayTwo = alternativesOn(steps, 2);
        assertThat(dayOne).isNotEmpty();
        assertThat(dayTwo).isNotEmpty().doesNotContainAnyElementsOf(dayOne);
    }

    private static Set<String> alternativesOn(List<StepData> steps, int day) {
        return steps.stream().filter(s -> s.dayNumber() == day && s.place() != null)
                .filter(s -> !"LODGING".equals(PlaceCategoryConstants.majorCategory(s.place().category())))
                .flatMap(s -> s.alternatives() == null ? java.util.stream.Stream.<AlternativeData>empty()
                        : s.alternatives().stream())
                .map(AlternativeData::name).collect(Collectors.toCollection(HashSet::new));
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                        double lng) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index, category, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60,
                false, null, null, null, null, role, null, false, null);
    }
}
