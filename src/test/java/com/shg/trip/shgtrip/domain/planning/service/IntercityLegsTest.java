package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntercityLegsTest {

    private static final TripCities.City TOKYO = new TripCities.City("Tokyo", "도쿄", "JP", 35.6812, 139.7671);
    private static final TripCities.City OSAKA = new TripCities.City("Osaka", "오사카", "JP", 34.6937, 135.5023);

    @Test
    @DisplayName("도쿄→오사카 열차는 3시간 남짓, 1인 요금 10만~13만 원이다")
    void tokyoOsakaTrain() {
        IntercityLegs.IntercityLeg leg = IntercityLegs.legBetween(TOKYO, OSAKA, "any");

        assertThat(leg.minutes()).isBetween(180, 210);
        assertThat(leg.costWon().intValue()).isBetween(100_000, 130_000);
    }

    @Test
    @DisplayName("국내는 차로 다니면 자동차, 제주를 끼면 비행기, 그 밖에는 KTX로 옮기고 요금은 원화다")
    void domesticModes() {
        TripCities.City seoul = new TripCities.City("Seoul", "서울", "KR", 37.5409, 126.9975);
        TripCities.City busan = new TripCities.City("Busan", "부산", "KR", 35.1589, 129.0796);
        TripCities.City jeju = new TripCities.City("Jeju", "제주", "KR", 33.4581, 126.5155);

        IntercityLegs.IntercityLeg ktx = IntercityLegs.legBetween(seoul, busan, "any");
        IntercityLegs.IntercityLeg drive = IntercityLegs.legBetween(seoul, busan, "car");
        IntercityLegs.IntercityLeg flight = IntercityLegs.legBetween(busan, jeju, "car");

        assertThat(ktx.mode()).isEqualTo("TRAIN");
        assertThat(ktx.minutes()).isBetween(180, 230);
        assertThat(ktx.costWon().intValue()).isBetween(50_000, 65_000);
        assertThat(drive.mode()).isEqualTo("DRIVE");
        assertThat(drive.minutes()).isBetween(270, 330);
        assertThat(flight.mode()).isEqualTo("FLIGHT");
        assertThat(flight.minutes()).isBetween(150, 190);
        // 비행기 거리는 직선, 길로 가는 이동은 도로 계수를 곱한 거리다.
        assertThat(flight.distanceKm().doubleValue()).isBetween(295.0, 310.0);
        assertThat(ktx.distanceKm().doubleValue()).isBetween(415.0, 430.0);
        assertThat(IntercityLegs.legBetween(TOKYO, OSAKA, "car")).isEqualTo(IntercityLegs.legBetween(TOKYO, OSAKA, "any"));
    }
}
