package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftPlaceResolverTest {

    @Test
    @DisplayName("도시 이름을 붙인 채, 뗀 채, 꾸밈말을 뗀 채, 마지막 낱말 순으로 찾는다")
    void queryVariantsOrder() {
        assertThat(DraftPlaceResolver.queryVariants("수원화성 화서문", List.of("수원")))
                .containsExactly("수원화성화서문", "화성화서문", "화서문");
        assertThat(DraftPlaceResolver.queryVariants("수성못 야경 산책", List.of("대구")))
                .containsSequence("수성못야경산책", "수성못야경", "수성못");
        assertThat(DraftPlaceResolver.queryVariants("전주남부시장 야시장", List.of("전주")))
                .contains("전주남부시장", "남부시장");
    }

    @Test
    @DisplayName("첫 낱말만으로는 찾지 않는다")
    void neverFallsBackToFirstWord() {
        assertThat(DraftPlaceResolver.queryVariants("동명동 카페거리", List.of("광주"))).doesNotContain("동명동");
    }

    @Test
    @DisplayName("질의 가운데 꾸밈말을 뺀 이름은 같은 곳으로 본다")
    void dropsInfix() {
        assertThat(DraftPlaceResolver.dropsInfix("대인시장", "대인예술시장")).isTrue();
        assertThat(DraftPlaceResolver.dropsInfix("대인시장", "대인시장")).isFalse();
        assertThat(DraftPlaceResolver.dropsInfix("남부시장", "대인예술시장")).isFalse();
        assertThat(DraftPlaceResolver.dropsInfix("대인장", "대인예술시장")).isFalse();
    }

    @Test
    @DisplayName("같은 이름 > 앞부분·담음 > 질의가 이름을 담음 순이다")
    void tiers() {
        assertThat(DraftPlaceResolver.tier("해운대해수욕장", "해운대해수욕장")).isZero();
        assertThat(DraftPlaceResolver.tier("감천문화마을희망의메시지", "감천문화마을")).isEqualTo(1);
        assertThat(DraftPlaceResolver.tier("부산자갈치시장", "자갈치시장")).isEqualTo(2);
        assertThat(DraftPlaceResolver.tier("남산", "남산서울타워")).isEqualTo(4);
    }
}
