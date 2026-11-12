package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningCountriesTest {

    private final PlanningCountries countries = new PlanningCountries(List.of("KR", " JP "), Destinations.fromClasspath());

    @Test
    @DisplayName("한국과 국가를 모르는 요청은 막지 않고, 목록 국가는 대소문자와 공백을 무시한다")
    void isEnabled() {
        assertThat(countries.isEnabled("")).isTrue();
        assertThat(countries.isEnabled(null)).isTrue();
        assertThat(countries.isEnabled("kr")).isTrue();
        assertThat(countries.isEnabled("jp")).isTrue();
        assertThat(countries.isEnabled("CN")).isFalse();
    }

    @Test
    @DisplayName("안내 문구는 나라 이름과 지금 열린 나라를 말하고, 모르는 코드는 '이 여행지'로 말한다")
    void notEnabledMessage() {
        assertThat(countries.notEnabledMessage("CN"))
                .isEqualTo("중국 여행지는 아직 준비 중이에요. 지금은 한국·일본 여행지로 일정을 만들 수 있어요.");
        assertThat(countries.notEnabledMessage("FR")).startsWith("이 여행지는 아직 준비 중이에요.");
    }

    @Test
    @DisplayName("열지 않은 나라의 목록 여행지는 한국어 이름·별칭으로 말해도 안내하고, 열린 나라·한국·목록 밖 이름은 통과한다")
    void closedDestinationMessage() {
        assertThat(countries.closedDestinationMessage("상하이")).hasValueSatisfying(m -> assertThat(m).startsWith("중국 여행지는"));
        assertThat(countries.closedDestinationMessage("Saigon")).hasValueSatisfying(m -> assertThat(m).startsWith("베트남 여행지는"));
        assertThat(countries.closedDestinationMessage("교토")).isEmpty();
        assertThat(countries.closedDestinationMessage("부산")).isEmpty();
        assertThat(countries.closedDestinationMessage("방콕")).isEmpty();
        assertThat(countries.closedDestinationMessage(null)).isEmpty();
    }
}
