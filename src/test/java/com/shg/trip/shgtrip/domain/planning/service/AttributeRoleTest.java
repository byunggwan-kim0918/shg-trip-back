package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeRoleTest {

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";

    private static PlaceCandidate cand(String name, String category, String role, PlaceAttributes attributes) {
        return new PlaceCandidate(1, 1L, name, "주소", category, List.of(), "Seoul", "KR",
                BigDecimal.ONE, BigDecimal.ONE, null, null, 0.9, null, null, null, null, false, null, null,
                null, null, role, null, false, null, attributes);
    }

    private static PlaceAttributes venue(String v) {
        return new PlaceAttributes(v, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("역할이 없으면 업종 속성으로 끼니를 정한다: 총칭 카테고리 베이커리는 끼니가 아니고, 이름에 '커피'가 든 돈가스집은 끼니다")
    void mealByVenue() {
        assertThat(cand("르뱅", RESTAURANT, null, null).mealEligible()).isTrue();
        assertThat(cand("르뱅", RESTAURANT, null, venue("BAKERY")).mealEligible()).isFalse();
        assertThat(cand("돈까스 인 커피", RESTAURANT, null, venue("MEAL")).mealEligible()).isTrue();
    }

    @Test
    @DisplayName("역할 값이 있으면 속성보다 우선한다")
    void roleWins() {
        assertThat(cand("시장 국수", RESTAURANT, "MARKET", venue("MEAL")).mealEligible()).isFalse();
        assertThat(CandidatePolicy.roleOf(cand("시장 국수", RESTAURANT, "MARKET", venue("MEAL"))))
                .isEqualTo(CandidatePolicy.Role.MARKET);
    }

    @Test
    @DisplayName("업종 → 역할은 v1 보강과 같다: 카페·베이커리·디저트·간식은 CAFE, 술집은 BAR")
    void venueToRole() {
        assertThat(CandidatePolicy.roleOf(venue("MEAL"))).isEqualTo(CandidatePolicy.Role.MEAL);
        assertThat(CandidatePolicy.roleOf(venue("BAKERY"))).isEqualTo(CandidatePolicy.Role.CAFE);
        assertThat(CandidatePolicy.roleOf(venue("SNACK"))).isEqualTo(CandidatePolicy.Role.CAFE);
        assertThat(CandidatePolicy.roleOf(venue("BAR"))).isEqualTo(CandidatePolicy.Role.BAR);
    }

    @Test
    @DisplayName("숙소 유형이 있으면 관광지 카테고리여도 숙소, 판정할 속성이 없으면 카테고리 추정")
    void lodgingAndFallback() {
        PlaceAttributes resort = new PlaceAttributes(null, null, null, null, "RESORT", null, null);
        String sight = "Landmarks and Outdoors > Tourist Attraction";

        assertThat(CandidatePolicy.roleOf(cand("금호리조트 제주", sight, null, resort)))
                .isEqualTo(CandidatePolicy.Role.LODGING);
        assertThat(CandidatePolicy.roleOf(cand("금호리조트 제주", sight, null, null)))
                .isEqualTo(CandidatePolicy.Role.SIGHT);
        assertThat(CandidatePolicy.roleOf((PlaceAttributes) null)).isNull();
    }
}
