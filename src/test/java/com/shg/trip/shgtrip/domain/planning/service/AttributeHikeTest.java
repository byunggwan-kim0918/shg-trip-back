package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeHikeTest {

    private static PlaceAttributes hike(String level) {
        return new PlaceAttributes(null, null, null, level, null, null, null);
    }

    @Test
    @DisplayName("태그 '등산'만 있는 성산일출봉은 속성 EASY면 등산으로 보지 않는다")
    void easyOverridesKeyword() {
        List<String> tags = List.of("일출명소", "등산");

        assertThat(PlaceCategoryConstants.isHikingPlace("성산일출봉", tags)).isTrue();
        assertThat(PlaceCategoryConstants.isHikingPlace(hike("EASY"), "성산일출봉", tags)).isFalse();
    }

    @Test
    @DisplayName("키워드가 없는 관악산도 속성 LONG이면 등산")
    void longWithoutKeyword() {
        assertThat(PlaceCategoryConstants.isHikingPlace("관악산", List.of())).isFalse();
        assertThat(PlaceCategoryConstants.isHikingPlace(hike("LONG"), "관악산", List.of())).isTrue();
    }

    @Test
    @DisplayName("속성이 없거나 등산 강도가 비면 키워드 판정 그대로")
    void fallsBackToKeyword() {
        assertThat(PlaceCategoryConstants.isHikingPlace(null, "한라산 등반", List.of())).isTrue();
        assertThat(PlaceCategoryConstants.isHikingPlace(hike(null), "한라산 등반", List.of())).isTrue();
    }
}
