package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VarietyLedgerTest {

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House";
    private static final String BEACH = "Landmarks and Outdoors > Beach";

    private final List<PlaceCandidate> candidates = List.of(
            candidate(1, "토속초당순두부", RESTAURANT, "MEAL", "KOREAN_SET"),
            candidate(2, "그옛날 초당순두부", RESTAURANT, "MEAL", "KOREAN_SET"),
            candidate(3, "주문진 생선회센터", RESTAURANT, "MEAL", "SEAFOOD"),
            candidate(4, "카페 라카이", CAFE, "CAFE", null),
            candidate(5, "돌체테리아", CAFE, "CAFE", null),
            candidate(6, "커피베이 교동점", CAFE, "CAFE", null),
            candidate(7, "경포해수욕장", BEACH, "SIGHT", null),
            candidate(8, "한식당", RESTAURANT, "MEAL", "KOREAN_SET"),
            candidate(9, "나상면초당순두부", RESTAURANT, "MEAL", "KOREAN_SET"),
            candidate(10, "강문해변", BEACH, "SIGHT", null),
            candidate(11, "황금조개구이", RESTAURANT, "MEAL", "SEAFOOD"),
            candidate(12, "바다식당", RESTAURANT, "MEAL", "SEAFOOD"));

    @Test
    @DisplayName("그날 이미 든 끼니와 같은 종류는 막고, 다른 종류·종류 미상 식당과 관광지는 통과시킨다")
    void blocksSameKindOnDay() {
        var admits = VarietyLedger.admitsOnDay(day(1, 1, 7), candidates);

        assertThat(admits.test(candidate(2))).isFalse();
        assertThat(admits.test(candidate(3))).isTrue();
        assertThat(admits.test(candidate(8))).isTrue();
        assertThat(admits.test(candidate(10))).isTrue();
    }

    @Test
    @DisplayName("하루 카페가 상한(2곳)에 닿으면 세 번째 카페를 막는다")
    void capsCafesPerDay() {
        assertThat(VarietyLedger.admitsOnDay(day(1, 4), candidates).test(candidate(6))).isTrue();
        assertThat(VarietyLedger.admitsOnDay(day(1, 4, 5), candidates).test(candidate(6))).isFalse();
    }

    @Test
    @DisplayName("자리를 바꿀 때는 바뀌어 나갈 장소를 세지 않는다")
    void replacingIgnoresOutgoingPlace() {
        assertThat(VarietyLedger.admitsReplacing(day(1, 4, 5), 4, candidates).test(candidate(6))).isTrue();
        assertThat(VarietyLedger.admitsReplacing(day(1, 1, 3), 1, candidates).test(candidate(9))).isTrue();
        assertThat(VarietyLedger.admitsReplacing(day(1, 1, 3), 3, candidates).test(candidate(9))).isFalse();
        assertThat(VarietyLedger.admitsReplacing(day(1, 4, 5), 99, candidates).test(candidate(6))).isFalse();
    }

    @Test
    @DisplayName("요리가 달라도 그날 같은 묶음인 끼니는 선호 단계에서 거른다")
    void avoidsSameGroupOnDay() {
        var otherGroup = VarietyLedger.avoidsGroupOnDay(day(1, 3, 7), candidates);

        assertThat(otherGroup.test(candidate(11))).isFalse();
        assertThat(otherGroup.test(candidate(1))).isTrue();
        assertThat(otherGroup.test(candidate(6))).isTrue();
    }

    @Test
    @DisplayName("요리를 모르는 해산물집은 그날 횟집과 같은 끼니로 본다. 요리가 다른 조개구이는 통과한다")
    void unknownDishOverlapsItsGroup() {
        var admits = VarietyLedger.admitsOnDay(day(1, 3), candidates);

        assertThat(admits.test(candidate(12))).isFalse();
        assertThat(admits.test(candidate(11))).isTrue();
    }

    @Test
    @DisplayName("여러 곳을 한꺼번에 옮길 때는 옮겨 오는 곳끼리 겹치는 것도 막는다")
    void admitsAllCountsIncomingTogether() {
        assertThat(VarietyLedger.admitsAll(day(1, 4), List.of(5), candidates)).isTrue();
        assertThat(VarietyLedger.admitsAll(day(1, 4), List.of(5, 6), candidates)).isFalse();
        assertThat(VarietyLedger.admitsAll(day(1, 7), List.of(1, 2), candidates)).isFalse();
    }

    @Test
    @DisplayName("고치는 도중의 구성으로 세면 아직 빠지지 않은 장소는 보지 않는다")
    void admitsAmongUsesGivenPlaces() {
        assertThat(VarietyLedger.admitsAmong(List.of(7), candidates).test(candidate(2))).isTrue();
        assertThat(VarietyLedger.admitsAmong(List.of(1, 7), candidates).test(candidate(2))).isFalse();
    }

    private PlaceCandidate candidate(int index) {
        return candidates.get(index - 1);
    }

    private static DayState day(int dayNumber, Integer... places) {
        return new DayState(new SelectionOutput.DayPlan(dayNumber, null, new ArrayList<>(List.of(places)), null, null));
    }

    private static PlaceCandidate candidate(int index, String name, String category, String role, String cuisine) {
        return new PlaceCandidate(index, (long) index, name, "강원 강릉시 " + index, category, List.of(), "Gangneung",
                "KR", BigDecimal.valueOf(37.7), BigDecimal.valueOf(128.9), null, null, 0.9, null, null,
                null, 60, false, null, null, null, null, role, cuisine, false, null);
    }
}
