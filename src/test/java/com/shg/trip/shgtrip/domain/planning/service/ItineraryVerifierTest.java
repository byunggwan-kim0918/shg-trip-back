package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryVerifierTest {

    private static PlaceCandidate restaurant(String name, String cuisine) {
        return new PlaceCandidate(1, 1L, name, null, "Dining and Drinking > Restaurant", List.of(), "Jeonju", "KR",
                BigDecimal.valueOf(35.81), BigDecimal.valueOf(127.15), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, "MEAL", cuisine, false, null, null, null, null);
    }

    @Test
    @DisplayName("요청한 음식은 식당 이름·음식 종류로만 확인한다")
    void servesChecksTheRestaurantItself() {
        assertThat(ItineraryVerifier.serves("피순대", restaurant("한국닭집(전주남부시장)", null))).isFalse();
        assertThat(ItineraryVerifier.serves("피순대", restaurant("조점례남문피순대", null))).isTrue();
        assertThat(ItineraryVerifier.serves("모슬포 고등어회", restaurant("미영이네 고등어회", null))).isTrue();
        assertThat(ItineraryVerifier.serves("회", restaurant("바다횟집", null))).isFalse();
    }

    @Test
    @DisplayName("메뉴 정보가 없는 식당은 그 음식으로 이름난 곳과 이름이 같을 때만 요청 음식으로 본다")
    void namedRestaurantWithoutMenu() {
        assertThat(ItineraryVerifier.serves("두부두루치기", List.of("진로집"), restaurant("진로집 본점", null))).isTrue();
        assertThat(ItineraryVerifier.serves("두부두루치기", List.of("진로집"), restaurant("광천식당", null))).isFalse();
        assertThat(ItineraryVerifier.serves("칼국수", List.of(), restaurant("원조교동장칼국수", null))).isTrue();
    }
}
