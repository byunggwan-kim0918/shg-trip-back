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
                .isEqualTo("chinese_noodle");
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
    @DisplayName("한식·중식으로 보강된 식당도 이름의 요리로 센다. 종류를 모르면 순두부집 네 끼가 반복으로 잡히지 않는다")
    void koreanDishFamilies() {
        String restaurant = "Dining and Drinking > Restaurant";
        assertThat(kind("토속초당순두부", "KOREAN_SET")).isEqualTo("tofu");
        assertThat(kind("짬뽕 순두부 초당애", "KOREAN_SET")).isEqualTo("tofu");
        assertThat(kind("짬뽕지존", "CHINESE")).isEqualTo("chinese_noodle");
        assertThat(kind("합천돼지국밥 해운대본점", "MEAT")).isEqualTo("gukbap");
        assertThat(kind("가온밀면", "NOODLE")).isEqualTo("cold_noodle");
        assertThat(kind("청해 조개구이 장어구이", "SEAFOOD")).isEqualTo("shellfish");
        assertThat(kind("자갈치 꼼장어", "SEAFOOD")).isEqualTo("eel");
        assertThat(kind("앞산울진대게 본점", "SEAFOOD")).isEqualTo("crab");
        assertThat(kind("주문진 생선회센터", "SEAFOOD")).isEqualTo("raw_fish");
        assertThat(kind("영덕물회", "SEAFOOD")).isEqualTo("raw_fish");
        assertThat(kind("실비생선구이 본점", "SEAFOOD")).isEqualTo("grilled_fish");
        assertThat(PlaceCategoryConstants.mealKind("타워 만두", null, restaurant, List.of(), null, "SNACK"))
                .isEqualTo("dumpling");
    }

    @Test
    @DisplayName("요리 키워드가 단어 경계를 넘어 걸리는 이름은 그 요리로 보지 않는다")
    void dishWordsDoNotCrossWordBoundaries() {
        assertThat(kind("보물회관", "KOREAN_SET")).isNotEqualTo("raw_fish");
        assertThat(kind("시장어묵", "SNACK")).isNotEqualTo("eel");
        assertThat(kind("가게장터 백반", "KOREAN_SET")).isNotEqualTo("crab");
        // 제외어는 이름에서 그 조각만 지운다. 이름의 다른 곳에 요리가 있거나 태그가 요리면 그대로 잡는다.
        assertThat(kind("속초 물회 회관", "SEAFOOD")).isEqualTo("raw_fish");
        assertThat(PlaceCategoryConstants.mealKind("부산어묵", null, "Dining and Drinking > Restaurant", List.of("장어"),
                null, "SNACK")).isEqualTo("eel");
    }

    @Test
    @DisplayName("요리를 모르는 묶음 키는 같은 묶음의 요리와 같은 끼니로 보고, 요리가 다르면 다른 끼니다")
    void sameMealKindTreatsUnknownDishAsItsGroup() {
        assertThat(PlaceCategoryConstants.sameMealKind("raw_fish", "seafood")).isTrue();
        assertThat(PlaceCategoryConstants.sameMealKind("seafood", "shellfish")).isTrue();
        assertThat(PlaceCategoryConstants.sameMealKind("raw_fish", "shellfish")).isFalse();
        assertThat(PlaceCategoryConstants.sameMealKind("tofu", "seafood")).isFalse();
        assertThat(PlaceCategoryConstants.sameMealKind("meat", "meat")).isTrue();
        assertThat(PlaceCategoryConstants.sameMealKind(null, "meat")).isFalse();
    }

    @Test
    @DisplayName("요리 종류는 상위 묶음으로 올라가고, 묶음이 없는 종류는 그 자체가 묶음이다")
    void mealGroups() {
        assertThat(List.of("shellfish", "raw_fish", "eel", "crab", "grilled_fish"))
                .allMatch(k -> "seafood".equals(PlaceCategoryConstants.mealGroup(k)));
        assertThat(PlaceCategoryConstants.mealGroup("chinese_noodle")).isEqualTo("noodle");
        assertThat(PlaceCategoryConstants.mealGroup("tofu")).isEqualTo("soup");
        assertThat(PlaceCategoryConstants.mealGroup("meat")).isEqualTo("meat");
        assertThat(PlaceCategoryConstants.mealGroup(null)).isNull();
    }

    private static String kind(String name, String cuisine) {
        return PlaceCategoryConstants.mealKind(name, null, "Dining and Drinking > Restaurant", List.of(), null, cuisine);
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
