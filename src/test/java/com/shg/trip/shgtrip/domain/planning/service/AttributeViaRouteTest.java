package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeViaRouteTest {

    private static PlaceAttributes style(String visitStyle) {
        return new PlaceAttributes(null, null, visitStyle, null, null, null, null);
    }

    private static final String SIGHT = "Landmarks and Outdoors > Tourist Attraction";

    @Test
    @DisplayName("태그 '산책로'·'드라이브코스'로 경유형이 되던 명소를 속성(STOP)이 되돌린다")
    void stopOverridesKeyword() {
        List<String> tags = List.of("해안절경", "산책로", "드라이브코스");

        assertThat(PlaceCategoryConstants.isViaRoute("송악산", SIGHT, tags)).isTrue();
        assertThat(PlaceCategoryConstants.isViaRoute(style("STOP"), "송악산", SIGHT, tags)).isFalse();
    }

    @Test
    @DisplayName("키워드가 없는 다리도 속성(PASS_THROUGH)이면 경유형")
    void passThroughWithoutKeyword() {
        assertThat(PlaceCategoryConstants.isViaRoute("마포대교", SIGHT, List.of())).isFalse();
        assertThat(PlaceCategoryConstants.isViaRoute(style("PASS_THROUGH"), "마포대교", SIGHT, List.of())).isTrue();
    }

    @Test
    @DisplayName("속성이 없거나 경유형 값이 비면 키워드 판정 그대로")
    void fallsBackToKeyword() {
        assertThat(PlaceCategoryConstants.isViaRoute(null, "애월 해안도로", SIGHT, List.of())).isTrue();
        assertThat(PlaceCategoryConstants.isViaRoute(style(null), "애월 해안도로", SIGHT, List.of())).isTrue();
    }

    @Test
    @DisplayName("공항·역은 속성이 PASS_THROUGH여도 경유형으로 보지 않는다(키워드 판정 유지)")
    void hubsIgnoreAttribute() {
        assertThat(PlaceCategoryConstants.isViaRoute(style("PASS_THROUGH"), "서울역",
                "Travel and Transportation > Transport Hub > Metro Station", List.of())).isFalse();
    }
}
