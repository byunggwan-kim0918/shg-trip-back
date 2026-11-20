package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateTraitsTest {

    @Test
    @DisplayName("선택 단계가 고른 장소는 경유형이어도 놓지만 지정 구역과 집합 POI는 놓지 않는다")
    void placesPickedBySelection() {
        PlaceCandidate coastalRoad = candidate("해운대 해안도로", "Landmarks and Outdoors > Tourist Attraction");
        PlaceCandidate zone = candidate("제주도 관광특구", "Landmarks and Outdoors > Tourist Attraction");
        PlaceCandidate street = candidate("해리단길 먹자골목", "Dining and Drinking > Food Court");

        assertThat(CandidateTraits.isSelectable(coastalRoad, true)).isTrue();
        assertThat(CandidateTraits.isSelectable(zone, true)).isFalse();
        assertThat(CandidateTraits.isSelectable(street, true)).isFalse();
    }

    private static PlaceCandidate candidate(String name, String category) {
        return new PlaceCandidate(1, 1L, name, "부산광역시 해운대구 우동 1", category, List.of(), "Busan", "KR",
                BigDecimal.valueOf(35.16), BigDecimal.valueOf(129.16), null, null, 0.8, null, null, null, 60, false,
                null, null, null, null, "SIGHT", null, false, null);
    }
}
