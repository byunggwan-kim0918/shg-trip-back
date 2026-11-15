package com.shg.trip.shgtrip.domain.place.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceAttributesTest {

    @Test
    @DisplayName("주재료는 JSON에서 읽을 때 허용 목록 밖 값을 버리고, 빈 목록은 물어본 결과로 남긴다")
    void ingredientsFromJson() {
        assertThat(PlaceAttributes.fromJson("{\"venue\":\"MEAL\",\"ingredients\":[\"beef\",\"FISH\",\"BEEF\"]}")
                .ingredients()).containsExactly("BEEF");
        assertThat(PlaceAttributes.fromJson("{\"venue\":\"MEAL\",\"ingredients\":[]}").ingredients()).isEmpty();
        assertThat(PlaceAttributes.fromJson("{\"venue\":\"MEAL\"}").ingredients()).isNull();
    }

    @Test
    @DisplayName("주재료 목록은 고기 종류와 해산물이고, 빈 목록은 재료를 아는 것으로 보지 않는다")
    void ingredientKinds() {
        assertThat(PlaceAttributes.INGREDIENTS).containsAll(PlaceAttributes.MEAT_INGREDIENTS).contains("SEAFOOD")
                .hasSize(PlaceAttributes.MEAT_INGREDIENTS.size() + 1);
        PlaceAttributes beef = new PlaceAttributes(null, null, null, null, null, null, null, List.of("BEEF"));
        PlaceAttributes none = new PlaceAttributes(null, null, null, null, null, null, null, List.of());

        assertThat(beef.hasIngredient("BEEF")).isTrue();
        assertThat(beef.knowsIngredients()).isTrue();
        assertThat(none.knowsIngredients()).isFalse();
        assertThat(new PlaceAttributes("MEAL", null, null, null, null, null, null).hasIngredient("BEEF")).isFalse();
    }

    @Test
    @DisplayName("원문을 바꿔도 주재료는 남고, 속성 보강이 쓰는 분류 JSON에는 원문·주재료가 없다")
    void ingredientsSurviveOfficialAndStayOutOfAttributeWrites() {
        PlaceAttributes withIngredients = new PlaceAttributes("MEAL", null, null, null, null, null,
                Map.of("firstmenu", "설렁탕"), List.of("BEEF"));

        assertThat(withIngredients.withOfficial(null).ingredients()).containsExactly("BEEF");
        assertThat(withIngredients.classificationJson()).isEqualTo("{\"venue\":\"MEAL\"}");
    }
}
