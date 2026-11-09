package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedPlaceTest {

    private static final String SHRINE = "Community and Government > Spiritual Center > Shrine";

    private static PlaceCandidate cand(String name, String role, boolean userSelected) {
        return new PlaceCandidate(1, 1L, name, "주소", SHRINE, List.of(), "Tokyo", "JP",
                BigDecimal.ONE, BigDecimal.ONE, null, null, 0.9, null, null, null, null, userSelected, null, null,
                null, null, role, null, false, null, null);
    }

    @Test
    @DisplayName("추천 금지 장소는 보강 역할이 관광이어도 후보에서 빠진다")
    void blockedOverridesEnrichedRole() {
        assertThat(CandidatePolicy.roleOf(cand("야스쿠니 신사 (靖国神社)", "SIGHT", false)))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
        assertThat(CandidatePolicy.roleOf(cand("Yasukuni Shrine", null, false)))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
        assertThat(CandidatePolicy.roleOf(cand("제주러브랜드", "SIGHT", false)))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
        assertThat(CandidatePolicy.roleOf(cand("제주 러브랜드", "SIGHT", false)))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
        assertThat(CandidatePolicy.roleOf(cand("Jeju Loveland", "SIGHT", false)))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
    }

    @Test
    @DisplayName("사용자가 직접 고른 장소와 이름이 비슷한 다른 장소는 막지 않는다")
    void keepsUserSelectedAndUnrelated() {
        assertThat(CandidatePolicy.roleOf(cand("야스쿠니 신사", "SIGHT", true))).isEqualTo(CandidatePolicy.Role.SIGHT);
        assertThat(CandidatePolicy.roleOf(cand("메이지 신궁", "SIGHT", false))).isEqualTo(CandidatePolicy.Role.SIGHT);
        assertThat(CandidatePolicy.roleOf(cand("靖国通り 라멘", "MEAL", false))).isEqualTo(CandidatePolicy.Role.MEAL);
    }
}
