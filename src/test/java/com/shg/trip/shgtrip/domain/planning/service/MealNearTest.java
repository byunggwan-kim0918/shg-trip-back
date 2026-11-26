package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MealNearTest {

    private static final Destination SEOUL = DomesticCities.find("서울").orElseThrow();
    private static final Destination BUSAN = DomesticCities.find("부산").orElseThrow();
    private static final double[] SEOUL_STATION = {37.5547, 126.9707};
    private static final double[] HAEUNDAE = {35.1587, 129.1604};
    private static final double[] MYEONGDONG = {37.5636, 126.9850};

    @Test
    @DisplayName("끼니 칸이 앞 장소와 다른 여행 도시의 구역을 적었으면 그 구역에서 식당을 찾는다")
    void mealInNamedAreaOfOtherCity() {
        assertThat(DraftItineraryPlanner.mealNear(List.of(SEOUL, BUSAN), HAEUNDAE, SEOUL_STATION)).isSameAs(HAEUNDAE);
    }

    @Test
    @DisplayName("같은 도시의 구역이거나 구역을 모르면 앞 장소 근처에서 찾는다")
    void sameCityKeepsPreviousPlace() {
        assertThat(DraftItineraryPlanner.mealNear(List.of(SEOUL, BUSAN), MYEONGDONG, SEOUL_STATION))
                .isSameAs(SEOUL_STATION);
        assertThat(DraftItineraryPlanner.mealNear(List.of(SEOUL, BUSAN), null, SEOUL_STATION)).isSameAs(SEOUL_STATION);
    }
}
