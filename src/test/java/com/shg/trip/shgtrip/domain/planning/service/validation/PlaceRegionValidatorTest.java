package com.shg.trip.shgtrip.domain.planning.service.validation;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceRegionValidatorTest {

    private final PlaceRegionValidator validator =
            new PlaceRegionValidator(com.shg.trip.shgtrip.domain.place.destination.Destinations.fromClasspath());

    private Place place(String name, String region) {
        return place(name, region, "KR");
    }

    private Place place(String name, String region, String country) {
        return Place.builder()
                .name(name).region(region).country(country)
                .latitude(BigDecimal.ZERO).longitude(BigDecimal.ZERO)
                .category("Landmarks and Outdoors")
                .build();
    }

    private VectorEnrichedInput enriched(String destination, List<String> regions, String country,
                                         Map<String, List<String>> regionAllocation) {
        return new VectorEnrichedInput(
                destination, List.of(), List.of(), "normal", "any",
                BigDecimal.valueOf(500000), LocalDate.now(), LocalDate.now().plusDays(2),
                "", List.of(), destination, country, regions, List.of(),
                regionAllocation, "MEDIUM", "", "", null, Map.of());
    }

    @Test
    @DisplayName("여행지와 다른 지역 장소 선택 시 PLACE_REGION_MISMATCH 예외")
    void rejectsMismatchedRegion() {
        var enriched = enriched("제주도", List.of("Jeju"), "KR", null);
        var places = List.of(place("명동", "Seoul"), place("성산일출봉", "Jeju"));

        assertThatThrownBy(() -> validator.validate(places, enriched))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLACE_REGION_MISMATCH))
                .hasMessageContaining("명동")
                .hasMessageContaining("제주도");
    }

    @Test
    @DisplayName("여행지와 같은 지역 장소만 선택하면 통과")
    void passesWhenAllInRegion() {
        var enriched = enriched("제주도", List.of("Jeju"), "KR", null);
        var places = List.of(place("성산일출봉", "Jeju"), place("협재해수욕장", "Jeju"));

        assertThatCode(() -> validator.validate(places, enriched)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("선택 장소 없음(AUTO 모드)이면 통과")
    void passesWhenNoSelectedPlaces() {
        var enriched = enriched("제주도", List.of("Jeju"), "KR", null);

        assertThatCode(() -> validator.validate(List.of(), enriched)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(null, enriched)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enrich regions가 비어있으면 검증 스킵(오탐 방지)")
    void skipsWhenRegionsEmpty() {
        var enriched = enriched("어딘가", List.of(), "KR", null);
        var places = List.of(place("명동", "Seoul"));

        assertThatCode(() -> validator.validate(places, enriched)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다지역 여행(regionAllocation)은 그 지역들을 모두 허용")
    void allowsMultiRegionViaAllocation() {
        var enriched = enriched("서울부산", List.of("Seoul"), "KR",
                Map.of("1-2", List.of("Seoul"), "3-4", List.of("Busan")));
        var places = List.of(place("명동", "Seoul"), place("해운대", "Busan"));

        assertThatCode(() -> validator.validate(places, enriched)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("해외도 여행지가 다른 장소는 거절한다")
    void rejectsOverseasMismatch() {
        var enriched = enriched("도쿄", List.of("Tokyo"), "JP", null);
        var places = List.of(place("어떤장소", "Osaka", "JP"));

        assertThatThrownBy(() -> validator.validate(places, enriched)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("해외는 근교·상위 여행지 장소를 허용한다(타이베이 여행의 지우펀, 하코네 여행의 도쿄)")
    void allowsOverseasNearbyDestinations() {
        assertThatCode(() -> validator.validate(List.of(place("九份老街", "Jiufen", "TW")),
                enriched("타이베이", List.of("Taipei"), "TW", null))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(List.of(place("東京駅", "Tokyo", "JP")),
                enriched("하코네", List.of("Hakone"), "JP", null))).doesNotThrowAnyException();
        // 교토·나라는 둘 다 오사카 근교라 서로 허용한다
        assertThatCode(() -> validator.validate(List.of(place("奈良公園", "Nara", "JP")),
                enriched("교토", List.of("Kyoto"), "JP", null))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(List.of(place("城崎温泉", "Kinosaki", "JP")),
                enriched("나라", List.of("Nara"), "JP", null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("장소의 region이 null이면 해당 장소는 검증 대상에서 제외")
    void skipsPlaceWithNullRegion() {
        var enriched = enriched("제주도", List.of("Jeju"), "KR", null);
        var places = List.of(place("좌표없는장소", null));

        assertThatCode(() -> validator.validate(places, enriched)).doesNotThrowAnyException();
    }
}
