package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WantMatcherTest {

    private static PlaceCandidate restaurant(String name, String cuisine) {
        return new PlaceCandidate(1, 1L, name, null, "Dining and Drinking > Restaurant", List.of(), "Jeonju", "KR",
                BigDecimal.valueOf(35.81), BigDecimal.valueOf(127.15), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, "MEAL", cuisine, false, null, null, null, null);
    }

    @Test
    @DisplayName("요청한 음식은 식당 이름·음식 종류로만 확인한다")
    void servesChecksTheRestaurantItself() {
        assertThat(WantMatcher.serves("피순대", restaurant("한국닭집(전주남부시장)", null))).isFalse();
        assertThat(WantMatcher.serves("피순대", restaurant("조점례남문피순대", null))).isTrue();
        assertThat(WantMatcher.serves("모슬포 고등어회", restaurant("미영이네 고등어회", null))).isTrue();
        assertThat(WantMatcher.serves("회", restaurant("바다횟집", null))).isFalse();
    }

    @Test
    @DisplayName("메뉴 정보가 없는 식당은 그 음식으로 이름난 곳과 이름이 같을 때만 요청 음식으로 본다")
    void namedRestaurantWithoutMenu() {
        assertThat(WantMatcher.serves("두부두루치기", List.of("진로집"), restaurant("진로집 본점", null))).isTrue();
        assertThat(WantMatcher.serves("두부두루치기", List.of("진로집"), restaurant("광천식당", null))).isFalse();
        assertThat(WantMatcher.serves("칼국수", List.of(), restaurant("원조교동장칼국수", null))).isTrue();
    }

    private static PlaceCandidate sight(String name, String nameKo) {
        return new PlaceCandidate(1, 2L, name, null, "Landmarks and Outdoors > Beach", List.of(), "Ishigaki", "JP",
                BigDecimal.valueOf(24.45), BigDecimal.valueOf(124.19), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, "SIGHT", null, false, null, null, nameKo, null);
    }

    @Test
    @DisplayName("요구의 현지어 이름은 원래 이름과 정확히 같을 때만 맞히고 이름을 딴 근처 가게는 맞히지 않는다")
    void localNameMatchesOriginalNameExactly() {
        assertThat(WantMatcher.matches(local("요네하라 해변", "米原海岸"), sight("米原海岸", "마이바라 해안"))).isTrue();
        assertThat(WantMatcher.matches(local("요네하라 해변", "米原海岸"), sight("東茶屋街", null))).isFalse();
        assertThat(WantMatcher.matches(local("히가시차야가이", "東茶屋街"), sight("東茶屋街", null))).isTrue();
        assertThat(WantMatcher.matches(local("기요미즈데라", "清水寺"), sight("清水寺前 湯豆腐", null))).isFalse();
        assertThat(WantMatcher.matches(local("기요미즈데라", "清水寺"), sight("清水寺（音羽山）", null))).isTrue();
    }

    private static TripConditions.Want local(String text, String localName) {
        return new TripConditions.Want(text, null, List.of(), null, TripConditions.Reach.PLACEABLE, false, false,
                List.of(localName));
    }
}
