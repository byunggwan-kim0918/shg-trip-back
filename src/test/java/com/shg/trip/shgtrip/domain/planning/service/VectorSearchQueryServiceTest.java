package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
class VectorSearchQueryServiceTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private PlaceVectorSearchService placeVectorSearchService;
    @Captor private ArgumentCaptor<VectorSearchRequest> requestCaptor;

    private VectorSearchQueryService service;

    @BeforeEach
    void setUp() {
        service = new VectorSearchQueryService(embeddingService, placeVectorSearchService);
    }

    private static final float[] MOCK_VECTOR = new float[]{0.1f, 0.2f, 0.3f};

    private VectorEnrichedInput createBasicInput(int days) {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = start.plusDays(days - 1);
        return new VectorEnrichedInput(
                "도쿄", List.of("맛집", "관광"), List.of("음식", "관광", "쇼핑", "숙소"),
                "normal", "any", BigDecimal.valueOf(1000000), start, end,
                "도쿄 여행", null,
                "도쿄", "일본", List.of("시부야", "하라주쿠"),
                List.of("맛집", "쇼핑", "라멘"), null,
                "MEDIUM", "여름", "도쿄 여행 컨텍스트",
                null, null
        );
    }

    private VectorEnrichedInput createLongTripInput() {
        return new VectorEnrichedInput(
                "도쿄", List.of("맛집", "관광"), List.of("음식", "관광", "쇼핑", "숙소"),
                "normal", "any", BigDecimal.valueOf(3000000),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7),
                "도쿄 7일 여행", null,
                "도쿄", "일본", List.of("시부야", "하라주쿠", "아사쿠사", "우에노"),
                List.of("맛집", "관광", "쇼핑"),
                Map.of("1-3", List.of("시부야", "하라주쿠"), "4-7", List.of("아사쿠사", "우에노")),
                "HIGH", "여름", "도쿄 7일 여행 컨텍스트",
                null, null
        );
    }

    private List<VectorSearchResult> createMockResults(int count) {
        List<VectorSearchResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(new VectorSearchResult(
                    (long) (i + 1),
                    "장소 " + (i + 1),
                    "주소 " + (i + 1),
                    i % 2 == 0 ? "음식" : "관광",
                    List.of("태그" + i),
                    "시부야",
                    "일본",
                    BigDecimal.valueOf(35.6 + i * 0.01),
                    BigDecimal.valueOf(139.7 + i * 0.01),
                    "설명 " + (i + 1),
                    BigDecimal.valueOf(4.0 + (i % 10) * 0.1),
                    0.95 - i * 0.01
            ));
        }
        return results;
    }

    @Nested
    @DisplayName("search - 기본 검색 파이프라인")
    class SearchTests {

        @Test
        @DisplayName("기본 검색 파이프라인이 올바르게 동작한다 (카테고리별 검색)")
        void search_basicPipeline_returnsPlaceCandidates() {
            VectorEnrichedInput input = createBasicInput(3);
            given(embeddingService.embed(anyString())).willReturn(MOCK_VECTOR);
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(10));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result).hasSize(10);
            verify(embeddingService, atLeastOnce()).embed(anyString());
            verify(placeVectorSearchService, atLeastOnce()).search(requestCaptor.capture());

            // 카테고리별 검색이므로 여러 요청이 발생
            List<VectorSearchRequest> capturedRequests = requestCaptor.getAllValues();
            assertThat(capturedRequests).isNotEmpty();

            // 모든 요청이 같은 destination과 budgetRange를 가져야 함
            for (VectorSearchRequest req : capturedRequests) {
                assertThat(req.queryVector()).isEqualTo(MOCK_VECTOR);
                assertThat(req.destination()).isEqualTo("일본");
                assertThat(req.budgetRange()).isEqualTo("MEDIUM");
                assertThat(req.categories()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("검색 결과가 1-based 연속 인덱스를 가진다")
        void search_resultHasOneBasedContinuousIndex() {
            VectorEnrichedInput input = createBasicInput(3);
            given(embeddingService.embed(anyString())).willReturn(MOCK_VECTOR);
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            List<PlaceCandidate> result = service.search(input);

            for (int i = 0; i < result.size(); i++) {
                assertThat(result.get(i).index()).isEqualTo(i + 1);
            }
        }

        @Test
        @DisplayName("VectorSearchResult 필드가 PlaceCandidate로 올바르게 매핑된다")
        void search_fieldsAreMappedCorrectly() {
            VectorEnrichedInput input = createBasicInput(3);
            given(embeddingService.embed(anyString())).willReturn(MOCK_VECTOR);

            VectorSearchResult result = new VectorSearchResult(
                    42L, "센소지", "아사쿠사 2-3-1", "관광",
                    List.of("사찰", "역사"), "아사쿠사", "일본",
                    BigDecimal.valueOf(35.7148), BigDecimal.valueOf(139.7967),
                    "유명한 사찰", BigDecimal.valueOf(4.5), 0.92
            );
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(List.of(result));

            List<PlaceCandidate> candidates = service.search(input);

            assertThat(candidates).hasSize(1);
            PlaceCandidate candidate = candidates.get(0);
            assertThat(candidate.index()).isEqualTo(1);
            assertThat(candidate.placeId()).isEqualTo(42L);
            assertThat(candidate.name()).isEqualTo("센소지");
            assertThat(candidate.category()).isEqualTo("관광");
            assertThat(candidate.tags()).containsExactly("사찰", "역사");
            assertThat(candidate.region()).isEqualTo("아사쿠사");
            assertThat(candidate.country()).isEqualTo("일본");
            assertThat(candidate.latitude()).isEqualByComparingTo(BigDecimal.valueOf(35.7148));
            assertThat(candidate.longitude()).isEqualByComparingTo(BigDecimal.valueOf(139.7967));
            assertThat(candidate.description()).isEqualTo("유명한 사찰");
            assertThat(candidate.rating()).isEqualByComparingTo(BigDecimal.valueOf(4.5));
            assertThat(candidate.similarityScore()).isEqualTo(0.92);
        }
    }

    @Nested
    @DisplayName("search - 지역별 분리 검색 (5일+ 여행)")
    class RegionSplitSearchTests {

        @Test
        @DisplayName("5일+ 여행 시 regionAllocation에 따라 지역별 분리 검색을 수행한다 (카테고리별 추가)")
        void search_longTrip_searchesByRegion() {
            VectorEnrichedInput input = createLongTripInput();
            given(embeddingService.embed(anyString())).willReturn(MOCK_VECTOR);
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            List<PlaceCandidate> result = service.search(input);

            // 카테고리별 + 지역별 분리 검색이므로 여러 호출 발생
            // 최소 2회 이상의 검색 호출이 있어야 함
            verify(placeVectorSearchService, atLeast(2)).search(requestCaptor.capture());

            List<VectorSearchRequest> requests = requestCaptor.getAllValues();
            assertThat(requests).isNotEmpty();

            // 결과가 정상적으로 반환되어야 함
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("지역별 분리 검색 결과를 합산하여 연속 인덱스를 부여한다")
        void search_regionSplit_mergedResultsHaveContinuousIndex() {
            VectorEnrichedInput input = createLongTripInput();
            given(embeddingService.embed(anyString())).willReturn(MOCK_VECTOR);
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(3))
                    .willReturn(createMockResults(4));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result).hasSize(7);
            for (int i = 0; i < result.size(); i++) {
                assertThat(result.get(i).index()).isEqualTo(i + 1);
            }
        }

        @Test
        @DisplayName("regionAllocation이 없으면 단일 검색을 수행한다")
        void search_noRegionAllocation_singleSearch() {
            VectorEnrichedInput input = createBasicInput(7); // 7일이지만 regionAllocation 없음
            given(embeddingService.embed(anyString())).willReturn(MOCK_VECTOR);
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(10));

            service.search(input);

            verify(placeVectorSearchService, times(1)).search(any());
        }
    }

    @Nested
    @DisplayName("calculateTotalLimit - 총 반환 수 계산")
    class CalculateTotalLimitTests {

        @Test
        @DisplayName("3일 여행: days*10=30 → 30 반환")
        void threeDayTrip_returns30() {
            VectorEnrichedInput input = createBasicInput(3);
            assertThat(service.calculateTotalLimit(input)).isEqualTo(30);
        }

        @Test
        @DisplayName("4일 여행: days*10=40 → 40 반환")
        void fourDayTrip_returns40() {
            VectorEnrichedInput input = createBasicInput(4);
            assertThat(service.calculateTotalLimit(input)).isEqualTo(40);
        }

        @Test
        @DisplayName("5일 여행: days*10=50 → 50 반환")
        void fiveDayTrip_returns50() {
            VectorEnrichedInput input = createBasicInput(5);
            assertThat(service.calculateTotalLimit(input)).isEqualTo(50);
        }

        @Test
        @DisplayName("1일 여행: days*10=10 → min 30 반환")
        void oneDayTrip_minIs30() {
            VectorEnrichedInput input = createBasicInput(1);
            assertThat(service.calculateTotalLimit(input)).isEqualTo(30);
        }

        @Test
        @DisplayName("2일 여행: days*10=20 → min 30 반환")
        void twoDayTrip_minIs30() {
            VectorEnrichedInput input = createBasicInput(2);
            assertThat(service.calculateTotalLimit(input)).isEqualTo(30);
        }

        @Test
        @DisplayName("날짜가 null이면 기본값 3일 적용 → 30")
        void nullDates_defaultsTo3Days() {
            VectorEnrichedInput input = new VectorEnrichedInput(
                    "도쿄", List.of("관광"), List.of("관광"), "normal", "any",
                    BigDecimal.valueOf(1000000), null, null,
                    null, null,
                    "도쿄", "일본", null, List.of("관광"), null,
                    "MEDIUM", null, null,
                    null, null
            );
            assertThat(service.calculateTotalLimit(input)).isEqualTo(30);
        }
    }

    // NOTE: calculatePerCategoryLimit은 구현되지 않은 메서드 (내부 로직만 사용)
    // 테스트는 카테고리별 벡터 검색의 통합 테스트로 대체

    @Nested
    @DisplayName("shouldSplitByRegion - 지역 분리 검색 판단")
    class ShouldSplitByRegionTests {

        @Test
        @DisplayName("5일+ & regionAllocation 있음 → true")
        void longTripWithRegionAllocation_returnsTrue() {
            VectorEnrichedInput input = createLongTripInput();
            assertThat(service.shouldSplitByRegion(input)).isTrue();
        }

        @Test
        @DisplayName("3일 여행 & regionAllocation 있음 → false")
        void shortTripWithRegionAllocation_returnsFalse() {
            VectorEnrichedInput input = new VectorEnrichedInput(
                    "도쿄", List.of("관광"), List.of("관광"), "normal", "any",
                    BigDecimal.valueOf(1000000),
                    LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3),
                    null, null,
                    "도쿄", "일본", null, List.of("관광"),
                    Map.of("1-3", List.of("시부야")),
                    "MEDIUM", null, null,
                    null, null
            );
            assertThat(service.shouldSplitByRegion(input)).isFalse();
        }

        @Test
        @DisplayName("regionAllocation이 null → false")
        void nullRegionAllocation_returnsFalse() {
            VectorEnrichedInput input = createBasicInput(7);
            assertThat(service.shouldSplitByRegion(input)).isFalse();
        }

        @Test
        @DisplayName("regionAllocation이 빈 맵 → false")
        void emptyRegionAllocation_returnsFalse() {
            VectorEnrichedInput input = new VectorEnrichedInput(
                    "도쿄", List.of("관광"), List.of("관광"), "normal", "any",
                    BigDecimal.valueOf(1000000),
                    LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7),
                    null, null,
                    "도쿄", "일본", null, List.of("관광"),
                    Map.of(),
                    "MEDIUM", null, null,
                    null, null
            );
            assertThat(service.shouldSplitByRegion(input)).isFalse();
        }
    }

    @Nested
    @DisplayName("convertToCandidates - VectorSearchResult → PlaceCandidate 변환")
    class ConvertToCandidatesTests {

        @Test
        @DisplayName("빈 목록 입력 시 빈 목록 반환")
        void emptyInput_returnsEmptyList() {
            assertThat(service.convertToCandidates(List.of())).isEmpty();
        }

        @Test
        @DisplayName("인덱스가 1부터 시작하고 연속적이다")
        void indexStartsAt1AndIsContinuous() {
            List<VectorSearchResult> results = createMockResults(5);
            List<PlaceCandidate> candidates = service.convertToCandidates(results);

            assertThat(candidates).hasSize(5);
            assertThat(candidates.get(0).index()).isEqualTo(1);
            assertThat(candidates.get(1).index()).isEqualTo(2);
            assertThat(candidates.get(2).index()).isEqualTo(3);
            assertThat(candidates.get(3).index()).isEqualTo(4);
            assertThat(candidates.get(4).index()).isEqualTo(5);
        }
    }
}
