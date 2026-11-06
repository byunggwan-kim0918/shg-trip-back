package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeLodgingTest {

    private static final String LODGING = "Travel and Transportation > Lodging";

    private static PlaceAttributes lodging(String type) {
        return new PlaceAttributes(null, null, null, null, type, null, null);
    }

    @Test
    @DisplayName("이름에 키워드가 없어도 속성이 게스트하우스·호스텔이면 다인실")
    void dormByAttribute() {
        assertThat(PlaceCategoryConstants.isDormLodging("서툰가족", LODGING, List.of())).isFalse();
        assertThat(PlaceCategoryConstants.isDormLodging(lodging("GUESTHOUSE"), "서툰가족", LODGING, List.of())).isTrue();
        assertThat(PlaceCategoryConstants.isDormLodging(lodging("HOSTEL"), "어딘가", LODGING, List.of())).isTrue();
    }

    @Test
    @DisplayName("태그에 '게스트하우스'가 붙은 호텔도 속성이 호텔이면 다인실이 아니다")
    void hotelByAttribute() {
        List<String> tags = List.of("게스트하우스");

        assertThat(PlaceCategoryConstants.isDormLodging("제주R호텔", LODGING, tags)).isTrue();
        assertThat(PlaceCategoryConstants.isDormLodging(lodging("HOTEL"), "제주R호텔", LODGING, tags)).isFalse();
    }

    @Test
    @DisplayName("속성이 없거나 숙소 유형이 비면 키워드 판정 그대로")
    void fallsBackToKeyword() {
        assertThat(PlaceCategoryConstants.isDormLodging(null, "소낭게스트하우스", LODGING, List.of())).isTrue();
        assertThat(PlaceCategoryConstants.isDormLodging(lodging(null), "소낭게스트하우스", LODGING, List.of())).isTrue();
    }
}
