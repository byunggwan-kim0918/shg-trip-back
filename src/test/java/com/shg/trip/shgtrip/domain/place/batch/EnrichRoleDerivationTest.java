package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 보강 결과에서 일정 속 역할(place_role)·음식 종류(cuisine)를 정하는 규칙. */
class EnrichRoleDerivationTest {

    @Test
    @DisplayName("업종에서 역할이 나온다 — 카페·베이커리·디저트·간식은 모두 쉬어 가는 자리(CAFE)")
    void roleFromVenue() {
        assertThat(DiningEnrichment.roleOf(DiningEnrichment.Venue.MEAL)).isEqualTo("MEAL");
        assertThat(DiningEnrichment.roleOf(DiningEnrichment.Venue.BAR)).isEqualTo("BAR");
        assertThat(DiningEnrichment.roleOf(DiningEnrichment.Venue.BAKERY)).isEqualTo("CAFE");
        assertThat(DiningEnrichment.roleOf(DiningEnrichment.Venue.SNACK)).isEqualTo("CAFE");
    }

    @Test
    @DisplayName("무엇을 먹는가(고기·해산물·면)가 어느 나라 음식인가보다 우선한다")
    void cuisinePrefersDishOverOrigin() {
        assertThat(DiningEnrichment.cuisineOf(List.of("식당", "한식", "돼지고기"), null)).isEqualTo("MEAT");
        assertThat(DiningEnrichment.cuisineOf(List.of("식당", "일식", "해산물"), null)).isEqualTo("SEAFOOD");
        assertThat(DiningEnrichment.cuisineOf(List.of("식당", "일식"),
                "Dining and Drinking > Restaurant > Asian Restaurant > Japanese Restaurant > Ramen Restaurant"))
                .isEqualTo("NOODLE");
        assertThat(DiningEnrichment.cuisineOf(List.of("식당", "한식"), null)).isEqualTo("KOREAN_SET");
        assertThat(DiningEnrichment.cuisineOf(List.of("식당"), null)).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("관광지 역할은 허용 값만 받고, 모르는 값은 버린다(일정 로직이 카테고리로 추정)")
    void attractionRoleParsing() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(BatchEnrichScheduler.parseAttractionRole(om.readTree("\"not_visitable\""))).isEqualTo("NOT_VISITABLE");
        assertThat(BatchEnrichScheduler.parseAttractionRole(om.readTree("\"SIGHT\""))).isEqualTo("SIGHT");
        assertThat(BatchEnrichScheduler.parseAttractionRole(om.readTree("\"MEAL\""))).isNull();
        assertThat(BatchEnrichScheduler.parseAttractionRole(null)).isNull();
    }
}
