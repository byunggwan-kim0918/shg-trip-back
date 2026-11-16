package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningCheckTest {

    private static final String HOURS = "월요일: 11:00 ~ 15:00, 화요일: 휴무, 수요일: 11:00 ~ 15:00, 목요일: 11:00 ~ 15:00, "
            + "금요일: 11:00 ~ 15:00, 토요일: 11:00 ~ 15:00, 일요일: 11:00 ~ 15:00";
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    @Test
    @DisplayName("그 시각 영업 여부와 휴무일을 알려 주고, 영업시간을 모르면 판단하지 않는다")
    void readsOpeningHours() {
        Place diner = place("Dining and Drinking > Restaurant", HOURS);

        assertThat(OpeningCheck.openDuring(diner, MONDAY, "11:30", "12:40")).isTrue();
        assertThat(OpeningCheck.openDuring(diner, MONDAY, "17:30", "18:40")).isFalse();
        assertThat(OpeningCheck.closedOn(diner, MONDAY.plusDays(1))).isTrue();
        assertThat(OpeningCheck.openDuring(place("Dining and Drinking > Restaurant", null), MONDAY, "11:30", "12:40"))
                .isNull();
    }

    @Test
    @DisplayName("숙소는 판정 규칙처럼 영업시간을 보지 않는다")
    void skipsLodging() {
        Place hotel = place("Travel and Transportation > Lodging > Hotel", HOURS);

        assertThat(OpeningCheck.openDuring(hotel, MONDAY, "20:00", "20:30")).isNull();
        assertThat(OpeningCheck.closedOn(hotel, MONDAY.plusDays(1))).isFalse();
    }

    private static Place place(String category, String hours) {
        return Place.builder().id(1L).name("장소").address("주소").category(category).region("Seoul").country("KR")
                .openingHours(hours).build();
    }
}
