package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceProminenceTest {

    @Test
    @DisplayName("알려진 정도는 언어판 수로 바꿔 큰 쪽을 쓰고, 없으면 언어판 수 그대로다")
    void effectiveSitelinks() {
        assertThat(PlaceProminence.effectiveSitelinks(null, null)).isNull();
        assertThat(PlaceProminence.effectiveSitelinks(12, null)).isEqualTo(12);
        assertThat(PlaceProminence.effectiveSitelinks(null, (short) 0)).isZero();
        assertThat(PlaceProminence.effectiveSitelinks(0, (short) 2)).isEqualTo(8);
        assertThat(PlaceProminence.effectiveSitelinks(30, (short) 1)).isEqualTo(30);
        assertThat(PlaceProminence.effectiveSitelinks(null, (short) 5)).isNull();
    }

    @Test
    @DisplayName("언어판이 없는 해외 식당은 알려진 정도 순으로 인기도가 매겨진다")
    void diningRankedByFame() {
        double michelin = PlaceProminence.of(dining((short) 2));
        double localFavorite = PlaceProminence.of(dining((short) 1));
        double ordinary = PlaceProminence.of(dining((short) 0));

        assertThat(michelin).isGreaterThan(localFavorite);
        assertThat(localFavorite).isGreaterThan(ordinary);
        assertThat(PlaceProminence.of(dining(null))).isEqualTo(PlaceProminence.of(null, null, null));
    }

    private static PlaceCandidate dining(Short fame) {
        return new PlaceCandidate(1, 1L, "銀座小十", "東京都中央区銀座", "Dining and Drinking > Restaurant", List.of(),
                "Tokyo", "JP", BigDecimal.ONE, BigDecimal.ONE, null, null, 0.5, null, null, null, null, false, null,
                null, null, null, "MEAL", "SEAFOOD", false, null, null, "긴자 고쥬", fame);
    }
}
