package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripCityDetectorTest {

    private final Destinations destinations = Destinations.fromClasspath();

    private static TransportationHub hub(String arrival, String departure) {
        return new TransportationHub(arrival, departure, "AIRPORT");
    }

    @Test
    @DisplayName("근교 여행지는 상위 도시에 묶여 도시가 하나면 null이다")
    void nearbyDestinationsStaySingleCity() {
        assertThat(TripCityDetector.detect(List.of("Osaka", "Kyoto", "Nara"), null, 4, destinations, null)).isNull();
    }

    @Test
    @DisplayName("배분이 없으면 도착 관문 도시를 처음, 출발 관문 도시를 마지막에 두고 날을 고르게 나눈다")
    void hubDecidesOrder() {
        TripCities cities = TripCityDetector.detect(List.of("Osaka", "Tokyo"), null, 5, destinations,
                hub("Narita International Airport", "Kansai International Airport"));

        assertThat(cities.cities()).extracting(TripCities.City::name).containsExactly("Tokyo", "Osaka");
        assertThat(cities.dayCity()).containsExactlyInAnyOrderEntriesOf(
                Map.of(1, "Tokyo", 2, "Tokyo", 3, "Tokyo", 4, "Osaka", 5, "Osaka"));
    }

    @Test
    @DisplayName("일차별 배분이 도시마다 이어져 있으면 그 배분과 순서를 따른다")
    void contiguousAllocationWins() {
        Map<String, List<String>> allocation = new LinkedHashMap<>();
        allocation.put("1-2", List.of("Osaka", "Kyoto"));
        allocation.put("3-5", List.of("Tokyo"));

        TripCities cities = TripCityDetector.detect(List.of("Tokyo", "Osaka", "Kyoto"), allocation, 5, destinations,
                hub("Narita International Airport", "Narita International Airport"));

        assertThat(cities.cities()).extracting(TripCities.City::name).containsExactly("Osaka", "Tokyo");
        assertThat(cities.dayCity().get(2)).isEqualTo("Osaka");
        assertThat(cities.dayCity().get(3)).isEqualTo("Tokyo");
    }

    @Test
    @DisplayName("여행 일수를 벗어난 배분은 버리고 고르게 나눈다")
    void allocationOutOfRangeIsIgnored() {
        Map<String, List<String>> allocation = new LinkedHashMap<>();
        allocation.put("1-2", List.of("Tokyo"));
        allocation.put("3-5", List.of("Osaka"));

        TripCities cities = TripCityDetector.detect(List.of("Tokyo", "Osaka"), allocation, 4, destinations, null);

        assertThat(cities.dayCity()).containsOnlyKeys(1, 2, 3, 4);
    }

    @Test
    @DisplayName("따로 두지 않은 인접 여행지(나가사키)는 가장 가까운 도시(후쿠오카) 배분으로 본다")
    void nearbyRootJoinsNearestCity() {
        Map<String, List<String>> allocation = new LinkedHashMap<>();
        allocation.put("1-2", List.of("Tokyo"));
        allocation.put("3-5", List.of("Fukuoka", "Nagasaki"));

        TripCities cities = TripCityDetector.detect(List.of("Tokyo", "Fukuoka", "Nagasaki"), allocation, 5,
                destinations, null);

        assertThat(cities.cities()).extracting(TripCities.City::name).containsExactly("Tokyo", "Fukuoka");
        assertThat(cities.dayCity().get(3)).isEqualTo("Fukuoka");
        assertThat(cities.dayCity().get(5)).isEqualTo("Fukuoka");
    }

    @Test
    @DisplayName("도시가 날보다 많으면 도시별로 나눌 수 없어 null이다")
    void moreCitiesThanDays() {
        assertThat(TripCityDetector.detect(List.of("Tokyo", "Osaka"), null, 1, destinations, null)).isNull();
    }
}
