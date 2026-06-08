package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FallbackDecider 프로퍼티 기반 테스트.
 * 총 후보 수 20개 기준으로 벡터 경로/Fallback 분기를 검증한다.
 */
class FallbackDeciderPropertyTest {

    private final FallbackDecider decider = new FallbackDecider();

    /**
     * 총 후보 수가 20개 이상이면 벡터 경로를 사용한다 (shouldFallback = false).
     */
    @Property(tries = 100)
    void twentyOrMoreCandidates_shouldNotFallback(
            @ForAll("sufficientCandidates") List<PlaceCandidate> candidates
    ) {
        boolean result = decider.shouldFallback(candidates, List.of("음식", "관광"));
        assertThat(result).isFalse();
    }

    /**
     * 총 후보 수가 20개 미만이면 Fallback 경로로 분기한다 (shouldFallback = true).
     */
    @Property(tries = 100)
    void lessThanTwentyCandidates_shouldFallback(
            @ForAll("insufficientCandidates") List<PlaceCandidate> candidates
    ) {
        boolean result = decider.shouldFallback(candidates, List.of("음식", "관광"));
        assertThat(result).isTrue();
    }

    /**
     * 동일 입력에 대해 결과는 항상 결정론적이며, 총 수에 의해서만 결정된다.
     */
    @Property(tries = 100)
    void resultIsDeterministicAndConsistent(
            @ForAll("anyCandidates") List<PlaceCandidate> candidates
    ) {
        List<String> categories = List.of("음식", "관광", "쇼핑");

        boolean result1 = decider.shouldFallback(candidates, categories);
        boolean result2 = decider.shouldFallback(candidates, categories);

        assertThat(result1).isEqualTo(result2);
        assertThat(result1).isEqualTo(candidates.size() < 20);
    }

    @Provide
    Arbitrary<List<PlaceCandidate>> sufficientCandidates() {
        return Arbitraries.integers().between(20, 80).map(this::createCandidates);
    }

    @Provide
    Arbitrary<List<PlaceCandidate>> insufficientCandidates() {
        return Arbitraries.integers().between(0, 19).map(this::createCandidates);
    }

    @Provide
    Arbitrary<List<PlaceCandidate>> anyCandidates() {
        return Arbitraries.integers().between(0, 80).map(this::createCandidates);
    }

    private List<PlaceCandidate> createCandidates(int count) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            candidates.add(new PlaceCandidate(
                    i, (long) i, "장소_" + i, "주소_" + i,
                    "RESTAURANT", List.of("맛집"), "Seoul", "KR",
                    BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0),
                    "설명", BigDecimal.valueOf(4.5), 0.9
            ));
        }
        return candidates;
    }
}
