package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackBlockedPlaceTest {

    private static StepData step(int order, String name, List<AlternativeData> alternatives) {
        return new StepData(order, 1, "10:00", "11:00", new PlaceData(name, "주소", "관광", "제주", "KR"),
                alternatives, "CAR", 10, BigDecimal.ONE, BigDecimal.ZERO, "설명", BigDecimal.ZERO);
    }

    private static AlternativeData alt(String name) {
        return new AlternativeData(name, "주소", "관광", "제주", "KR", "설명", BigDecimal.ZERO);
    }

    private static ItineraryData itinerary(StepData... steps) {
        return new ItineraryData("제주", "제주", BigDecimal.ZERO, List.of(), List.of(steps));
    }

    @Test
    @DisplayName("fallback 일정의 추천 금지 장소는 스텝과 대안에서 모두 뺀다")
    void dropsBlockedStepsAndAlternatives() {
        ItineraryData data = itinerary(
                step(0, "성산일출봉", List.of(alt("제주러브랜드"), alt("섭지코지"))),
                step(1, "제주러브랜드", List.of()));

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of());

        assertThat(result.steps()).extracting(s -> s.place().name()).containsExactly("성산일출봉");
        assertThat(result.steps().get(0).alternatives()).extracting(AlternativeData::name).containsExactly("섭지코지");
    }

    @Test
    @DisplayName("사용자가 직접 고른 장소는 목록에 있어도 남긴다")
    void keepsUserSelected() {
        Place chosen = Place.builder().name("제주러브랜드").build();
        ItineraryData data = itinerary(step(0, "제주러브랜드", List.of()));

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of(chosen));

        assertThat(result.steps()).hasSize(1);
    }

    @Test
    @DisplayName("뺀 장소 다음 스텝의 이동 구간은 비우고, 다른 스텝의 이동 구간은 그대로 둔다")
    void clearsLegFromDroppedPlace() {
        ItineraryData data = itinerary(
                step(0, "성산일출봉", List.of()),
                step(1, "제주 러브랜드", List.of()),
                step(2, "섭지코지", List.of()),
                step(3, "우도", List.of()));

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of());

        assertThat(result.steps()).extracting(s -> s.place().name()).containsExactly("성산일출봉", "섭지코지", "우도");
        assertThat(result.steps().get(1).transportationMode()).isNull();
        assertThat(result.steps().get(1).transportationCost()).isNull();
        assertThat(result.steps().get(2).transportationMode()).isEqualTo("CAR");
    }
}
