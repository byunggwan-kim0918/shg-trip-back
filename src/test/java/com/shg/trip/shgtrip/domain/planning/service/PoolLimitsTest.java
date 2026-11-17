package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
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
    @DisplayName("빠진 점심은 그날 장소 근처에 안 쓴 식당이 있으면 고칠 수 있는 위반이다")
    void missingLunchWithNearbyRestaurantIsNotLimited() {
        List<PlaceCandidate> pool = lunchlessPool();
        pool.add(place(4, "산채식당", MEAL, "MEAL", 37.5790, 126.9790));

        assertThat(limits(withoutLunch(), pool)).doesNotContain(new QualityGate.PoolLimit(Rule.LUNCH_MISSING, 1, List.of()));
    }

    @Test
    @DisplayName("빠진 점심은 구간 상한 안에 안 쓴 식당이 없으면 풀 한계다")
    void missingLunchWithoutReachableRestaurantIsLimited() {
        List<PlaceCandidate> pool = lunchlessPool();
        pool.add(place(4, "먼 식당", MEAL, "MEAL", 37.4000, 127.1500));

        assertThat(limits(withoutLunch(), pool)).contains(new QualityGate.PoolLimit(Rule.LUNCH_MISSING, 1, List.of()));
    }

    @Test
    @DisplayName("같은 날 같은 종류 끼니는 끼니 종류 분류와 같은 기준으로 풀 한계를 정한다")
    void sameDayKindFollowsMealKindSwaps() {
        List<PlaceCandidate> far = pool();
        far.add(place(4, "먼 바다횟집", MEAL, "MEAL", 37.4000, 127.1500));
        List<PlaceCandidate> near = pool();
        near.add(place(4, "바다횟집", MEAL, "MEAL", 37.5790, 126.9790));

        QualityGate.PoolLimit sameDay = new QualityGate.PoolLimit(Rule.SAME_DAY_MEAL_KIND, 1,
                List.of("할매국밥", "명가국밥"));
        assertThat(limits(gukbapTwice(), far)).contains(sameDay);
        assertThat(limits(gukbapTwice(), near)).doesNotContain(sameDay);
    }

    @Test
    @DisplayName("편집으로 다른 장소가 같은 규칙을 어기면 저장된 풀 한계로 가리지 않는다")
    void otherPlacesBreakingTheSameRuleAreNotLimited() {
        List<QualityGate.PoolLimit> stored = List.of(new QualityGate.PoolLimit(Rule.SAME_DAY_MEAL_KIND, 1,
                List.of("할매국밥", "명가국밥")));

        assertThat(PoolLimits.isLimited(new ItineraryRules.Violation(Rule.SAME_DAY_MEAL_KIND, 1, "",
                List.of("할매국밥", "명가국밥")), stored)).isTrue();
        assertThat(PoolLimits.isLimited(new ItineraryRules.Violation(Rule.SAME_DAY_MEAL_KIND, 1, "",
                List.of("명가국밥", "할매국밥")), stored)).isTrue();
        assertThat(PoolLimits.isLimited(new ItineraryRules.Violation(Rule.SAME_DAY_MEAL_KIND, 1, "",
                List.of("할매국밥", "바다횟집")), stored)).isFalse();
    }

    @Test
    @DisplayName("풀 한계가 아닌 핵심 위반이 하나라도 남으면 조정 필요다")
    void needsAdjustmentWhenAnyCoreViolationIsNotLimited() {
        List<PlaceCandidate> pool = lunchlessPool();
        pool.add(place(4, "먼 식당", MEAL, "MEAL", 37.4000, 127.1500));
        ItineraryRules.Verdict verdict = new ItineraryRules().evaluate(withoutLunch(), pool, CTX);
        List<QualityGate.PoolLimit> limits = PoolLimits.of(verdict, withoutLunch(), pool, CTX);

        assertThat(verdict.core()).isTrue();
        assertThat(PoolLimits.needsAdjustment(verdict, limits)).isFalse();
        assertThat(PoolLimits.needsAdjustment(verdict, List.of())).isTrue();
    }

    private static List<QualityGate.PoolLimit> limits(List<StepData> steps, List<PlaceCandidate> pool) {
        ItineraryRules.Verdict verdict = new ItineraryRules().evaluate(steps, pool, CTX);
        return PoolLimits.of(verdict, steps, pool, CTX);
    }

    /** 궁을 사이에 두고 점심·저녁이 모두 국밥인 하루. */
    private static List<StepData> gukbapTwice() {
        return List.of(step(1, "할매국밥", MEAL, "11:30", "12:40"), step(2, "궁", SIGHT, "13:00", "15:00"),
                step(3, "명가국밥", MEAL, "17:30", "18:40"));
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
