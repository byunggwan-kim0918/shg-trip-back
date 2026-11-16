package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 대안을 여행 전체 기준으로 고르는지: 다른 날에 나온 장소, 여행 전체 끼니 종류 상한, 이미 간 브랜드. */
class AlternativeTripViewTest {

    private static final String MEAL = "Dining and Drinking > Restaurant";

    /** 1 원래 식당(국밥), 2~5 근처 식당. */
    private final List<PlaceCandidate> pool = List.of(
            place(1, "할매국밥", 35.1000, 129.0300),
            place(2, "바다횟집", 35.1005, 129.0300),
            place(3, "짬뽕지존", 35.1010, 129.0300),
            place(4, "평양냉면", 35.1015, 129.0300),
            place(5, "본가국밥 서면점", 35.1020, 129.0300));

    @Test
    @DisplayName("다른 날 대안으로 나온 장소는 빼고, 그러면 두 개가 안 될 때만 다시 쓴다")
    void avoidsAlternativesShownOnOtherDays() {
        AlternativeBuilder.TripView elsewhere = new AlternativeBuilder.TripView(
                Set.of(AlternativeBuilder.altKey(pool.get(1)), AlternativeBuilder.altKey(pool.get(2))), Map.of(),
                Integer.MAX_VALUE, Set.of());

        assertThat(names(build(elsewhere))).containsExactlyInAnyOrder("평양냉면", "본가국밥 서면점");

        AlternativeBuilder.TripView almostAll = new AlternativeBuilder.TripView(Set.of(
                AlternativeBuilder.altKey(pool.get(1)), AlternativeBuilder.altKey(pool.get(2)),
                AlternativeBuilder.altKey(pool.get(3))), Map.of(), Integer.MAX_VALUE, Set.of());
        assertThat(build(almostAll)).hasSize(AlternativeBuilder.MIN_ALTERNATIVES);
    }

    @Test
    @DisplayName("여행 전체 상한에 닿은 종류와 이미 간 브랜드는 대안 순위를 뒤로 미루되 빼지는 않는다")
    void demotesCappedKindsAndVisitedBrands() {
        AlternativeBuilder.TripView trip = new AlternativeBuilder.TripView(Set.of(), Map.of("raw_fish", 2), 2,
                Set.of("본가국밥"));

        List<String> names = names(build(trip));

        assertThat(names).hasSize(3);
        assertThat(names.subList(0, 2)).containsExactlyInAnyOrder("짬뽕지존", "평양냉면");
    }

    private List<AlternativeData> build(AlternativeBuilder.TripView trip) {
        return AlternativeBuilder.buildAlternatives(pool.get(0), null, new ArrayDeque<>(), new HashSet<>(Set.of(1)),
                pool, "walk", new HashSet<>(), c -> true, trip);
    }

    private static List<String> names(List<AlternativeData> alternatives) {
        return alternatives.stream().map(AlternativeData::name).toList();
    }

    private static PlaceCandidate place(int index, String name, double lat, double lng) {
        return new PlaceCandidate(index, (long) index, name, "부산 부산진구 테스트로 " + index, MEAL, List.of(), "Busan",
                "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60, false,
                null, null, null, null, "MEAL", null, false, null);
    }
}
