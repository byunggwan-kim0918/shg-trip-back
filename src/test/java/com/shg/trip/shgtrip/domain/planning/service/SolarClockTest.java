package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SolarClockTest {

    @Test
    @DisplayName("일몰 시각은 여름이 겨울보다 두 시간 넘게 늦다")
    void sunsetBySeason() {
        int summer = SolarClock.sunset(LocalDate.of(2027, 6, 21), 37.5665, 126.9780, "KR");
        int winter = SolarClock.sunset(LocalDate.of(2026, 12, 21), 37.5665, 126.9780, "KR");
        assertThat(summer).isBetween(19 * 60 + 50, 20 * 60 + 2);
        assertThat(winter).isBetween(17 * 60 + 10, 17 * 60 + 22);
    }

    @Test
    @DisplayName("해외는 그 나라 시각이다. 하노이 겨울 일몰은 현지 17시 20분 무렵이다")
    void sunsetInLocalTime() {
        int hanoi = SolarClock.sunset(LocalDate.of(2026, 12, 21), 21.0285, 105.8542, "VN");
        assertThat(hanoi).isBetween(17 * 60 + 10, 17 * 60 + 30);
    }
}
