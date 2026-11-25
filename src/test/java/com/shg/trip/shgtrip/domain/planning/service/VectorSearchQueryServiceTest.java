package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

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

    // NOTE: calculatePerCategoryLimit은 구현되지 않은 메서드 (내부 로직만 사용)
    // 테스트는 카테고리별 벡터 검색의 통합 테스트로 대체

    @Test
    @DisplayName("근처 검색의 관광 질의는 카테고리·테마별 문구가 아니라 대표 관광 문구를 쓴다")
    @SuppressWarnings("unchecked")
    void searchNear_usesRepresentativeAttractionQuery() {
        VectorEnrichedInput input = inputWith(List.of("food", "ocean"), List.of("market", "attraction"));
        String representative = SearchQueryBuilder.buildSearchSlots(input).stream()
                .filter(sl -> "attraction".equals(sl.key())).findFirst().orElseThrow().queryText();
        given(embeddingService.embedBatch(anyList())).willReturn(List.of(MOCK_VECTOR));
        given(placeVectorSearchService.search(any(VectorSearchRequest.class))).willReturn(List.of());

        service.nearSearch(input).search(PlaceCategoryConstants.SearchRole.ATTRACTION,
                new double[]{35.66, 139.70}, 10.0, 5, 1);

        ArgumentCaptor<List<String>> queries = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embedBatch(queries.capture());
        assertThat(queries.getValue()).containsExactly(representative);
    }

    @Test
    @DisplayName("정한 지점 둘레 검색은 여행지 지역으로 거르지 않고, 보통 근처 검색은 여행지 지역으로 거른다")
    void searchAroundDropsRegionFilter() {
        VectorEnrichedInput input = inputWith(List.of("food"), List.of("attraction"));
        given(embeddingService.embedBatch(anyList())).willReturn(List.of(MOCK_VECTOR));
        given(placeVectorSearchService.search(any(VectorSearchRequest.class))).willReturn(List.of());
        VectorSearchQueryService.NearSearch near = service.nearSearch(input);
        double[] haneda = {35.5494, 139.7798};

        near.search(PlaceCategoryConstants.SearchRole.ACCOMMODATION, haneda, 8.0, 5, 1);
        near.searchAround(PlaceCategoryConstants.SearchRole.ACCOMMODATION, haneda, 8.0, 5, 1);

        verify(placeVectorSearchService, org.mockito.Mockito.times(2)).search(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(VectorSearchRequest::regions)
                .containsExactly(List.of("시부야"), null);
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

    @Nested
    @DisplayName("세부유형 다양화 — 식사 후보가 한 종류로 쏠리지 않게 윈도를 고른다")
    class SubTypeDiversityTests {

        private final SearchQueryBuilder.SearchSlot restaurantSlot = new SearchQueryBuilder.SearchSlot(
                "restaurant", PlaceCategoryConstants.SearchRole.RESTAURANT, "제주 맛집", List.of(), List.of());

        private final SearchQueryBuilder.SearchSlot attractionSlot = new SearchQueryBuilder.SearchSlot(
                "attraction", PlaceCategoryConstants.SearchRole.ATTRACTION, "제주 관광", List.of(), List.of());

        private VectorSearchResult dining(String name, double similarity) {
            return new VectorSearchResult(
                    (long) name.hashCode(), name, name + " 주소",
                    "Dining and Drinking > Restaurant", List.of(),
                    "Jeju", "KR", BigDecimal.valueOf(33.4), BigDecimal.valueOf(126.5),
                    name + " 설명", BigDecimal.valueOf(4.3), null, null, similarity);
        }

        /** 유사도 내림차순으로 돼지고기 n곳 + 다른 종류를 섞은 원본 결과. */
        private List<VectorSearchResult> porkHeavyRaw() {
            List<VectorSearchResult> raw = new java.util.ArrayList<>();
            double sim = 0.90;
            for (int i = 1; i <= 10; i++) raw.add(dining("흑돼지구이 " + i + "호점", sim -= 0.01));
            for (int i = 1; i <= 4; i++) raw.add(dining("물회 해산물집 " + i, sim -= 0.01));
            for (int i = 1; i <= 4; i++) raw.add(dining("고기국수 " + i, sim -= 0.01));
            return raw;
        }

        private long countContaining(List<String> names, String needle) {
            return names.stream().filter(n -> n.contains(needle)).count();
        }

        @Test
        @DisplayName("유사도 상위가 전부 같은 종류여도 유형당 상한만큼만 채택한다")
        void capsDominantCuisine() {
            // 실측: 제주 restaurant 슬롯 상위 16곳이 pork 12 / seafood 2 / noodle 1 / 미분류 1이었다.
            // 3박4일 8끼에 비(非)돼지 후보가 4곳이면 조립 단계 유형 상한(3회)을 만족할 수 없다.
            // 다른 종류로 limit을 채울 수 있을 때는 상한이 끝까지 지켜져야 한다.
            List<VectorSearchResult> raw = new java.util.ArrayList<>();
            double sim = 0.90;
            for (int i = 1; i <= 10; i++) raw.add(dining("흑돼지구이 " + i + "호점", sim -= 0.01));
            for (int i = 1; i <= 4; i++) raw.add(dining("물회 해산물집 " + i, sim -= 0.01));
            for (int i = 1; i <= 4; i++) raw.add(dining("고기국수 " + i, sim -= 0.01));
            for (int i = 1; i <= 4; i++) raw.add(dining("한우 구이 " + i, sim -= 0.01));
            for (int i = 1; i <= 4; i++) raw.add(dining("치킨 " + i + "번가", sim -= 0.01));

            List<String> accepted = service.applyThresholdAndRank(restaurantSlot, raw, 12).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).hasSize(12);
            // limit 12 → 유형당 상한 3. 5개 유형 × 3 = 15 ≥ 12이므로 되돌림이 일어나지 않는다.
            assertThat(countContaining(accepted, "흑돼지")).isEqualTo(3);
            assertThat(countContaining(accepted, "물회")).isEqualTo(3);
            assertThat(countContaining(accepted, "고기국수")).isEqualTo(3);
        }

        @Test
        @DisplayName("같은 묶음 안에서는 점수가 낮은 다른 요리에도 자리를 준다")
        void spreadsDishesWithinGroup() {
            // 조개구이가 모두 횟집보다 점수가 높아도 해산물 자리에 횟집이 하나는 들어와야 한다.
            List<VectorSearchResult> raw = new java.util.ArrayList<>();
            double sim = 0.90;
            for (int i = 1; i <= 6; i++) raw.add(dining("조개구이 " + i + "호", sim -= 0.01));
            for (int i = 1; i <= 6; i++) raw.add(dining("광안횟집 " + i, sim -= 0.01));
            for (int i = 1; i <= 6; i++) raw.add(dining("흑돼지구이 " + i + "호점", sim -= 0.01));
            for (int i = 1; i <= 6; i++) raw.add(dining("치킨 " + i + "번가", sim -= 0.01));

            List<String> accepted = service.applyThresholdAndRank(restaurantSlot, raw, 12).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(countContaining(accepted, "횟집")).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("요리 상한에 밀린 후보는 같은 묶음에 자리가 남으면 점수 순 되돌림보다 먼저 받는다")
        void dishCapYieldsToGroupRoomBeforeBackfill() {
            // limit 12 → 묶음 상한 3, 요리 상한 1. 조개구이가 점수 최하위라도 해산물 자리 3개는 조개구이가 채워야 한다.
            List<VectorSearchResult> raw = new java.util.ArrayList<>();
            double sim = 0.90;
            for (int i = 1; i <= 6; i++) raw.add(dining("흑돼지구이 " + i + "호점", sim -= 0.01));
            for (int i = 1; i <= 6; i++) raw.add(dining("치킨 " + i + "번가", sim -= 0.01));
            for (int i = 1; i <= 6; i++) raw.add(dining("조개구이 " + i + "호", sim -= 0.01));

            List<String> accepted = service.applyThresholdAndRank(restaurantSlot, raw, 12).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).hasSize(12);
            assertThat(countContaining(accepted, "조개구이")).isEqualTo(3);
        }

        @Test
        @DisplayName("다양화해도 후보 수는 줄지 않는다 — 상한 초과분을 점수 순으로 되돌린다")
        void neverReturnsFewerThanLimit() {
            // 한 종류뿐인 소도시에서 다양화가 후보를 말려버리면 fallback 경로로 떨어진다.
            List<VectorSearchResult> onlyPork = new java.util.ArrayList<>();
            double sim = 0.90;
            for (int i = 1; i <= 10; i++) onlyPork.add(dining("흑돼지구이 " + i + "호점", sim -= 0.01));

            List<String> accepted = service.applyThresholdAndRank(restaurantSlot, onlyPork, 8).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).hasSize(8);
        }

        @Test
        @DisplayName("상한을 채운 뒤 되돌리는 순서는 점수 순이다")
        void backfillFollowsScoreOrder() {
            // limit 8 → 유형당 상한 2. 돼지 2 + 물회 2 + 국수 2 = 6을 먼저 채우고,
            // 남은 2자리는 밀어둔 후보 중 점수가 가장 높은 돼지 3·4호점이 받아야 한다.
            List<String> accepted = service.applyThresholdAndRank(restaurantSlot, porkHeavyRaw(), 8).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).hasSize(8);
            assertThat(accepted).contains("흑돼지구이 3호점", "흑돼지구이 4호점");
            assertThat(accepted).doesNotContain("흑돼지구이 5호점");
        }

        @Test
        @DisplayName("관광 슬롯은 다양화 대상이 아니다 — 점수 상위를 그대로 자른다")
        void attractionSlotIsUntouched() {
            // 관광은 커버리지 규칙(viewpoint·theme 슬롯 분리)이 이미 종류를 나눠 담당한다.
            // 여기에 유형 상한을 겹치면 테마 적합도 상위가 밀려난다.
            List<String> accepted = service.applyThresholdAndRank(attractionSlot, porkHeavyRaw(), 4).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).containsExactly(
                    "흑돼지구이 1호점", "흑돼지구이 2호점", "흑돼지구이 3호점", "흑돼지구이 4호점");
        }

        private VectorSearchResult dining(String name, String cuisine, double similarity) {
            return new VectorSearchResult((long) name.hashCode(), name, name + " 주소",
                    "Dining and Drinking > Restaurant", List.of(), "Busan", "KR", BigDecimal.valueOf(35.1),
                    BigDecimal.valueOf(129.0), null, BigDecimal.valueOf(4.3), null, null, similarity, "MEAL", cuisine,
                    null, null);
        }

        @Test
        @DisplayName("못 먹는 음식은 자르기 전에 빼서 다른 식당으로 자리를 채우고, 종류를 모르는 식당은 뒤로 미룬다")
        void restrictionsRefillWindow() {
            List<VectorSearchResult> raw = new java.util.ArrayList<>();
            double sim = 0.90;
            for (int i = 1; i <= 6; i++) raw.add(dining("광안횟집 " + i, "SEAFOOD", sim -= 0.01));
            raw.add(dining("이름없는식당", null, sim -= 0.01));
            raw.add(dining("밀면집", "NOODLE", sim -= 0.01));
            raw.add(dining("돼지국밥집", "SOUP", sim -= 0.01));
            raw.add(dining("흑돼지집", "MEAT", sim -= 0.01));

            List<String> restricted = service.applyThresholdAndRank(restaurantSlot, raw, 3,
                    PlaceRestrictions.of(List.of(PlaceRestriction.SEAFOOD)))
                    .stream().map(s -> s.result().name()).toList();
            List<String> plain = service.applyThresholdAndRank(restaurantSlot, raw, 3).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(restricted).containsExactlyInAnyOrder("밀면집", "돼지국밥집", "흑돼지집");
            assertThat(plain).anyMatch(n -> n.startsWith("광안횟집"));
        }

        @Test
        @DisplayName("출처별 같은 가게 행은 요리 분류가 한쪽에만 있어도 이름이 같으면 함께 뺀다")
        void restrictionsDropSameNameRowWithoutCuisine() {
            List<VectorSearchResult> raw = List.of(dining("할매집", "SEAFOOD", 0.90), dining("할매집 ", null, 0.89),
                    dining("밀면집", "NOODLE", 0.88));

            List<String> accepted = service.applyThresholdAndRank(restaurantSlot, raw, 5,
                    PlaceRestrictions.of(List.of(PlaceRestriction.SEAFOOD)))
                    .stream().map(s -> s.result().name()).toList();

            assertThat(accepted).containsExactly("밀면집");
        }

        @Test
        @DisplayName("임계값 아래 행이나 다른 슬롯에서 걸린 행과 이름이 같은 행도 뺀다")
        void restrictionsDropTwinOfRowsOutsideThisWindow() {
            List<VectorSearchResult> raw = List.of(dining("할매집", "SEAFOOD", 0.10), dining("할매집", null, 0.89),
                    dining("밀면집", "NOODLE", 0.88), dining("소문난집", null, 0.87));
            PlaceRestrictions seafood = PlaceRestrictions.of(List.of(PlaceRestriction.SEAFOOD));
            Set<String> otherSlot = seafood.twinKeys(List.of(dining("소문난집", "SEAFOOD", 0.90)));

            List<String> ownWindow = service.applyThresholdAndRank(restaurantSlot, raw, 5, seafood)
                    .stream().map(s -> s.result().name()).toList();
            List<String> shared = service.applyThresholdAndRank(restaurantSlot, raw, 5, seafood, otherSlot)
                    .stream().map(s -> s.result().name()).toList();

            assertThat(ownWindow).containsExactlyInAnyOrder("밀면집", "소문난집");
            assertThat(shared).contains("밀면집").doesNotContain("소문난집");
        }

        @Test
        @DisplayName("후보가 limit 이하면 다양화가 개입하지 않는다")
        void noOpWhenPoolSmallerThanLimit() {
            List<String> accepted = service.applyThresholdAndRank(restaurantSlot, porkHeavyRaw(), 50).stream()
                    .map(s -> s.result().name()).toList();

            assertThat(accepted).hasSize(18);
        }
    }

}
