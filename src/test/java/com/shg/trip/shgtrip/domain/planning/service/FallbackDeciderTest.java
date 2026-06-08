package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FallbackDecider 단위 테스트.
 * 총 후보 수 20개 기준으로 벡터 경로/Fallback 경로 분기를 검증한다.
 */
class FallbackDeciderTest {

    private FallbackDecider decider;

    @BeforeEach
    void setUp() {
        decider = new FallbackDecider();
    }

    private PlaceCandidate createCandidate(int index) {
        return new PlaceCandidate(
                index, (long) index, "Place " + index, "Address " + index,
                "RESTAURANT", List.of("맛집"), "Seoul", "KR",
                BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                "설명", BigDecimal.valueOf(4.5), 0.9
        );
    }

    private List<PlaceCandidate> createCandidates(int count) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            candidates.add(createCandidate(i));
        }
        return candidates;
    }

    @Nested
    @DisplayName("shouldFallback - 정상 분기 판단")
    class NormalBranchingTests {

        @Test
        @DisplayName("후보가 20개 이상이면 false 반환 (벡터 경로 사용)")
        void twentyOrMoreCandidates_returnsFalse() {
            List<PlaceCandidate> candidates = createCandidates(20);
            assertThat(decider.shouldFallback(candidates, List.of("음식", "관광"))).isFalse();
        }

        @Test
        @DisplayName("후보가 20개 초과이면 false 반환")
        void moreThanTwentyCandidates_returnsFalse() {
            List<PlaceCandidate> candidates = createCandidates(50);
            assertThat(decider.shouldFallback(candidates, List.of("음식"))).isFalse();
        }

        @Test
        @DisplayName("후보가 20개 미만이면 true 반환 (Fallback 경로)")
        void lessThanTwentyCandidates_returnsTrue() {
            List<PlaceCandidate> candidates = createCandidates(19);
            assertThat(decider.shouldFallback(candidates, List.of("음식", "관광"))).isTrue();
        }

        @Test
        @DisplayName("후보가 비어있으면 true 반환")
        void emptyCandidates_returnsTrue() {
            assertThat(decider.shouldFallback(List.of(), List.of("음식"))).isTrue();
        }
    }

    @Nested
    @DisplayName("shouldFallback - 엣지 케이스")
    class EdgeCaseTests {

        @Test
        @DisplayName("candidates가 null이면 true 반환")
        void nullCandidates_returnsTrue() {
            assertThat(decider.shouldFallback(null, List.of("음식"))).isTrue();
        }

        @Test
        @DisplayName("requestedCategories가 null이어도 총 수 기준으로 판단")
        void nullCategories_usesTotalCount() {
            List<PlaceCandidate> candidates = createCandidates(25);
            assertThat(decider.shouldFallback(candidates, null)).isFalse();
        }

        @Test
        @DisplayName("requestedCategories가 비어있어도 총 수 기준으로 판단")
        void emptyCategories_usesTotalCount() {
            List<PlaceCandidate> candidates = createCandidates(21);
            assertThat(decider.shouldFallback(candidates, List.of())).isFalse();
        }

        @Test
        @DisplayName("정확히 경계값(20개)은 false 반환")
        void exactBoundary_returnsFalse() {
            List<PlaceCandidate> candidates = createCandidates(20);
            assertThat(decider.shouldFallback(candidates, List.of("관광"))).isFalse();
        }

        @Test
        @DisplayName("경계값 바로 아래(19개)는 true 반환")
        void justBelowBoundary_returnsTrue() {
            List<PlaceCandidate> candidates = createCandidates(19);
            assertThat(decider.shouldFallback(candidates, List.of("관광"))).isTrue();
        }
    }
}
