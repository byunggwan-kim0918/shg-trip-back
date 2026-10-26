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
import static org.mockito.ArgumentMatchers.anyList;
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

    /** 슬롯 수만큼의 임베딩 벡터를 배치로 돌려주는 스텁. */
    private void givenBatchEmbeddings() {
        given(embeddingService.embedBatch(anyList())).willAnswer(inv -> {
            List<?> texts = inv.getArgument(0);
            List<float[]> vectors = new java.util.ArrayList<>();
            for (int i = 0; i < texts.size(); i++) vectors.add(MOCK_VECTOR);
            return vectors;
        });
    }

    /** 테마·카테고리만 바꾼 3일 입력. */
    private VectorEnrichedInput inputWith(List<String> themes, List<String> categories) {
        LocalDate start = LocalDate.of(2026, 8, 1);
        return new VectorEnrichedInput(
                "도쿄", themes, categories,
                "normal", "any", BigDecimal.valueOf(1000000), start, start.plusDays(2),
                "도쿄 여행", null,
                "도쿄", "일본", List.of("시부야"),
                List.of("맛집"), null,
                "MEDIUM", "여름", "컨텍스트",
                null, null
        );
    }

    private VectorSearchResult resultWithSimilarity(long id, String name, double similarity) {
        return new VectorSearchResult(
                id, name, "주소", "Dining and Drinking > Restaurant", List.of(),
                "시부야", "일본", BigDecimal.valueOf(35.6), BigDecimal.valueOf(139.7),
                "설명", BigDecimal.valueOf(4.0), similarity);
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
    @DisplayName("search - 슬롯 기반 검색 파이프라인")
    class SearchTests {

        @Test
        @DisplayName("임베딩은 슬롯 수와 무관하게 embedBatch 1회로 생성된다")
        void search_embedsAllSlotQueriesInOneBatch() {
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(10));

            service.search(input);

            verify(embeddingService, times(1)).embedBatch(anyList());
            verify(embeddingService, org.mockito.Mockito.never()).embed(anyString());
        }

        @Test
        @DisplayName("고정 역할 슬롯(식당/카페/숙소) + 사용자 카테고리·테마별 관광 슬롯으로 분리 검색한다")
        void search_splitsAttractionSlotsByUserInput() {
            VectorEnrichedInput input = inputWith(
                    List.of("ocean", "nightview"),               // 검색 의미가 있는 테마 2개
                    List.of("restaurant", "cafe", "beach", "viewpoint"));
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            service.search(input);

            // 식당 + 카페 + 숙소 + 관광(beach, viewpoint, ocean, nightview, 기본) = 8슬롯
            verify(placeVectorSearchService, times(8)).search(requestCaptor.capture());
            assertThat(requestCaptor.getAllValues()).hasSize(8);
        }

        @Test
        @DisplayName("슬롯마다 '명백히 틀린 대분류' 배제 패턴이 요청에 실린다 (하드 카테고리 필터는 없음)")
        void search_passesSlotExclusionsNotCategoryFilter() {
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            service.search(input);

            verify(placeVectorSearchService, atLeastOnce()).search(requestCaptor.capture());
            for (VectorSearchRequest req : requestCaptor.getAllValues()) {
                assertThat(req.destination()).isEqualTo("일본");
                assertThat(req.budgetRange()).isEqualTo("MEDIUM");
                // 마법사 카테고리 id ↔ DB category 형식이 달라 하드 필터는 쓰지 않는다
                assertThat(req.categories()).isNull();
                assertThat(req.excludedCategoryPatterns()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("최소 유사도 미만 결과는 버려진다 (데이터 부족 시 limit 채우려 무관 장소를 끌어오지 않음)")
        void search_dropsResultsBelowSimilarityThreshold() {
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(List.of(
                            resultWithSimilarity(1L, "충분히 유사한 곳", 0.80),
                            resultWithSimilarity(2L, "무관한 곳", 0.10)));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result).extracting(PlaceCandidate::name).containsOnly("충분히 유사한 곳");
        }

        @Test
        @DisplayName("여러 슬롯에 중복 등장한 같은 장소는 1회만 후보가 된다")
        void search_dedupesSamePlaceAcrossSlots() {
            VectorEnrichedInput input = inputWith(List.of("ocean"), List.of("beach", "viewpoint"));
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(4)); // 모든 슬롯이 같은 placeId 1~4를 반환

            List<PlaceCandidate> result = service.search(input);

            assertThat(result).hasSize(4);
            assertThat(result).extracting(PlaceCandidate::placeId).doesNotHaveDuplicates();
        }

        /** 평점·리뷰 수만 다른 후보를 만든다. 유사도와 카테고리는 동일. */
        private VectorSearchResult candidate(long id, String name, Double rating,
                                             Integer reviewCount, double similarity) {
            return new VectorSearchResult(
                    id, name, "주소" + id, "Dining and Drinking > Restaurant", List.of(),
                    "시부야", "일본", BigDecimal.valueOf(35.6), BigDecimal.valueOf(139.7),
                    "설명", rating != null ? BigDecimal.valueOf(rating) : null,
                    reviewCount, similarity);
        }

        @Test
        @DisplayName("리뷰 수 없는 고평점은 유사도를 뒤집지 못한다 — 평점 단독은 신뢰 신호가 아니다")
        void search_ratingAloneDoesNotOutrankSimilarity() {
            // 리뷰 수를 모르면 평점 4.9도 인기도 근거가 못 된다. 실측: 리뷰 두세 개짜리 5.0이
            // 수만 리뷰의 명소를 이기는 현상(사라봉공원 5.0 vs 협재해수욕장 4.7)이 여기서 나왔다.
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(List.of(
                            candidate(1L, "무평점 유사", null, null, 0.62),
                            candidate(2L, "고평점 리뷰미상", 4.9, null, 0.60)));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result.get(0).name()).isEqualTo("무평점 유사");
        }

        @Test
        @DisplayName("리뷰 많은 명소가 리뷰 적은 만점 장소를 이긴다 (실측 협재해수욕장 vs 사라봉공원)")
        void search_manyReviewsOutrankFewReviewPerfectScore() {
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(List.of(
                            // 리뷰 3개 만점 — 유사도는 오히려 더 높다
                            candidate(1L, "사라봉공원형", 5.0, 3, 0.65),
                            // 리뷰 2만개 4.7 — 유사도는 낮지만 인기도가 압도
                            candidate(2L, "협재해수욕장형", 4.7, 20_000, 0.60)));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result.get(0).name()).isEqualTo("협재해수욕장형");
        }

        @Test
        @DisplayName("리뷰 수 미상은 '무명임을 아는' 장소보다 위에 온다 — 자기강화 루프 방지")
        void search_unknownPopularityRanksAboveKnownObscure() {
            // TourAPI로 적재된 명소(성산일출봉·천지연폭포 등)는 Google 미동기화라 리뷰 수가 없다.
            // 이들을 '리뷰 적은 무명 장소'보다 밀어내면 후보가 되지 못해 영원히 동기화되지 않는다.
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(List.of(
                            candidate(1L, "무명 확인됨", 4.5, 5, 0.60),
                            candidate(2L, "미동기화 명소", null, null, 0.60)));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result.get(0).name()).isEqualTo("미동기화 명소");
        }

        @Test
        @DisplayName("리뷰 수 가점은 포화한다 — 초대형 명소 하나가 전부를 누르지 않는다")
        void search_popularitySaturates() {
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(List.of(
                            // 리뷰 50만개지만 포화 상한(1만)을 넘어 추가 가점이 없다.
                            // 유사도 0.08 차이를 인기도로 뒤집지 못한다(최대 가점 0.10, 이미 양쪽 모두 포화).
                            candidate(1L, "초대형 명소", 4.6, 500_000, 0.55),
                            candidate(2L, "유사도 우위 명소", 4.6, 12_000, 0.63)));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result.get(0).name()).isEqualTo("유사도 우위 명소");
        }

        @Test
        @DisplayName("검색 결과가 1-based 연속 인덱스를 가진다")
        void search_resultHasOneBasedContinuousIndex() {
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            List<PlaceCandidate> result = service.search(input);

            for (int i = 0; i < result.size(); i++) {
                assertThat(result.get(i).index()).isEqualTo(i + 1);
            }
        }

        @Test
        @DisplayName("VectorSearchResult 필드가 PlaceCandidate로 올바르게 매핑되고 주소에서 세부지역이 추출된다")
        void search_fieldsAreMappedCorrectly() {
            VectorEnrichedInput input = createBasicInput(3);
            givenBatchEmbeddings();

            VectorSearchResult result = new VectorSearchResult(
                    42L, "센소지", "제주특별자치도 서귀포시 성산읍 1", "Landmarks and Outdoors > Temple",
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
            assertThat(candidate.tags()).containsExactly("사찰", "역사");
            assertThat(candidate.region()).isEqualTo("아사쿠사");
            assertThat(candidate.country()).isEqualTo("일본");
            assertThat(candidate.latitude()).isEqualByComparingTo(BigDecimal.valueOf(35.7148));
            assertThat(candidate.longitude()).isEqualByComparingTo(BigDecimal.valueOf(139.7967));
            assertThat(candidate.description()).isEqualTo("유명한 사찰");
            assertThat(candidate.rating()).isEqualByComparingTo(BigDecimal.valueOf(4.5));
            assertThat(candidate.similarityScore()).isEqualTo(0.92);
            assertThat(candidate.subRegion()).isEqualTo("서귀포시");
            assertThat(candidate.displayRegion()).isEqualTo("서귀포시");
        }
    }

    @Nested
    @DisplayName("search - 지역별 분리 검색 (5일+ 여행)")
    class RegionSplitSearchTests {

        @Test
        @DisplayName("5일+ 여행 시 regionAllocation에 따라 슬롯마다 지역별 분리 검색을 수행한다")
        void search_longTrip_searchesByRegion() {
            VectorEnrichedInput input = createLongTripInput();
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            List<PlaceCandidate> result = service.search(input);

            // 슬롯 수 × regionAllocation 엔트리 수만큼 호출
            verify(placeVectorSearchService, atLeast(2)).search(requestCaptor.capture());
            assertThat(requestCaptor.getAllValues()).isNotEmpty();
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("지역별 분리 검색 결과를 합산해도 인덱스는 1-based 연속이다")
        void search_regionSplit_mergedResultsHaveContinuousIndex() {
            VectorEnrichedInput input = createLongTripInput();
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(3));

            List<PlaceCandidate> result = service.search(input);

            assertThat(result).isNotEmpty();
            for (int i = 0; i < result.size(); i++) {
                assertThat(result.get(i).index()).isEqualTo(i + 1);
            }
        }

        @Test
        @DisplayName("regionAllocation이 없으면 슬롯당 1회씩만 검색한다")
        void search_noRegionAllocation_singleSearchPerSlot() {
            VectorEnrichedInput input = inputWith(List.of("culture"), List.of("restaurant"));
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(10));

            service.search(input);

            // restaurant + cafe + accommodation + culture 테마 관광 + 기본 관광 = 5슬롯
            verify(placeVectorSearchService, times(5)).search(any());
        }

        /**
         * 실측 회귀(제주 5일): enrich가 regionAllocation에 행정동·지형·랜드마크를 돌려줬는데
         * 그 값이 그대로 {@code p.region = ?} 필터로 들어가 전 슬롯이 조회=0이 됐다.
         * DB의 region 값은 'Jeju' 하나뿐이라 '제주시'·'용담동'은 어떤 행과도 매칭되지 않는다.
         */
        @Test
        @DisplayName("regionAllocation 값이 regions 어휘에 없으면 DB 지역 필터로 쓰지 않는다")
        void search_subRegionNames_neverBecomeHardFilter() {
            VectorEnrichedInput input = jejuLongTripInput();
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            service.search(input);

            verify(placeVectorSearchService, atLeastOnce()).search(requestCaptor.capture());
            for (VectorSearchRequest request : requestCaptor.getAllValues()) {
                if (request.regions() == null) continue;
                assertThat(request.regions()).containsOnly("Jeju");
            }
        }

        @Test
        @DisplayName("필터로 못 쓰는 세부 지명은 쿼리 텍스트에 얹혀 유사도로 반영된다")
        void search_subRegionNames_becomeQueryHints() {
            VectorEnrichedInput input = jejuLongTripInput();
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(5));

            service.search(input);

            ArgumentCaptor<List<String>> textCaptor = ArgumentCaptor.forClass(List.class);
            verify(embeddingService).embedBatch(textCaptor.capture());
            assertThat(textCaptor.getValue())
                    .anyMatch(text -> text.contains("제주시") && text.contains("용담동"))
                    .anyMatch(text -> text.contains("성산일출봉"));
        }

        @Test
        @DisplayName("지역 필터가 0건을 만들면 상위 지역으로 완화해 재조회한다")
        void search_emptyRegionFilter_retriesWithoutIt() {
            VectorEnrichedInput input = multiCityInput();
            givenBatchEmbeddings();
            // Busan 단독 필터만 0건 — 상위 지역으로 완화한 재조회는 정상 (fail-open 경로 강제)
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willAnswer(inv -> {
                        VectorSearchRequest request = inv.getArgument(0);
                        return List.of("Busan").equals(request.regions())
                                ? List.of()
                                : createMockResults(5);
                    });

            List<PlaceCandidate> result = service.search(input);

            verify(placeVectorSearchService, atLeastOnce()).search(requestCaptor.capture());
            assertThat(requestCaptor.getAllValues())
                    .as("Busan 슬롯이 0건이면 상위 지역 전체로 완화한 재조회가 뒤따라야 한다")
                    .anyMatch(r -> r.regions() != null
                            && r.regions().containsAll(List.of("Seoul", "Busan")));
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("지역 단위끼리 겹친 결과가 랭킹 상위를 잠식해 후보 풀을 얕게 만들지 않는다")
        void search_duplicateAcrossRegionUnits_doesNotShrinkPool() {
            // 세부 지명은 힌트로만 쓰이므로 3개 단위가 모두 같은 'Jeju' 필터로 조회하고, 같은
            // 장소가 3번 돌아온다. 중복을 안 걸러내면 슬롯 limit을 같은 장소가 3칸씩 차지해
            // 실제 고유 장소 수가 1/3로 줄어든다 — "그날 반경에 식당이 없음"의 직접 원인이다.
            VectorEnrichedInput input = jejuLongTripInput();
            givenBatchEmbeddings();
            given(placeVectorSearchService.search(any(VectorSearchRequest.class)))
                    .willReturn(createMockResults(30));

            List<PlaceCandidate> result = service.search(input);

            // 중복 제거 시 슬롯 limit(최대 20)까지 고유 장소가 채워져 20개가 나온다.
            // 제거하지 않으면 같은 장소 3벌이 상위 20칸을 차지해 고유 7개 수준으로 떨어진다.
            assertThat(result)
                    .as("고유 장소 30개가 있는데 중복에 밀려 한 자릿수로 줄면 안 된다")
                    .hasSizeGreaterThanOrEqualTo(15);
            assertThat(result.stream().map(PlaceCandidate::placeId).filter(java.util.Objects::nonNull))
                    .doesNotHaveDuplicates();
        }

        /** 제주 5일 — regions는 DB 실재값 'Jeju' 하나, regionAllocation은 세부 지명(실측 형태). */
        private VectorEnrichedInput jejuLongTripInput() {
            return new VectorEnrichedInput(
                    "제주", List.of("자연", "맛집"), List.of("음식", "관광", "숙소"),
                    "normal", "any", null,
                    LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5),
                    "제주 5일 여행", null,
                    "제주", "KR", List.of("Jeju"),
                    List.of("제주 맛집", "제주 관광"),
                    Map.of("1-2", List.of("제주시", "용담동"),
                           "3-4", List.of("한라산", "올레길"),
                           "5", List.of("성산일출봉")),
                    "MEDIUM", "여름", "제주 5일 컨텍스트",
                    null, null
            );
        }

        /** 서울+부산 5일 — regionAllocation 값이 regions 어휘 그대로라 분리 검색이 유지돼야 한다. */
        private VectorEnrichedInput multiCityInput() {
            return new VectorEnrichedInput(
                    "서울부산", List.of("맛집"), List.of("음식", "관광", "숙소"),
                    "normal", "any", BigDecimal.valueOf(1000000),
                    LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5),
                    "서울 부산 5일", null,
                    "서울부산", "KR", List.of("Seoul", "Busan"),
                    List.of("맛집"),
                    Map.of("1-3", List.of("Seoul"), "4-5", List.of("Busan")),
                    "MEDIUM", "여름", "서울 부산 컨텍스트",
                    null, null
            );
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

    @Nested
    @DisplayName("filterGeographicOutliers - 지역 중심점 기준 좌표 아웃라이어 제거")
    class FilterGeographicOutliersTests {

        /** region='Jeju'로 지정하고 주어진 좌표를 갖는 결과 생성 */
        private VectorSearchResult jeju(String name, double lat, double lng) {
            return result(name, "Jeju", lat, lng);
        }

        private VectorSearchResult result(String name, String region, double lat, double lng) {
            return new VectorSearchResult(
                    (long) name.hashCode(), name, name + " 주소", "restaurant",
                    List.of("태그"), region, "KR",
                    lat == 0 && lng == 0 ? null : BigDecimal.valueOf(lat),
                    lat == 0 && lng == 0 ? null : BigDecimal.valueOf(lng),
                    name + " 설명", BigDecimal.valueOf(4.0), 0.9
            );
        }

        @Test
        @DisplayName("제주 다수 + 오염 소수(명동/춘천) → 오염만 제거되고 제주 후보는 전부 보존")
        void jejuMajorityWithFewOutliers_removesOnlyOutliers() {
            List<VectorSearchResult> results = List.of(
                    jeju("제주공항", 33.5063, 126.4931),
                    jeju("성산일출봉", 33.4587, 126.9426),
                    jeju("협재해변", 33.3940, 126.2396),
                    jeju("한라산", 33.3617, 126.5292),
                    jeju("올레길", 33.2450, 126.5600),
                    jeju("공차 명동역점", 37.5609, 126.9861),   // 서울 명동 (~450km)
                    jeju("남문식당(춘천)", 37.7904, 127.5254)     // 강원 춘천
            );

            List<VectorSearchResult> filtered = service.filterGeographicOutliers(results);

            assertThat(filtered)
                    .extracting(VectorSearchResult::name)
                    .containsExactly("제주공항", "성산일출봉", "협재해변", "한라산", "올레길")
                    .doesNotContain("공차 명동역점", "남문식당(춘천)");
        }

        @Test
        @DisplayName("그룹 표본이 4개 미만이면 중심점 신뢰도 부족으로 필터를 건너뛰어 전부 보존")
        void smallGroup_skipsFilter() {
            List<VectorSearchResult> results = List.of(
                    jeju("제주공항", 33.5063, 126.4931),
                    jeju("성산일출봉", 33.4587, 126.9426),
                    jeju("공차 명동역점", 37.5609, 126.9861) // 오염이지만 표본<4라 skip
            );

            List<VectorSearchResult> filtered = service.filterGeographicOutliers(results);

            assertThat(filtered).hasSize(3);
        }

        @Test
        @DisplayName("좌표가 없는(null) 후보는 판단을 보류하고 보존한다")
        void nullCoordinate_isPreserved() {
            List<VectorSearchResult> results = List.of(
                    jeju("제주공항", 33.5063, 126.4931),
                    jeju("성산일출봉", 33.4587, 126.9426),
                    jeju("협재해변", 33.3940, 126.2396),
                    jeju("한라산", 33.3617, 126.5292),
                    jeju("좌표없음", 0, 0) // lat/lng null
            );

            List<VectorSearchResult> filtered = service.filterGeographicOutliers(results);

            assertThat(filtered).extracting(VectorSearchResult::name).contains("좌표없음");
        }

        @Test
        @DisplayName("서로 다른 region은 각자의 중심점으로 독립 판정한다 (다지역 여행)")
        void multipleRegions_filteredIndependently() {
            List<VectorSearchResult> results = new java.util.ArrayList<>();
            // Seoul 그룹 (정상 4개 + 제주 좌표 오염 1개)
            results.add(result("경복궁", "Seoul", 37.5796, 126.9770));
            results.add(result("명동", "Seoul", 37.5636, 126.9850));
            results.add(result("남산타워", "Seoul", 37.5512, 126.9882));
            results.add(result("홍대", "Seoul", 37.5563, 126.9236));
            results.add(result("제주오염", "Seoul", 33.4587, 126.9426)); // 서울 그룹에 제주 좌표
            // Busan 그룹 (정상 4개)
            results.add(result("해운대", "Busan", 35.1587, 129.1604));
            results.add(result("광안리", "Busan", 35.1532, 129.1187));
            results.add(result("감천문화마을", "Busan", 35.0975, 129.0108));
            results.add(result("자갈치시장", "Busan", 35.0966, 129.0306));

            List<VectorSearchResult> filtered = service.filterGeographicOutliers(results);

            assertThat(filtered).extracting(VectorSearchResult::name)
                    .doesNotContain("제주오염")
                    .contains("해운대", "광안리", "감천문화마을", "자갈치시장", "경복궁");
        }

        @Test
        @DisplayName("빈 목록/전부 정상이면 원본 그대로 반환")
        void emptyOrAllValid_returnsAll() {
            assertThat(service.filterGeographicOutliers(List.of())).isEmpty();

            List<VectorSearchResult> valid = List.of(
                    jeju("제주공항", 33.5063, 126.4931),
                    jeju("성산일출봉", 33.4587, 126.9426),
                    jeju("협재해변", 33.3940, 126.2396),
                    jeju("한라산", 33.3617, 126.5292)
            );
            assertThat(service.filterGeographicOutliers(valid)).hasSize(4);
        }
    }

    @Nested
    @DisplayName("인기도 랭킹 — 리뷰 수 미상의 중립값은 슬롯 커버리지에 따른다")
    class ProminenceRankingTests {

        private final SearchQueryBuilder.SearchSlot attractionSlot = new SearchQueryBuilder.SearchSlot(
                "attraction-nature", PlaceCategoryConstants.SearchRole.ATTRACTION, "제주 자연 경관", List.of(), List.of());

        /**
         * 관광지 결과 생성. sitelinks의 세 상태를 그대로 넘긴다 —
         * null=미매칭, 0=Wikidata에 항목 없음, 1+=언어판 수.
         */
        private VectorSearchResult attraction(String name, double similarity,
                                              double rating, Integer sitelinks) {
            return new VectorSearchResult(
                    (long) name.hashCode(), name, name + " 주소",
                    "Landmarks and Outdoors > Tourist Attraction", List.of(),
                    "Jeju", "KR", BigDecimal.valueOf(33.4), BigDecimal.valueOf(126.5),
                    name + " 설명", BigDecimal.valueOf(rating), null, sitelinks, similarity);
        }

        @Test
        @DisplayName("유사도가 비슷하면 명소가 '항목 없음'을 이긴다")
        void popularBeatsNotNotableAtSimilarSimilarity() {
            // 랭킹에서 계속 이기던 열안지오름은 Wikidata에 항목 자체가 없다(sitelinks 0).
            // 유사도가 같거나 더 높아도 명소가 위로 와야 한다.
            List<VectorSearchResult> raw = List.of(
                    attraction("열안지오름", 0.588, 4.0, 0),
                    attraction("만장굴", 0.588, 4.4, 11),
                    attraction("성산일출봉", 0.514, 4.3, 16),
                    attraction("한라산", 0.430, 4.4, 46)
            );

            List<String> accepted = service.applyThresholdAndRank(attractionSlot, raw, 4).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted.indexOf("만장굴")).isLessThan(accepted.indexOf("열안지오름"));
            assertThat(accepted.indexOf("성산일출봉")).isLessThan(accepted.indexOf("열안지오름"));
        }

        @Test
        @DisplayName("미매칭(null)과 항목없음(0)을 다르게 다룬다")
        void unmatchedIsNotTreatedAsNotNotable() {
            // null은 "아직 안 봤음"이라 판단 보류, 0은 "봤는데 없음"이라 무명 확정이다.
            // 둘을 같게 다루면 매칭 전 지역의 명소가 전부 바닥으로 깔린다.
            List<VectorSearchResult> raw = List.of(
                    attraction("아직안본곳", 0.50, 4.5, null),
                    attraction("확인된무명", 0.50, 4.5, 0)
            );

            List<String> accepted = service.applyThresholdAndRank(attractionSlot, raw, 2).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted.indexOf("아직안본곳")).isLessThan(accepted.indexOf("확인된무명"));
        }

        @Test
        @DisplayName("유사도는 여전히 주된 신호다 — 인기도가 주제 적합성을 뒤집지는 않는다")
        void similarityRemainsPrimarySignal() {
            // 인기도 가중치 0.15는 유사도 폭(제주 관광지 실측 0.25)의 일부일 뿐이다.
            // 압도적으로 더 적합한 장소가 인기도만으로 밀려나면 자연 테마에 수족관이 들어온다.
            List<VectorSearchResult> raw = List.of(
                    attraction("주제에 꼭 맞는 오름", 0.60, 4.0, null),
                    attraction("초인기 수족관", 0.41, 4.3, 14173)
            );

            List<String> accepted = service.applyThresholdAndRank(attractionSlot, raw, 1).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).containsExactly("주제에 꼭 맞는 오름");
        }

        @Test
        @DisplayName("커버리지가 0이면 리뷰 수 미상을 밀어내지 않는다 — 미동기화 지역 보호")
        void zeroCoverage_keepsUnknownReviewCount() {
            // 서울 관광지 리뷰 보유율은 1%다. 여기서 "미상"을 무명으로 깔면 그 지역 전체가
            // 유사도 순서를 잃는다. 전원이 미상이면 같은 값을 받아 순위가 유사도 그대로여야 한다.
            List<VectorSearchResult> raw = List.of(
                    attraction("경복궁", 0.52, 4.5, null),
                    attraction("북촌한옥마을", 0.50, 4.3, null),
                    attraction("무명공원", 0.48, 4.0, null)
            );

            List<String> accepted = service.applyThresholdAndRank(attractionSlot, raw, 3).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).containsExactly("경복궁", "북촌한옥마을", "무명공원");
        }

        @Test
        @DisplayName("리뷰 수가 압도적이어도 슬롯 최소 유사도 미만이면 채택되지 않는다")
        void popularButOffTopic_isStillDropped() {
            List<VectorSearchResult> raw = List.of(
                    attraction("주제에 맞는 해안", 0.45, 4.0, 100),
                    attraction("초인기 무관한 장소", 0.20, 4.9, 50000)
            );

            List<String> accepted = service.applyThresholdAndRank(attractionSlot, raw, 5).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).containsExactly("주제에 맞는 해안");
        }
    }
}
