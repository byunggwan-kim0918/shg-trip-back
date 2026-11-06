package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeSubTypeKeyTest {

    private static PlaceAttributes sub(String subType) {
        return new PlaceAttributes(null, subType, null, null, null, null, null);
    }

    @Test
    @DisplayName("속성 유형을 기존 유형 키로 옮긴다 — 산·오름은 같은 hill, 정원은 park, 미술관은 museum")
    void mapsToExistingKeys() {
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("mountain"))).isEqualTo("hill");
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("hill"))).isEqualTo("hill");
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("garden"))).isEqualTo("park");
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("gallery"))).isEqualTo("museum");
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("beach"))).isEqualTo("beach");
        assertThat(PlaceCategoryConstants.isDaytimeSubType(
                PlaceCategoryConstants.attributeSubTypeKey(sub("mountain")))).isTrue();
    }

    @Test
    @DisplayName("속성이 없거나 판정에 못 쓰는 값이면 null(호출부가 카테고리·이름 추론으로 돌아간다)")
    void unusableIsNull() {
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(null)).isNull();
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub(null))).isNull();
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("landmark"))).isNull();
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("other"))).isNull();
    }

    @Test
    @DisplayName("리프만 보면 갈리는 같은 종류가 속성으로는 하나로 묶인다(Mountain 리프 오름 vs 이름 추론 오름)")
    void unifiesLeafSplit() {
        String byLeaf = PlaceCategoryConstants.subType("솔오름", "Landmarks and Outdoors > Mountain", List.of(), null);
        String byName = PlaceCategoryConstants.subType("삼의악오름",
                "Landmarks and Outdoors > Tourist Attraction", List.of(), null);

        assertThat(byLeaf).isNotEqualTo(byName);
        assertThat(PlaceCategoryConstants.attributeSubTypeKey(sub("hill")))
                .isEqualTo(PlaceCategoryConstants.attributeSubTypeKey(sub("mountain")));
    }
}
