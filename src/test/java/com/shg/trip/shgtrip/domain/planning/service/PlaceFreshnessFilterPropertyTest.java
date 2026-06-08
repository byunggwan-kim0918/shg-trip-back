package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceFreshnessResult;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Feature: llm-optimization, Property 10: 장소 freshness 기반 재사용/갱신 결정
class PlaceFreshnessFilterPropertyTest {

    private final PlaceRepository placeRepository = mock(PlaceRepository.class);
    private final PlaceFreshnessFilter filter = new PlaceFreshnessFilter(placeRepository);

    /**
     * Property 10: 장소 freshness 기반 재사용/갱신 결정
     *
     * For any 장소 with savedAt within 7 days → classified as fresh (no API call needed).
     * All candidates backed by a Place with savedAt within 7 days must appear in freshPlaces.
     *
     */
    @Property(tries = 100)
    void freshPlaces_within7Days_classifiedAsFresh(
            @ForAll("freshCandidatesWithPlaces") CandidatesWithPlaces input
    ) {
        when(placeRepository.findAllById(anyList())).thenReturn(input.places());

        PlaceFreshnessResult result = filter.filter(input.candidates());

        // All candidates should be classified as fresh
        assertThat(result.freshPlaces()).hasSameSizeAs(input.candidates());
        assertThat(result.stalePlaces()).isEmpty();

        // Verify each fresh candidate corresponds to the input
        List<Long> freshIds = result.freshPlaces().stream()
                .map(PlaceCandidate::placeId)
                .collect(Collectors.toList());
        List<Long> inputIds = input.candidates().stream()
                .map(PlaceCandidate::placeId)
                .collect(Collectors.toList());
        assertThat(freshIds).containsExactlyInAnyOrderElementsOf(inputIds);
    }

    /**
     * Property 10: 장소 freshness 기반 재사용/갱신 결정
     *
     * For any 장소 with savedAt > 7 days ago → classified as stale (needs refresh).
     * All candidates backed by a Place with savedAt older than 7 days must appear in stalePlaces.
     *
     */
    @Property(tries = 100)
    void stalePlaces_over7Days_classifiedAsStale(
            @ForAll("staleCandidatesWithPlaces") CandidatesWithPlaces input
    ) {
        when(placeRepository.findAllById(anyList())).thenReturn(input.places());

        PlaceFreshnessResult result = filter.filter(input.candidates());

        // All candidates should be classified as stale
        assertThat(result.stalePlaces()).hasSameSizeAs(input.candidates());
        assertThat(result.freshPlaces()).isEmpty();

        // Verify each stale candidate corresponds to the input
        List<Long> staleIds = result.stalePlaces().stream()
                .map(PlaceCandidate::placeId)
                .collect(Collectors.toList());
        List<Long> inputIds = input.candidates().stream()
                .map(PlaceCandidate::placeId)
                .collect(Collectors.toList());
        assertThat(staleIds).containsExactlyInAnyOrderElementsOf(inputIds);
    }

    /**
     * Property 10: 장소 freshness 기반 재사용/갱신 결정
     *
     * For any mixed set of fresh and stale places, the boundary at exactly 7 days is consistent:
     * - savedAt == now - 7 days (exactly on boundary) → the comparison uses isBefore(threshold),
     *   so a savedAt equal to the threshold is NOT before it → classified as fresh.
     * - savedAt just past the 7-day boundary → classified as stale.
     *
     * This test verifies the classification is a proper partition (no element lost or duplicated)
     * and that fresh/stale classification matches the 7-day boundary consistently.
     *
     */
    @Property(tries = 100)
    void mixedFreshAndStale_correctPartition(
            @ForAll("mixedCandidatesWithPlaces") MixedCandidatesWithPlaces input
    ) {
        when(placeRepository.findAllById(anyList())).thenReturn(input.allPlaces());

        PlaceFreshnessResult result = filter.filter(input.allCandidates());

        // Partition property: freshPlaces + stalePlaces == all candidates (no loss, no duplication)
        assertThat(result.freshPlaces().size() + result.stalePlaces().size())
                .isEqualTo(input.allCandidates().size());

        // Fresh candidates should only contain candidates from freshCandidateIds
        List<Long> freshResultIds = result.freshPlaces().stream()
                .map(PlaceCandidate::placeId)
                .collect(Collectors.toList());
        assertThat(freshResultIds).containsExactlyInAnyOrderElementsOf(input.freshCandidateIds());

        // Stale candidates should only contain candidates from staleCandidateIds
        List<Long> staleResultIds = result.stalePlaces().stream()
                .map(PlaceCandidate::placeId)
                .collect(Collectors.toList());
        assertThat(staleResultIds).containsExactlyInAnyOrderElementsOf(input.staleCandidateIds());
    }

    // --- Test Data Types ---

    record CandidatesWithPlaces(
            List<PlaceCandidate> candidates,
            List<Place> places
    ) {}

    record MixedCandidatesWithPlaces(
            List<PlaceCandidate> allCandidates,
            List<Place> allPlaces,
            List<Long> freshCandidateIds,
            List<Long> staleCandidateIds
    ) {}

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<CandidatesWithPlaces> freshCandidatesWithPlaces() {
        // Generate 1-20 candidates, all with savedAt within 7 days (fresh)
        return Arbitraries.integers().between(1, 20).flatMap(count -> {
            // Days ago: 0 to 6 (strictly within 7 days)
            Arbitrary<List<Integer>> daysAgoList = Arbitraries.integers().between(0, 6)
                    .list().ofSize(count);

            return daysAgoList.map(daysList -> {
                List<PlaceCandidate> candidates = new ArrayList<>();
                List<Place> places = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    long placeId = 100L + i;
                    int daysAgo = daysList.get(i);

                    candidates.add(createCandidate(i + 1, placeId, "장소_" + i));
                    places.add(createPlace(placeId, "장소_" + i,
                            OffsetDateTime.now().minusDays(daysAgo).minusHours(1)));
                }

                return new CandidatesWithPlaces(candidates, places);
            });
        });
    }

    @Provide
    Arbitrary<CandidatesWithPlaces> staleCandidatesWithPlaces() {
        // Generate 1-20 candidates, all with savedAt older than 7 days (stale)
        return Arbitraries.integers().between(1, 20).flatMap(count -> {
            // Days ago: 8 to 60 (strictly beyond 7 days)
            Arbitrary<List<Integer>> daysAgoList = Arbitraries.integers().between(8, 60)
                    .list().ofSize(count);

            return daysAgoList.map(daysList -> {
                List<PlaceCandidate> candidates = new ArrayList<>();
                List<Place> places = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    long placeId = 200L + i;
                    int daysAgo = daysList.get(i);

                    candidates.add(createCandidate(i + 1, placeId, "오래된_" + i));
                    places.add(createPlace(placeId, "오래된_" + i,
                            OffsetDateTime.now().minusDays(daysAgo)));
                }

                return new CandidatesWithPlaces(candidates, places);
            });
        });
    }

    @Provide
    Arbitrary<MixedCandidatesWithPlaces> mixedCandidatesWithPlaces() {
        // Generate a mix of fresh (0-6 days) and stale (8-60 days) candidates
        Arbitrary<Integer> freshCount = Arbitraries.integers().between(1, 10);
        Arbitrary<Integer> staleCount = Arbitraries.integers().between(1, 10);

        return Combinators.combine(freshCount, staleCount).flatAs((fc, sc) -> {
            Arbitrary<List<Integer>> freshDays = Arbitraries.integers().between(0, 6)
                    .list().ofSize(fc);
            Arbitrary<List<Integer>> staleDays = Arbitraries.integers().between(8, 60)
                    .list().ofSize(sc);

            return Combinators.combine(freshDays, staleDays).as((fDays, sDays) -> {
                List<PlaceCandidate> allCandidates = new ArrayList<>();
                List<Place> allPlaces = new ArrayList<>();
                List<Long> freshIds = new ArrayList<>();
                List<Long> staleIds = new ArrayList<>();

                int index = 1;

                // Add fresh candidates
                for (int i = 0; i < fc; i++) {
                    long placeId = 1000L + i;
                    allCandidates.add(createCandidate(index++, placeId, "fresh_" + i));
                    allPlaces.add(createPlace(placeId, "fresh_" + i,
                            OffsetDateTime.now().minusDays(fDays.get(i)).minusHours(1)));
                    freshIds.add(placeId);
                }

                // Add stale candidates
                for (int i = 0; i < sc; i++) {
                    long placeId = 2000L + i;
                    allCandidates.add(createCandidate(index++, placeId, "stale_" + i));
                    allPlaces.add(createPlace(placeId, "stale_" + i,
                            OffsetDateTime.now().minusDays(sDays.get(i))));
                    staleIds.add(placeId);
                }

                return new MixedCandidatesWithPlaces(allCandidates, allPlaces, freshIds, staleIds);
            });
        });
    }

    // --- Helper Methods ---

    private static PlaceCandidate createCandidate(int index, Long placeId, String name) {
        return new PlaceCandidate(
                index,
                placeId,
                name,
                "주소_" + index,
                "관광",
                List.of("태그1"),
                "도쿄",
                "일본",
                BigDecimal.valueOf(35.6 + index * 0.01),
                BigDecimal.valueOf(139.7 + index * 0.01),
                "설명_" + name,
                BigDecimal.valueOf(4.0),
                0.85
        );
    }

    private static Place createPlace(Long id, String name, OffsetDateTime googleSyncedAt) {
        return Place.builder()
                .id(id)
                .name(name)
                .address("주소_" + name)
                .latitude(BigDecimal.valueOf(35.6))
                .longitude(BigDecimal.valueOf(139.7))
                .category("관광")
                .savedAt(OffsetDateTime.now())
                .googleSyncedAt(googleSyncedAt)
                .build();
    }
}
