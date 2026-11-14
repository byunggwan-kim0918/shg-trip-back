package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateLookupTest {

    private static final String CHINESE = "Dining and Drinking > Restaurant > Asian Restaurant > Chinese Restaurant";

    @Test
    @DisplayName("같은 상호의 다른 지점이 후보에 있으면 placeId로 고른다")
    void placeIdBeatsName() {
        PlaceCandidate seoul = candidate(1, 1808927L, "짬뽕지존", "Seoul", null);
        PlaceCandidate busan = candidate(2, 1706233L, "짬뽕지존", "Busan", "SEAFOOD");
        CandidateLookup lookup = new CandidateLookup(List.of(seoul, busan));

        assertThat(lookup.of(step("짬뽕지존", 1706233L))).isSameAs(busan);
        assertThat(lookup.mealKind(step("짬뽕지존", 1706233L))).isEqualTo("chinese_noodle");
    }

    @Test
    @DisplayName("placeId가 없거나 후보에 없으면 이름으로 찾는다")
    void fallsBackToName() {
        PlaceCandidate busan = candidate(1, 1706233L, "짬뽕지존", "Busan", "SEAFOOD");
        CandidateLookup lookup = new CandidateLookup(List.of(busan));

        assertThat(lookup.of(step("짬뽕지존", null))).isSameAs(busan);
        assertThat(lookup.of(step("짬뽕지존", 999L))).isSameAs(busan);
        assertThat(lookup.of(step("다른 가게", null))).isNull();
    }

    private static StepData step(String name, Long placeId) {
        return new StepData(1, 1, "12:00", "13:00",
                new PlaceData(name, null, CHINESE, "Busan", "KR", null, null, placeId, null),
                List.of(), null, null, null, null, null, null);
    }

    private static PlaceCandidate candidate(int index, Long placeId, String name, String region, String cuisine) {
        return new PlaceCandidate(index, placeId, name, null, CHINESE, List.of(), region, "KR",
                BigDecimal.valueOf(35.1), BigDecimal.valueOf(129.0), null, null, 0.9, null, null,
                null, 70, false, null, null, null, null, "MEAL", cuisine, false, null);
    }
}
