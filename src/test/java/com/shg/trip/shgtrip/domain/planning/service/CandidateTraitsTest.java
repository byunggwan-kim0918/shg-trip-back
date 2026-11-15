package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateTraitsTest {

    @Test
    @DisplayName("약한 분류는 코드가 고르지 않지만 요청 의도 장소면 고른다. 빈자리 보충 기준은 그대로다")
    void requestedWeakFillIsSelectableButNotAFill() {
        PlaceCandidate sculpture = candidate("광장 조형물", "Landmarks and Outdoors > Structure");
        PlaceCandidate requested = sculpture.withIntent(true, null);

        assertThat(CandidateTraits.isSelectable(sculpture)).isFalse();
        assertThat(CandidateTraits.isSelectable(requested)).isTrue();
        assertThat(CandidateTraits.isVisitableFill(requested)).isFalse();
    }

    @Test
    @DisplayName("경유형은 코드가 고르지 않지만 요청 의도 장소면 고른다")
    void requestedViaRouteIsSelectable() {
        PlaceCandidate coastalRoad = candidate("해운대 해안도로", "Landmarks and Outdoors > Tourist Attraction");

        assertThat(CandidateTraits.isSelectable(coastalRoad)).isFalse();
        assertThat(CandidateTraits.isSelectable(coastalRoad.withIntent(true, null))).isTrue();
        assertThat(CandidateTraits.isVisitableFill(coastalRoad.withIntent(true, null))).isFalse();
    }

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

    @Test
    @DisplayName("집합 POI는 요청 의도 장소여도 고르지 않는다")
    void requestedAggregatePoiIsNotSelectable() {
        PlaceCandidate street = candidate("해리단길 먹자골목", "Dining and Drinking > Food Court")
                .withIntent(true, null);

        assertThat(CandidateTraits.isSelectable(street)).isFalse();
    }

    private static PlaceCandidate candidate(String name, String category) {
        return new PlaceCandidate(1, 1L, name, "부산광역시 해운대구 우동 1", category, List.of(), "Busan", "KR",
                BigDecimal.valueOf(35.16), BigDecimal.valueOf(129.16), null, null, 0.8, null, null, null, 60, false,
                null, null, null, null, "SIGHT", null, false, null);
    }
}
