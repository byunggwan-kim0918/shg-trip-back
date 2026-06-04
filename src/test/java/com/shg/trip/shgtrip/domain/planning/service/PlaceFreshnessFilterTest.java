package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceFreshnessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceFreshnessFilterTest {

    @Mock
    private PlaceRepository placeRepository;

    @InjectMocks
    private PlaceFreshnessFilter filter;

    private PlaceCandidate createCandidate(int index, Long placeId, String name) {
        return new PlaceCandidate(
                index,
                placeId,
                name,
                "주소 " + index,
                "관광",
                List.of("태그1"),
                "시부야",
                "일본",
                BigDecimal.valueOf(35.6 + index * 0.01),
                BigDecimal.valueOf(139.7 + index * 0.01),
                "설명",
                BigDecimal.valueOf(4.0),
                0.9
        );
    }

    private Place createPlace(Long id, String name, OffsetDateTime googleSyncedAt) {
        return Place.builder()
                .id(id)
                .name(name)
                .address("주소")
                .latitude(BigDecimal.valueOf(35.6))
                .longitude(BigDecimal.valueOf(139.7))
                .category("관광")
                .savedAt(OffsetDateTime.now())
                .googleSyncedAt(googleSyncedAt)
                .build();
    }

    @Nested
    @DisplayName("filter - fresh/stale 분류")
    class FreshnessClassificationTests {

        @Test
        @DisplayName("7일 이내 장소는 fresh로 분류")
        void freshPlace_within7Days_classifiedAsFresh() {
            PlaceCandidate candidate = createCandidate(1, 100L, "센소지");
            Place place = createPlace(100L, "센소지", OffsetDateTime.now().minusDays(3));

            when(placeRepository.findAllById(List.of(100L))).thenReturn(List.of(place));

            PlaceFreshnessResult result = filter.filter(List.of(candidate));

            assertThat(result.freshPlaces()).hasSize(1);
            assertThat(result.freshPlaces().get(0).name()).isEqualTo("센소지");
            assertThat(result.stalePlaces()).isEmpty();
        }

        @Test
        @DisplayName("7일 초과 장소는 stale로 분류")
        void stalePlace_over7Days_classifiedAsStale() {
            PlaceCandidate candidate = createCandidate(1, 100L, "메이지신궁");
            Place place = createPlace(100L, "메이지신궁", OffsetDateTime.now().minusDays(8));

            when(placeRepository.findAllById(List.of(100L))).thenReturn(List.of(place));

            PlaceFreshnessResult result = filter.filter(List.of(candidate));

            assertThat(result.freshPlaces()).isEmpty();
            assertThat(result.stalePlaces()).hasSize(1);
            assertThat(result.stalePlaces().get(0).name()).isEqualTo("메이지신궁");
        }

        @Test
        @DisplayName("정확히 7일 전 장소는 fresh로 분류 (경계값)")
        void exactlySevenDaysAgo_classifiedAsFresh() {
            PlaceCandidate candidate = createCandidate(1, 100L, "도쿄타워");
            // OffsetDateTime.now().minusDays(7) 시점은 정확히 7일 전 = 7일 이내(isBefore가 false)
            Place place = createPlace(100L, "도쿄타워", OffsetDateTime.now().minusDays(7).plusMinutes(1));

            when(placeRepository.findAllById(List.of(100L))).thenReturn(List.of(place));

            PlaceFreshnessResult result = filter.filter(List.of(candidate));

            assertThat(result.freshPlaces()).hasSize(1);
            assertThat(result.stalePlaces()).isEmpty();
        }

        @Test
        @DisplayName("DB에 존재하지 않는 장소는 stale로 분류 (MISSING)")
        void missingPlace_notInDb_classifiedAsStale() {
            PlaceCandidate candidate = createCandidate(1, 999L, "새 장소");

            when(placeRepository.findAllById(List.of(999L))).thenReturn(List.of());

            PlaceFreshnessResult result = filter.filter(List.of(candidate));

            assertThat(result.freshPlaces()).isEmpty();
            assertThat(result.stalePlaces()).hasSize(1);
            assertThat(result.stalePlaces().get(0).name()).isEqualTo("새 장소");
        }

        @Test
        @DisplayName("placeId가 null인 후보는 stale로 분류 (MISSING)")
        void nullPlaceId_classifiedAsStale() {
            PlaceCandidate candidate = createCandidate(1, null, "미확인 장소");

            when(placeRepository.findAllById(List.of())).thenReturn(List.of());

            PlaceFreshnessResult result = filter.filter(List.of(candidate));

            assertThat(result.freshPlaces()).isEmpty();
            assertThat(result.stalePlaces()).hasSize(1);
        }

        @Test
        @DisplayName("savedAt이 null인 장소는 stale로 분류")
        void nullSavedAt_classifiedAsStale() {
            PlaceCandidate candidate = createCandidate(1, 100L, "장소");
            Place place = createPlace(100L, "장소", null);

            when(placeRepository.findAllById(List.of(100L))).thenReturn(List.of(place));

            PlaceFreshnessResult result = filter.filter(List.of(candidate));

            assertThat(result.freshPlaces()).isEmpty();
            assertThat(result.stalePlaces()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("filter - 복합 시나리오")
    class MixedScenarioTests {

        @Test
        @DisplayName("fresh, stale, missing이 섞인 후보 목록 올바르게 분류")
        void mixedCandidates_correctlyClassified() {
            PlaceCandidate freshCandidate = createCandidate(1, 100L, "신선한 장소");
            PlaceCandidate staleCandidate = createCandidate(2, 200L, "오래된 장소");
            PlaceCandidate missingCandidate = createCandidate(3, 300L, "미존재 장소");

            Place freshPlace = createPlace(100L, "신선한 장소", OffsetDateTime.now().minusDays(2));
            Place stalePlace = createPlace(200L, "오래된 장소", OffsetDateTime.now().minusDays(10));

            when(placeRepository.findAllById(anyList()))
                    .thenReturn(List.of(freshPlace, stalePlace));

            PlaceFreshnessResult result = filter.filter(
                    List.of(freshCandidate, staleCandidate, missingCandidate));

            assertThat(result.freshPlaces()).hasSize(1);
            assertThat(result.freshPlaces().get(0).name()).isEqualTo("신선한 장소");
            assertThat(result.stalePlaces()).hasSize(2);
            assertThat(result.stalePlaces()).extracting(PlaceCandidate::name)
                    .containsExactlyInAnyOrder("오래된 장소", "미존재 장소");
        }

        @Test
        @DisplayName("모든 후보가 fresh이면 stalePlaces는 빈 리스트")
        void allFresh_stalePlacesIsEmpty() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            List<Place> places = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                candidates.add(createCandidate(i, (long) i, "장소" + i));
                places.add(createPlace((long) i, "장소" + i, OffsetDateTime.now().minusDays(1)));
            }

            when(placeRepository.findAllById(anyList())).thenReturn(places);

            PlaceFreshnessResult result = filter.filter(candidates);

            assertThat(result.freshPlaces()).hasSize(5);
            assertThat(result.stalePlaces()).isEmpty();
        }

        @Test
        @DisplayName("모든 후보가 stale이면 freshPlaces는 빈 리스트")
        void allStale_freshPlacesIsEmpty() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            List<Place> places = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                candidates.add(createCandidate(i, (long) i, "장소" + i));
                places.add(createPlace((long) i, "장소" + i, OffsetDateTime.now().minusDays(14)));
            }

            when(placeRepository.findAllById(anyList())).thenReturn(places);

            PlaceFreshnessResult result = filter.filter(candidates);

            assertThat(result.freshPlaces()).isEmpty();
            assertThat(result.stalePlaces()).hasSize(5);
        }
    }

    @Nested
    @DisplayName("filter - 엣지 케이스")
    class EdgeCaseTests {

        @Test
        @DisplayName("candidates가 null이면 빈 결과 반환")
        void nullCandidates_returnsEmptyResult() {
            PlaceFreshnessResult result = filter.filter(null);

            assertThat(result.freshPlaces()).isEmpty();
            assertThat(result.stalePlaces()).isEmpty();
        }

        @Test
        @DisplayName("candidates가 빈 리스트이면 빈 결과 반환")
        void emptyCandidates_returnsEmptyResult() {
            PlaceFreshnessResult result = filter.filter(List.of());

            assertThat(result.freshPlaces()).isEmpty();
            assertThat(result.stalePlaces()).isEmpty();
        }

        @Test
        @DisplayName("중복 placeId를 가진 후보들은 각각 분류됨")
        void duplicatePlaceIds_eachClassifiedIndependently() {
            PlaceCandidate candidate1 = createCandidate(1, 100L, "장소A");
            PlaceCandidate candidate2 = createCandidate(2, 100L, "장소A복제");

            Place place = createPlace(100L, "장소A", OffsetDateTime.now().minusDays(2));

            when(placeRepository.findAllById(List.of(100L))).thenReturn(List.of(place));

            PlaceFreshnessResult result = filter.filter(List.of(candidate1, candidate2));

            assertThat(result.freshPlaces()).hasSize(2);
            assertThat(result.stalePlaces()).isEmpty();
        }
    }
}
