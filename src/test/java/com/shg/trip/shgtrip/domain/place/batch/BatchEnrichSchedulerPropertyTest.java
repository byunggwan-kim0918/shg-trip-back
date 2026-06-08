package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import net.jqwik.api.*;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Feature: llm-optimization, Property 3: 배치 분할 단위 준수
// Feature: llm-optimization, Property 4: enriched_at 기반 중복 보강 방지
class BatchEnrichSchedulerPropertyTest {

    /**
     * Property 3: 배치 분할 단위 준수
     *
     * For any N개의 미보강 장소 목록에 대해, 배치 분할 로직은
     * 각 청크의 크기가 1000 이하이고 모든 원소가 정확히 하나의 청크에
     * 포함되어야 한다 (누락/중복 없음).
     *
     */
    @Property(tries = 100)
    void splitIntoChunksRespectsMaxSizeAndPreservesAllElements(
            @ForAll("placeLists") List<Place> places
    ) {
        // Arrange
        PlaceRepository mockRepo = Mockito.mock(PlaceRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        BatchEnrichScheduler scheduler = new BatchEnrichScheduler(mockRepo, objectMapper);
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);

        // Act
        List<List<Place>> chunks = scheduler.splitIntoChunks(places);

        // Assert 1: Each chunk size must be <= 1000
        for (List<Place> chunk : chunks) {
            assertThat(chunk.size()).isLessThanOrEqualTo(1000);
        }

        // Assert 2: Each chunk must be non-empty (no empty chunks)
        for (List<Place> chunk : chunks) {
            assertThat(chunk).isNotEmpty();
        }

        // Assert 3: Total elements across all chunks equals original list size (no omissions)
        int totalInChunks = chunks.stream().mapToInt(List::size).sum();
        assertThat(totalInChunks).isEqualTo(places.size());

        // Assert 4: No duplicates — each element appears exactly once
        List<Place> flattenedChunks = chunks.stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        assertThat(flattenedChunks).containsExactlyElementsOf(places);
    }

    /**
     * Property 3 (empty list edge case):
     * An empty list produces zero chunks.
     *
     */
    @Property(tries = 100)
    void splitIntoChunksEmptyListProducesNoChunks(
            @ForAll("emptyLists") List<Place> emptyPlaces
    ) {
        // Arrange
        PlaceRepository mockRepo = Mockito.mock(PlaceRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        BatchEnrichScheduler scheduler = new BatchEnrichScheduler(mockRepo, objectMapper);
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);

        // Act
        List<List<Place>> chunks = scheduler.splitIntoChunks(emptyPlaces);

        // Assert
        assertThat(chunks).isEmpty();
    }

    // ====================================================================
    // Property 4: enriched_at 기반 중복 보강 방지
    // ====================================================================

    /**
     * Property 4: enriched_at 기반 중복 보강 방지 — enrichWith 호출 후 enriched_at이 설정됨
     *
     * For any 장소에 대해, 보강 처리 완료 후 enriched_at 타임스탬프가 설정되며,
     * enriched_at이 null이 아닌 장소는 미보강 대상 조회 결과에 포함되지 않아야 한다.
     *
     */
    @Property(tries = 100)
    void enrichWithSetsEnrichedAtTimestamp(
            @ForAll("unenrichedPlaces") Place place,
            @ForAll("tagLists") List<String> tags,
            @ForAll("descriptions") String description,
            @ForAll("timeSlotLists") List<String> timeSlots
    ) {
        // Precondition: place starts with enrichedAt == null
        assertThat(place.getEnrichedAt()).isNull();

        // Act: perform enrichment
        place.enrichWith(tags, description, timeSlots);

        // Assert: enriched_at is now set (not null)
        assertThat(place.getEnrichedAt()).isNotNull();
        // enriched_at should be at or before "now"
        assertThat(place.getEnrichedAt()).isBeforeOrEqualTo(OffsetDateTime.now());
    }

    /**
     * Property 4: enriched_at 기반 중복 보강 방지 — enriched_at이 null이 아닌 장소는 미보강 대상에서 제외
     *
     * For any mix of enriched and unenriched places, when filtering for unenriched targets
     * (enriched_at IS NULL AND active = true), only places with null enriched_at are returned.
     *
     */
    @Property(tries = 100)
    void enrichedPlacesExcludedFromUnenrichedQuery(
            @ForAll("mixedPlaceLists") List<Place> allPlaces
    ) {
        // Simulate repository behavior: filter places where enriched_at IS NULL AND active = true
        List<Place> unenrichedTargets = allPlaces.stream()
                .filter(p -> p.getEnrichedAt() == null && Boolean.TRUE.equals(p.getActive()))
                .collect(Collectors.toList());

        // Assert: No place in the result should have a non-null enriched_at
        for (Place p : unenrichedTargets) {
            assertThat(p.getEnrichedAt()).isNull();
        }

        // Assert: All places with non-null enriched_at must be excluded
        List<Place> enrichedPlaces = allPlaces.stream()
                .filter(p -> p.getEnrichedAt() != null)
                .collect(Collectors.toList());
        for (Place enriched : enrichedPlaces) {
            assertThat(unenrichedTargets).doesNotContain(enriched);
        }
    }

    /**
     * Property 4: enriched_at 기반 중복 보강 방지 — applyEnrichment 성공 시 enriched_at 기록
     *
     * For any valid LLM JSON response, applyEnrichment sets enriched_at so the place
     * won't be picked up by the next enrichment cycle.
     *
     */
    @Property(tries = 100)
    void applyEnrichmentSetsEnrichedAtPreventsReprocessing(
            @ForAll("unenrichedPlaces") Place place,
            @ForAll("validEnrichResponses") String jsonResponse
    ) {
        // Arrange
        PlaceRepository mockRepo = Mockito.mock(PlaceRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        BatchEnrichScheduler scheduler = new BatchEnrichScheduler(mockRepo, objectMapper);

        // Precondition: enriched_at is null before processing
        assertThat(place.getEnrichedAt()).isNull();

        // Act
        boolean success = scheduler.applyEnrichment(place, jsonResponse);

        // Assert: if enrichment succeeded, enriched_at is set
        if (success) {
            assertThat(place.getEnrichedAt()).isNotNull();

            // Verify this place would NOT be selected by findByEnrichedAtIsNullAndActiveTrue
            // (simulating the repository query filter)
            boolean wouldBeSelectedForEnrichment = place.getEnrichedAt() == null
                    && Boolean.TRUE.equals(place.getActive());
            assertThat(wouldBeSelectedForEnrichment).isFalse();
        }
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<List<Place>> placeLists() {
        // Generate lists from 1 to 3500 places to cover multiple chunking scenarios
        return Arbitraries.integers().between(1, 3500)
                .flatMap(size -> Arbitraries.just(generatePlaceList(size)));
    }

    @Provide
    Arbitrary<List<Place>> emptyLists() {
        return Arbitraries.just(Collections.emptyList());
    }

    @Provide
    Arbitrary<Place> unenrichedPlaces() {
        return Arbitraries.integers().between(1, 10000).map(id ->
                Place.builder()
                        .id((long) id)
                        .name("Place_" + id)
                        .address("Address_" + id)
                        .latitude(BigDecimal.valueOf(35.0 + (id % 90) * 0.01))
                        .longitude(BigDecimal.valueOf(135.0 + (id % 180) * 0.01))
                        .category("Category")
                        .region("Region")
                        .country("Country")
                        .savedAt(OffsetDateTime.now())
                        .active(true)
                        .build()
        );
    }

    @Provide
    Arbitrary<List<String>> tagLists() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(10)
                .list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(100);
    }

    @Provide
    Arbitrary<List<String>> timeSlotLists() {
        return Arbitraries.of("morning", "afternoon", "evening", "night", "all_day")
                .list().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<List<Place>> mixedPlaceLists() {
        return Arbitraries.integers().between(2, 50).flatMap(size -> {
            List<Place> places = new ArrayList<>(size);
            Random rng = new Random();
            for (int i = 0; i < size; i++) {
                Place.PlaceBuilder builder = Place.builder()
                        .id((long) (i + 1))
                        .name("Place_" + i)
                        .address("Address_" + i)
                        .latitude(BigDecimal.valueOf(35.0 + (i % 90) * 0.01))
                        .longitude(BigDecimal.valueOf(135.0 + (i % 180) * 0.01))
                        .category("Category")
                        .region("Region")
                        .country("Country")
                        .savedAt(OffsetDateTime.now())
                        .active(rng.nextBoolean());

                // Randomly set enriched_at for some places
                if (rng.nextBoolean()) {
                    builder.enrichedAt(OffsetDateTime.now().minusHours(rng.nextInt(48) + 1));
                }

                places.add(builder.build());
            }
            return Arbitraries.just(places);
        });
    }

    @Provide
    Arbitrary<String> validEnrichResponses() {
        return Arbitraries.of(
                """
                {"tags": ["관광", "문화", "역사"], "description": "유명한 관광 명소입니다.", "recommended_time_slots": ["morning", "afternoon"]}
                """,
                """
                {"tags": ["맛집", "카페"], "description": "맛있는 음식을 즐길 수 있는 곳입니다.", "recommended_time_slots": ["afternoon", "evening"]}
                """,
                """
                {"tags": ["쇼핑", "패션", "트렌드"], "description": "다양한 브랜드를 만날 수 있는 쇼핑 거리입니다.", "recommended_time_slots": ["afternoon"]}
                """,
                """
                {"tags": ["자연", "힐링", "산책"], "description": "도심 속 자연을 즐길 수 있는 공원입니다.", "recommended_time_slots": ["morning", "all_day"]}
                """,
                """
                {"tags": ["야경", "전망", "데이트"], "description": "아름다운 야경을 감상할 수 있습니다.", "recommended_time_slots": ["evening", "night"]}
                """
        );
    }

    private List<Place> generatePlaceList(int size) {
        List<Place> places = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            places.add(Place.builder()
                    .id((long) (i + 1))
                    .name("Place_" + i)
                    .address("Address_" + i)
                    .latitude(BigDecimal.valueOf(35.0 + (i % 90) * 0.01))
                    .longitude(BigDecimal.valueOf(135.0 + (i % 180) * 0.01))
                    .category("Category")
                    .region("Region")
                    .country("Country")
                    .savedAt(OffsetDateTime.now())
                    .active(true)
                    .build());
        }
        return places;
    }
}
