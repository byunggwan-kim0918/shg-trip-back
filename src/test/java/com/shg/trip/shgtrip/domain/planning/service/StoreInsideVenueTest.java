package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoreInsideVenueTest {

    private static PlaceCandidate cand(int index, String name, String category, double lat, double lng) {
        return new PlaceCandidate(index, (long) index, name, "주소", category, List.of(), "Seoul", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.9, null, null, null, null,
                false, null, null, null, null, null, null, false, null);
    }

    private static List<String> names(List<PlaceCandidate> cs) {
        return cs.stream().map(PlaceCandidate::name).toList();
    }

    @Test
    @DisplayName("백화점 이름으로 시작하는 근처 매장은 빼고 백화점은 남긴다")
    void dropsStoreInsideDepartmentStore() {
        List<PlaceCandidate> result = OptimizedGenerationExecutor.dropStoresInsideVenue(List.of(
                cand(1, "롯데백화점 본점", "Retail > Department Store", 37.56468, 126.98170),
                cand(2, "롯데백화점 본점 시스템옴므", "Retail > Department Store", 37.56561, 126.98206)));
        assertThat(names(result)).containsExactly("롯데백화점 본점");
    }

    @Test
    @DisplayName("백화점 식당가·멀리 떨어진 같은 이름 지점·관광지는 건드리지 않는다")
    void keepsDiningFarAndNonRetail() {
        List<PlaceCandidate> result = OptimizedGenerationExecutor.dropStoresInsideVenue(List.of(
                cand(1, "롯데백화점 본점", "Retail > Department Store", 37.56468, 126.98170),
                cand(2, "롯데백화점 본점 푸드코트", "Dining and Drinking > Food Court", 37.56470, 126.98172),
                cand(3, "롯데백화점 본점 잠실", "Retail > Department Store", 37.51100, 127.09800),
                cand(4, "해운대", "Landmarks and Outdoors > Beach", 35.1587, 129.1604),
                cand(5, "해운대 블루라인파크", "Landmarks and Outdoors > Tourist Attraction", 35.1590, 129.1610)));
        assertThat(result).hasSize(5);
    }
}
