package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PoolLimitsTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String MEAL = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final ItineraryRules.Context CTX = new ItineraryRules.Context("normal", "walk", null, 1, null);

    @Test
    @DisplayName("저장된 풀 한계로 덮이지 않는 핵심 위반이 하나라도 남으면 조정 필요다")
    void needsAdjustmentWhenAnyCoreViolationIsNotLimited() {
        List<PlaceCandidate> pool = lunchlessPool();
        pool.add(place(4, "먼 식당", MEAL, "MEAL", 37.4000, 127.1500));
        ItineraryRules.Verdict verdict = new ItineraryRules().evaluate(withoutLunch(), pool, CTX);
        List<QualityGate.PoolLimit> limits = verdict.violations().stream().map(QualityGate.PoolLimit::of).toList();

        assertThat(verdict.core()).isTrue();
        assertThat(PoolLimits.needsAdjustment(verdict, limits)).isFalse();
        assertThat(PoolLimits.needsAdjustment(verdict, List.of())).isTrue();
    }

    private static List<StepData> withoutLunch() {
        return List.of(step(1, "궁", SIGHT, "10:00", "15:00"), step(2, "명가국밥", MEAL, "17:30", "18:40"));
    }

    private static List<PlaceCandidate> pool() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "할매국밥", MEAL, "MEAL", 37.5800, 126.9800));
        pool.add(place(2, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        pool.add(place(3, "명가국밥", MEAL, "MEAL", 37.5810, 126.9810));
        return pool;
    }

    private static List<PlaceCandidate> lunchlessPool() {
        List<PlaceCandidate> pool = pool();
        pool.remove(0);
        return pool;
    }

    private static StepData step(int order, String name, String category, String start, String end) {
        return new StepData(order, 1, start, end, new PlaceData(name, null, category, "Seoul", "KR"),
                List.of(), null, null, null, null, null, null);
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                        double lng) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index, category, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60,
                false, null, null, null, null, role, null, false, null);
    }
}
