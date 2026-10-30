package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 음식 종류는 출처(리프·이름·보강)가 달라도 같은 키로 세여야 유형 상한이 걸린다. */
class MealCuisineKeyTest {

    @Test
    @DisplayName("BBQ 리프·이름의 흑돼지·보강 MEAT가 모두 같은 종류")
    void grillSourcesShareOneKey() {
        String byKoreanBbqLeaf = PlaceCategoryConstants.subType("제주삼춘 함덕해수욕장 흑돼지",
                "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant > Korean BBQ Restaurant",
                List.of(), null);
        String byBbqJointLeaf = PlaceCategoryConstants.subType("김녕제주흑돼지식당",
                "Dining and Drinking > Restaurant > BBQ Joint", List.of(), null);
        String byName = PlaceCategoryConstants.subType("흑돈가 제주",
                "Dining and Drinking > Restaurant", List.of("흑돈", "돼지고기"), null);
        String byBeefName = PlaceCategoryConstants.subType("한우명가",
                "Dining and Drinking > Restaurant", List.of(), null);

        assertThat(List.of(byKoreanBbqLeaf, byBbqJointLeaf, byName, byBeefName)).containsOnly("meat");
        assertThat(PlaceCategoryConstants.cuisineKey("MEAT")).isEqualTo("meat");
    }

    @Test
    @DisplayName("구체적 종류가 다르면 다른 키 — 해산물·면은 고기와 따로 센다")
    void differentCuisinesStaySeparate() {
        assertThat(PlaceCategoryConstants.subType("고서방 짬뽕",
                "Dining and Drinking > Restaurant > Seafood Restaurant", List.of(), null)).isEqualTo("seafood");
        assertThat(PlaceCategoryConstants.subType("一蘭",
                "Dining and Drinking > Restaurant > Asian Restaurant > Japanese Restaurant > Ramen Restaurant",
                List.of(), null)).isEqualTo("noodle");
        // 나라 단위·기타는 구체 종류가 아니다 — 해물탕집과 국숫집을 "한식" 하나로 묶어 상한을 걸면 안 된다
        assertThat(PlaceCategoryConstants.cuisineKey("KOREAN_SET")).isNull();
        assertThat(PlaceCategoryConstants.cuisineKey("OTHER")).isNull();
        assertThat(PlaceCategoryConstants.mealKind("고서방 짬뽕",
                "Dining and Drinking > Restaurant > Seafood Restaurant", List.of(), null, "KOREAN_SET"))
                .isEqualTo("seafood");
    }
}
