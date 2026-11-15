package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
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

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of(), AvoidedPlaces.NONE,
                PlaceRestrictions.NONE);

        assertThat(result.steps()).extracting(s -> s.place().name()).containsExactly("성산일출봉");
        assertThat(result.steps().get(0).alternatives()).extracting(AlternativeData::name).containsExactly("섭지코지");
    }

    @Test
    @DisplayName("사용자가 직접 고른 장소는 목록에 있어도 남긴다")
    void keepsUserSelected() {
        Place chosen = Place.builder().name("제주러브랜드").build();
        ItineraryData data = itinerary(step(0, "제주러브랜드", List.of()));

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of(chosen), AvoidedPlaces.NONE,
                PlaceRestrictions.NONE);

        assertThat(result.steps()).hasSize(1);
    }

    @Test
    @DisplayName("피할 장소는 띄어쓰기·앞의 도시 이름이 달라도 스텝과 대안에서 뺀다")
    void dropsAvoidedPlaces() {
        ItineraryData data = itinerary(
                step(0, "성산일출봉", List.of(alt("감천 문화마을"), alt("섭지코지"))),
                step(1, "부산 감천문화마을", List.of()));
        AvoidedPlaces avoided = AvoidedPlaces.of(List.of(new PlaceCandidate(0, 9L, "감천문화마을", "주소",
                "Landmarks and Outdoors > Historic Site", List.of(), "Busan", "KR", BigDecimal.valueOf(35.0975),
                BigDecimal.valueOf(129.0106), null, null, 0.5)));

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of(), avoided,
                PlaceRestrictions.NONE);

        assertThat(result.steps()).extracting(s -> s.place().name()).containsExactly("성산일출봉");
        assertThat(result.steps().get(0).alternatives()).extracting(AlternativeData::name).containsExactly("섭지코지");
    }

    @Test
    @DisplayName("못 먹는 음식·피할 활동은 이름과 모델이 붙인 종류로 스텝과 대안에서 뺀다")
    void dropsRestrictedPlaces() {
        ItineraryData data = itinerary(
                step(0, "섭지코지", List.of(alt("해운대 횟집", "맛집"), alt("올레국수", "맛집"))),
                stepOf(1, "한라산 등산로", "관광"));

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of(), AvoidedPlaces.NONE,
                PlaceRestrictions.of(List.of(PlaceRestriction.SEAFOOD,
                        PlaceRestriction.HIKING)));

        assertThat(result.steps()).extracting(s -> s.place().name()).containsExactly("섭지코지");
        assertThat(result.steps().get(0).alternatives()).extracting(AlternativeData::name).containsExactly("올레국수");
    }

    private static StepData stepOf(int order, String name, String kind) {
        return new StepData(order, 1, "10:00", "11:00", new PlaceData(name, "주소", kind, "제주", "KR"),
                List.of(), "CAR", 10, BigDecimal.ONE, BigDecimal.ZERO, "설명", BigDecimal.ZERO);
    }

    private static AlternativeData alt(String name, String kind) {
        return new AlternativeData(name, "주소", kind, "제주", "KR", "설명", BigDecimal.ZERO);
    }

    @Test
    @DisplayName("뺀 장소 다음 스텝의 이동 구간은 비우고, 다른 스텝의 이동 구간은 그대로 둔다")
    void clearsLegFromDroppedPlace() {
        ItineraryData data = itinerary(
                step(0, "성산일출봉", List.of()),
                step(1, "제주 러브랜드", List.of()),
                step(2, "섭지코지", List.of()),
                step(3, "우도", List.of()));

        ItineraryData result = ItineraryGenerationExecutor.withoutBlockedPlaces(data, List.of(), AvoidedPlaces.NONE,
                PlaceRestrictions.NONE);

        assertThat(result.steps()).extracting(s -> s.place().name()).containsExactly("성산일출봉", "섭지코지", "우도");
        assertThat(result.steps().get(1).transportationMode()).isNull();
        assertThat(result.steps().get(1).transportationCost()).isNull();
        assertThat(result.steps().get(2).transportationMode()).isEqualTo("CAR");
    }
}
