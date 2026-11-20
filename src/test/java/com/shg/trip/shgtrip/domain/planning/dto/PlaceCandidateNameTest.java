package com.shg.trip.shgtrip.domain.planning.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceCandidateNameTest {

    private static PlaceCandidate candidate(String name, String nameKo) {
        return new PlaceCandidate(1, 1L, name, "", "Spiritual Center > Buddhist Temple", List.of(), "Tokyo", "JP",
                BigDecimal.ONE, BigDecimal.ONE, null, null, 0.5, null, null, null, null, false, null, null, null,
                null, null, null, false, null, null, nameKo);
    }

    @Test
    @DisplayName("한국어 이름이 있으면 화면은 한국어 이름이다")
    void displayNames() {
        PlaceCandidate sensoji = candidate("浅草寺", "센소지");
        assertThat(sensoji.displayName()).isEqualTo("센소지");

        PlaceCandidate korean = candidate("성산일출봉", null);
        assertThat(korean.displayName()).isEqualTo("성산일출봉");
        assertThat(candidate("浅草寺", " ").displayName()).isEqualTo("浅草寺");
    }

    @Test
    @DisplayName("사본을 만들어도 한국어 이름이 남는다")
    void copiesKeepNameKo() {
        PlaceCandidate c = candidate("浅草寺", "센소지");
        assertThat(c.reindexed(5).nameKo()).isEqualTo("센소지");
        assertThat(c.asUserSelected().nameKo()).isEqualTo("센소지");
        assertThat(c.withOpeningHours("월요일: 휴무").nameKo()).isEqualTo("센소지");
    }
}
