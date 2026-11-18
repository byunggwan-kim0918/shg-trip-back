package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SolarClockTest {

    @Test
    @DisplayName("일몰 시각은 여름이 겨울보다 두 시간 넘게 늦다")
    void sunsetBySeason() {
        int summer = SolarClock.sunset(LocalDate.of(2027, 6, 21), 37.5665, 126.9780);
        int winter = SolarClock.sunset(LocalDate.of(2026, 12, 21), 37.5665, 126.9780);
        assertThat(summer).isBetween(19 * 60 + 50, 20 * 60 + 2);
        assertThat(winter).isBetween(17 * 60 + 10, 17 * 60 + 22);
    }
}
