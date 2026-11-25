package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftDishWordsTest {

    @Test
    @DisplayName("음식의 다른 표기는 앞 두 글자로 자르지 않고 통째로 찾는다")
    void aliasesAreSearchedWhole() {
        assertThat(DraftItineraryPlanner.dishWords("멘타이코", List.of("めんたいこ", "明太子")))
                .containsExactly("멘타이코", "멘타", "めんたいこ", "明太子");
    }

    @Test
    @DisplayName("다른 표기가 없으면 음식 이름 낱말만 찾는다")
    void withoutAliases() {
        assertThat(DraftItineraryPlanner.dishWords("막창구이 막창", List.of())).containsExactly("막창구이", "막창");
    }
}
