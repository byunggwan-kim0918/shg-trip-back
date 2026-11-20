package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ItineraryVerifierTest {

    private static PlaceCandidate restaurant(String name, String cuisine) {
        return new PlaceCandidate(1, 1L, name, null, "Dining and Drinking > Restaurant", List.of(), "Jeonju", "KR",
                BigDecimal.valueOf(35.81), BigDecimal.valueOf(127.15), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, "MEAL", cuisine, false, null, null, null, null);
    }

    @Test
    @DisplayName("요청한 음식은 식당 이름·음식 종류로만 확인한다")
    void servesChecksTheRestaurantItself() {
        assertThat(ItineraryVerifier.serves("피순대", restaurant("한국닭집(전주남부시장)", null))).isFalse();
        assertThat(ItineraryVerifier.serves("피순대", restaurant("조점례남문피순대", null))).isTrue();
        assertThat(ItineraryVerifier.serves("모슬포 고등어회", restaurant("미영이네 고등어회", null))).isTrue();
        assertThat(ItineraryVerifier.serves("회", restaurant("바다횟집", null))).isFalse();
    }

    @Test
    @DisplayName("메뉴 정보가 없는 식당은 그 음식으로 이름난 곳과 이름이 같을 때만 요청 음식으로 본다")
    void namedRestaurantWithoutMenu() {
        assertThat(ItineraryVerifier.serves("두부두루치기", List.of("진로집"), restaurant("진로집 본점", null))).isTrue();
        assertThat(ItineraryVerifier.serves("두부두루치기", List.of("진로집"), restaurant("광천식당", null))).isFalse();
        assertThat(ItineraryVerifier.serves("칼국수", List.of(), restaurant("원조교동장칼국수", null))).isTrue();
    }

    @Test
    @DisplayName("공항으로 떠나는 마지막 날은 공항 수속 시간을 빼고 관광할 시간이 없으면 빈 날로 보지 않는다")
    void airportDepartureDayWithoutSightseeing() {
        PlaceCandidate airport = new PlaceCandidate(2, 2L, "제주국제공항", null,
                "Travel and Transportation > Transport Hub > Airport", List.of(), "Jeju", "KR",
                BigDecimal.valueOf(33.51), BigDecimal.valueOf(126.49), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, "TRANSIT", null, false, null, null, null, null);
        StepData toAirport = new StepData(1, 2, "10:30", "11:00", new PlaceData("제주국제공항", null, null, "Jeju", "KR",
                null, null, 2L, null), List.of(), null, null, null, null, null, BigDecimal.ZERO);
        TripConditions conditions = new TripConditions(List.of(), Map.of(), List.of(), false, "car", null,
                LocalTime.of(12, 30), "normal");

        List<ItineraryVerifier.Violation> violations = new ItineraryVerifier(mock(PlaceFactsReader.class))
                .verify(conditions, LocalDate.of(2027, 5, 1), 2, List.of(toAirport), List.of(airport),
                        List.of(DomesticCities.find("Jeju").orElseThrow()));

        assertThat(violations).noneMatch(v -> v.day() == 2 && "EMPTY_DAY".equals(v.code()));
    }
}
