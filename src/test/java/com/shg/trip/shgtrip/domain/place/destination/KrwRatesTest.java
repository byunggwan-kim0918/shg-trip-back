package com.shg.trip.shgtrip.domain.place.destination;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KrwRatesTest {

    private static Place place(Integer won, Integer local, String currency) {
        Place p = Place.builder().name("x").address("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .category("x").region("Tokyo").country("JP").admissionFee(won).build();
        if (local != null) p.recordAdmissionLocal(local, currency);
        return p;
    }

    @Test
    @DisplayName("현지 통화 입장료를 100원 단위 원화로 바꾸고, 0은 무료, 모르는 통화·금액은 null이다")
    void toWon() {
        assertThat(KrwRates.toWon(500, "JPY")).isEqualTo(4_800);
        assertThat(KrwRates.toWon(60, "CNY")).isEqualTo(11_700);
        assertThat(KrwRates.toWon(150_000, "VND")).isEqualTo(8_300);
        assertThat(KrwRates.toWon(1, "VND")).isEqualTo(100);
        assertThat(KrwRates.toWon(0, "JPY")).isZero();
        assertThat(KrwRates.toWon(500, "USD")).isNull();
        assertThat(KrwRates.toWon(null, "JPY")).isNull();
        assertThat(KrwRates.toWon(500, null)).isNull();
    }

    @Test
    @DisplayName("원화 입장료가 있으면 그 값을, 없으면 현지 통화 환산값을 쓴다")
    void admissionWon() {
        assertThat(KrwRates.admissionWon(place(3_000, null, null))).isEqualTo(3_000);
        assertThat(KrwRates.admissionWon(place(null, 500, "JPY"))).isEqualTo(4_800);
        assertThat(KrwRates.admissionWon(place(null, null, null))).isNull();
        assertThat(KrwRates.admissionWon(null)).isNull();
    }
}
