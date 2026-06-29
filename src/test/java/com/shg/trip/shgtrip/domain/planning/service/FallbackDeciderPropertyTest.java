package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FallbackDecider 프로퍼티 기반 테스트.
 * 카테고리별 최솟값 기준으로 벡터 경로/Fallback 분기를 검증한다.
 * 3일 여행: accommodation>=1, restaurant>=6, attraction>=3, total>=15
 */
class FallbackDeciderPropertyTest {

    private final FallbackDecider decider = new FallbackDecider();

    /**
     * 모든 카테고리 요구사항을 충족하는 충분한 후보는 Fallback하지 않는다.
     */
    @Property(tries = 50)
    void sufficientAndBalanced_shouldNotFallback(
            @ForAll("sufficientBalancedCandidates") List<PlaceCandidate> candidates
    ) {
        boolean result = decider.shouldFallback(candidates, 3L);
        assertThat(result).isFalse();
    }

    /**
     * 카테고리 중 하나라도 부족하면 Fallback한다.
     */
    @Property(tries = 50)
    void insufficientCategory_shouldFallback(
            @ForAll("imbalancedCandidates") List<PlaceCandidate> candidates
    ) {
        boolean result = decider.shouldFallback(candidates, 3L);
        assertThat(result).isTrue();
    }

    /**
     * 동일 입력에 대해 결과는 항상 결정론적이다.
     */
    @Property(tries = 50)
    void resultIsDeterministicAndConsistent(
            @ForAll("anyCandidates") List<PlaceCandidate> candidates
    ) {
        long days = 3L;

        boolean result1 = decider.shouldFallback(candidates, days);
        boolean result2 = decider.shouldFallback(candidates, days);

        assertThat(result1).isEqualTo(result2);
        // Expected: lodging>=1, restaurant keyword>=6, landmarks keyword>=3, total>=15
        boolean expectedFallback = candidates.isEmpty() ||
                candidates.stream().filter(c -> c.category() != null && c.category().toLowerCase().contains("lodging")).count() < 1 ||
                candidates.stream().filter(c -> c.category() != null && c.category().toLowerCase().contains("restaurant")).count() < 6 ||
                candidates.stream().filter(c -> c.category() != null && c.category().toLowerCase().contains("landmarks")).count() < 3 ||
                candidates.size() < 15;
        assertThat(result1).isEqualTo(expectedFallback);
    }

    @Provide
    Arbitrary<List<PlaceCandidate>> sufficientBalancedCandidates() {
        return Arbitraries.just(createSufficientCandidates());
    }

    @Provide
    Arbitrary<List<PlaceCandidate>> imbalancedCandidates() {
        return Arbitraries.just(createImbalancedCandidates());
    }

    @Provide
    Arbitrary<List<PlaceCandidate>> anyCandidates() {
        return Arbitraries.integers().between(0, 40).map(this::createMixedCandidates);
    }

    private List<PlaceCandidate> createCategoryGroup(int startIdx, int endIdx, String category) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (int i = startIdx; i <= endIdx; i++) {
            candidates.add(new PlaceCandidate(
                    i, (long) i, "장소_" + i, "주소_" + i,
                    category, List.of(category), "Seoul", "KR",
                    BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                    "설명", BigDecimal.valueOf(4.5), 0.9
            ));
        }
        return candidates;
    }

    // 실제 DB Foursquare 카테고리 경로
    private static final String CAT_ATTRACTION = "Landmarks and Outdoors > Park";
    private static final String CAT_RESTAURANT = "Dining and Drinking > Restaurant > Korean Restaurant";
    private static final String CAT_LODGING    = "Travel and Transportation > Lodging > Hotel";

    private List<PlaceCandidate> createSufficientCandidates() {
        List<PlaceCandidate> result = new ArrayList<>();
        result.addAll(createCategoryGroup(1, 5, CAT_ATTRACTION));
        result.addAll(createCategoryGroup(6, 13, CAT_RESTAURANT));
        result.addAll(createCategoryGroup(14, 15, CAT_LODGING));
        return result;
    }

    private List<PlaceCandidate> createImbalancedCandidates() {
        List<PlaceCandidate> result = new ArrayList<>();
        result.addAll(createCategoryGroup(1, 5, CAT_ATTRACTION));
        result.addAll(createCategoryGroup(6, 9, CAT_RESTAURANT)); // 4개만 (6개 필요)
        // 숙소 없음
        return result;
    }

    private List<PlaceCandidate> createMixedCandidates(int count) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        int idx = 1;
        int perCategory = Math.max(1, count / 3);
        for (int i = 0; i < perCategory && idx <= count; i++, idx++) {
            candidates.add(new PlaceCandidate(
                    idx, (long) idx, "장소_" + idx, "주소_" + idx,
                    CAT_ATTRACTION, List.of(CAT_ATTRACTION), "Seoul", "KR",
                    BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                    "설명", BigDecimal.valueOf(4.5), 0.9
            ));
        }
        for (int i = 0; i < perCategory && idx <= count; i++, idx++) {
            candidates.add(new PlaceCandidate(
                    idx, (long) idx, "장소_" + idx, "주소_" + idx,
                    CAT_RESTAURANT, List.of(CAT_RESTAURANT), "Seoul", "KR",
                    BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                    "설명", BigDecimal.valueOf(4.5), 0.9
            ));
        }
        for (int i = 0; i < perCategory && idx <= count; i++, idx++) {
            candidates.add(new PlaceCandidate(
                    idx, (long) idx, "장소_" + idx, "주소_" + idx,
                    CAT_LODGING, List.of(CAT_LODGING), "Seoul", "KR",
                    BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                    "설명", BigDecimal.valueOf(4.5), 0.9
            ));
        }
        while (idx <= count) {
            candidates.add(new PlaceCandidate(
                    idx, (long) idx, "장소_" + idx, "주소_" + idx,
                    CAT_RESTAURANT, List.of(CAT_RESTAURANT), "Seoul", "KR",
                    BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                    "설명", BigDecimal.valueOf(4.5), 0.9
            ));
            idx++;
        }
        return candidates;
    }
}
