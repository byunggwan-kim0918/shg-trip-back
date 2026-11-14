package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DayTimingLegTest {

    private static PlaceCandidate place(String name, String category, String country, double lat, double lng) {
        return new PlaceCandidate(1, 1L, name, null, category, List.of(), null, country, BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lng), null, null, 0.9);
    }

    @Test
    @DisplayName("해외 관문 연결은 직선 200km를 넘어도 300km까지 이동을 계산하고, 그 밖의 200km 초과 구간은 비운다")
    void gatewayLegUsesWiderLimit() {
        PlaceCandidate noiBai = place("Noi Bai International Airport",
                "Travel and Transportation > Transport Hub > Airport", "VN", 21.2187, 105.8042);
        PlaceCandidate sapa = place("Sa Pa Market", "Retail > Market", "VN", 22.3364, 103.8438);
        PlaceCandidate seoul = place("경복궁", "Landmarks and Outdoors > Palace", "KR", 37.5796, 126.9770);
        PlaceCandidate busan = place("해운대해수욕장", "Landmarks and Outdoors > Beach", "KR", 35.1587, 129.1604);

        TransportLeg gateway = DayTiming.computeLeg(noiBai, sapa, "any", 1);

        assertThat(gateway).isNotNull();
        assertThat(gateway.durationMin()).isGreaterThan(240);
        assertThat(DayTiming.computeLeg(seoul, busan, "car", 1)).isNull();
    }
}
