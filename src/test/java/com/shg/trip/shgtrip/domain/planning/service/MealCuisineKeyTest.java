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
        assertThat(PlaceCategoryConstants.mealKind("고서방 짬뽕", null,
                "Dining and Drinking > Restaurant > Seafood Restaurant", List.of(), null, "KOREAN_SET"))
                .isEqualTo("seafood");
    }

    @Test
    @DisplayName("요리 이름은 저장된 음식 종류보다 먼저 본다. 같은 요리가 고기·해산물·국물로 갈리면 반복 상한이 안 걸린다")
    void dishNameBeatsStoredCuisine() {
        String restaurant = "Dining and Drinking > Restaurant";
        assertThat(PlaceCategoryConstants.mealKind("老盛兴汤包馆", "라오성싱 탕바오관", restaurant, List.of("탕", "중식"),
                null, "SOUP")).isEqualTo("dumpling");
        assertThat(PlaceCategoryConstants.mealKind("邵师傅小笼", null, restaurant, List.of(), null, "CHINESE"))
                .isEqualTo("dumpling");
        assertThat(PlaceCategoryConstants.mealKind("Banh Mi Heo Que", "반미 헤 꾸에", "Dining and Drinking > Sandwich Spot",
                List.of(), null, "MEAT")).isEqualTo("banh_mi");
        assertThat(PlaceCategoryConstants.mealKind("Bánh Mì Phượng", null, restaurant, List.of(), null, "WESTERN"))
                .isEqualTo("banh_mi");
        assertThat(PlaceCategoryConstants.mealKind("タコマサ", "타코마사", "Dining and Drinking > Restaurant > Takoyaki Place",
                List.of(), null, "SEAFOOD")).isEqualTo("takoyaki");
        assertThat(PlaceCategoryConstants.mealKind("何か", null, restaurant, List.of("오코노미야키"), null, "JAPANESE"))
                .isEqualTo("okonomiyaki");
    }

    @Test
    @DisplayName("칼국숫집 상호 명동교자·태그 일부·식당 아닌 곳은 요리 종류로 보지 않는다")
    void dishKindDoesNotOvermatch() {
        assertThat(PlaceCategoryConstants.mealKind("명동교자", null, "Dining and Drinking > Restaurant > Korean Restaurant",
                List.of(), null, "NOODLE")).isEqualTo("noodle");
        assertThat(PlaceCategoryConstants.mealKind("칼국수집", null, "Dining and Drinking > Restaurant",
                List.of("왕만두국 맛집"), null, "NOODLE")).isEqualTo("noodle");
        assertThat(PlaceCategoryConstants.mealKind("만두 박물관", null, "Arts and Entertainment > Museum",
                List.of(), null, null)).isEqualTo("museum");
    }
}
