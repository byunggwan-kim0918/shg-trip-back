package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FallbackDecider 단위 테스트.
 * 실제 DB 카테고리는 Foursquare 계층 경로 형식 (예: "Dining and Drinking > Restaurant > ...")
 */
class FallbackDeciderTest {

    // 실제 DB 카테고리 값 (Foursquare 경로)
    private static final String CAT_RESTAURANT = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String CAT_CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House > Coffee Shop";
    private static final String CAT_LODGING = "Travel and Transportation > Lodging > Hotel";
    private static final String CAT_ATTRACTION = "Landmarks and Outdoors > Park";

    private FallbackDecider decider;

    @BeforeEach
    void setUp() {
        decider = new FallbackDecider();
    }

    private PlaceCandidate createCandidate(int index, String category) {
        return new PlaceCandidate(
                index, (long) index, "Place " + index, "Address " + index,
                category, List.of(category), "Seoul", "KR",
                BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                "설명", BigDecimal.valueOf(4.5), 0.9
        );
    }

    private List<PlaceCandidate> createCandidates(int startIndex, String category, int count) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (int i = startIndex; i < startIndex + count; i++) {
            candidates.add(createCandidate(i, category));
        }
        return candidates;
    }

    @Nested
    @DisplayName("shouldFallback - 정상 분기 판단")
    class NormalBranchingTests {

        @Test
        @DisplayName("3일 여행 - 실제 DB 카테고리로 모든 조건 충족 → false")
        void allRequirementsMetForThreeDays_returnsFalse() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.addAll(createCandidates(1, CAT_ATTRACTION, 5));
            candidates.addAll(createCandidates(6, CAT_RESTAURANT, 8));
            candidates.addAll(createCandidates(14, CAT_LODGING, 3));
            assertThat(decider.shouldFallback(candidates, 3)).isFalse();
        }

        @Test
        @DisplayName("5일 여행 - 실제 DB 카테고리로 모든 조건 충족 → false")
        void allRequirementsMetForFiveDays_returnsFalse() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.addAll(createCandidates(1, CAT_ATTRACTION, 10));
            candidates.addAll(createCandidates(11, CAT_RESTAURANT, 12));
            candidates.addAll(createCandidates(23, CAT_LODGING, 3));
            assertThat(decider.shouldFallback(candidates, 5)).isFalse();
        }

        @Test
        @DisplayName("후보가 비어있으면 true 반환")
        void emptyCandidates_returnsTrue() {
            assertThat(decider.shouldFallback(List.of(), 3)).isTrue();
        }
    }

    @Nested
    @DisplayName("assess - 후보 풀 품질 3단계 판정")
    class AssessTests {

        @Test
        @DisplayName("유효 관광지가 days×2 이상이면 SUFFICIENT")
        void enoughValidAttractions_returnsSufficient() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.addAll(createCandidates(1, CAT_ATTRACTION, 6)); // 공원 6 ≥ 3일×2
            candidates.addAll(createCandidates(7, CAT_RESTAURANT, 8));
            candidates.addAll(createCandidates(15, CAT_LODGING, 3));
            assertThat(decider.assess(candidates, 3)).isEqualTo(FallbackDecider.PoolQuality.SUFFICIENT);
        }

        @Test
        @DisplayName("attraction 수량은 충족해도 골프장/마구간/우물뿐이면 COMPACT")
        void onlyLowValueAttractions_returnsCompact() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            // 수량으로는 shouldFallback 통과(≥ days), 그러나 전부 저가치 시설
            candidates.addAll(createCandidates(1, "Sports and Recreation > Golf > Golf Course", 2));
            candidates.addAll(createCandidates(3, "Landmarks and Outdoors > Stable", 2));
            candidates.addAll(createCandidates(5, "Landmarks and Outdoors > Well", 2));
            candidates.addAll(createCandidates(7, CAT_RESTAURANT, 8));
            candidates.addAll(createCandidates(15, CAT_LODGING, 3));
            assertThat(decider.assess(candidates, 3)).isEqualTo(FallbackDecider.PoolQuality.COMPACT);
        }

        @Test
        @DisplayName("구조적 최소치 미달이면 FALLBACK")
        void structuralMinimumNotMet_returnsFallback() {
            assertThat(decider.assess(List.of(), 3)).isEqualTo(FallbackDecider.PoolQuality.FALLBACK);
        }
    }

    @Nested
    @DisplayName("shouldFallback - 엣지 케이스")
    class EdgeCaseTests {

        @Test
        @DisplayName("candidates가 null이면 true 반환")
        void nullCandidates_returnsTrue() {
            assertThat(decider.shouldFallback(null, 3)).isTrue();
        }

        @Test
        @DisplayName("식당 부족 (days×2 미만) → true")
        void insufficientRestaurants_returnsTrue() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.addAll(createCandidates(1, CAT_RESTAURANT, 5));  // 3일 기준 6개 필요
            candidates.addAll(createCandidates(6, CAT_ATTRACTION, 5));
            candidates.addAll(createCandidates(11, CAT_LODGING, 2));
            assertThat(decider.shouldFallback(candidates, 3)).isTrue();
        }

        @Test
        @DisplayName("관광지 부족 (days 미만) → true")
        void insufficientAttractions_returnsTrue() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.addAll(createCandidates(1, CAT_ATTRACTION, 2));  // 3일 기준 3개 필요
            candidates.addAll(createCandidates(3, CAT_RESTAURANT, 8));
            candidates.addAll(createCandidates(11, CAT_LODGING, 2));
            assertThat(decider.shouldFallback(candidates, 3)).isTrue();
        }

        @Test
        @DisplayName("숙소 없음 → true")
        void noAccommodation_returnsTrue() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.addAll(createCandidates(1, CAT_ATTRACTION, 5));
            candidates.addAll(createCandidates(6, CAT_RESTAURANT, 10));
            assertThat(decider.shouldFallback(candidates, 3)).isTrue();
        }

        @Test
        @DisplayName("총 후보 15개 미만 → true")
        void belowMinTotal_returnsTrue() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.addAll(createCandidates(1, CAT_ATTRACTION, 4));
            candidates.addAll(createCandidates(5, CAT_RESTAURANT, 7));
            candidates.addAll(createCandidates(12, CAT_LODGING, 2));  // 총 13개
            assertThat(decider.shouldFallback(candidates, 3)).isTrue();
        }
    }
}
