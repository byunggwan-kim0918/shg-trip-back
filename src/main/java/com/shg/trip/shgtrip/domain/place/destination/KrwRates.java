package com.shg.trip.shgtrip.domain.place.destination;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.util.Map;

/**
 * 비용 표시용 원화 환산. 해외 입장료는 현지 통화로 저장하고 원화는 읽을 때만 계산해, 환율을 바꿔도 재보강이 필요 없다.
 */
public final class KrwRates {

    /** 통화 1단위의 원화. 일정 비용 추정용 근삿값이라 일 단위 환율 변동은 따라가지 않는다. */
    static final Map<String, Double> WON_PER_UNIT = Map.of(
            "JPY", 9.5,
            "CNY", 195.0,
            "TWD", 44.0,
            "VND", 0.055);

    private static final int ROUND_TO_WON = 100;

    private KrwRates() {}

    /** 원화 입장료. 한국 행은 저장값, 해외 행은 현지 통화 금액을 100원 단위로 환산한 값이다. 모르면 null(0은 무료). */
    public static Integer admissionWon(Place place) {
        if (place == null) return null;
        if (place.getAdmissionFee() != null) return place.getAdmissionFee();
        return toWon(place.getAdmissionFeeLocal(), place.getAdmissionCurrency());
    }

    /** 100원 단위 원화. 0은 무료(0), 금액·통화를 모르거나 환율이 없는 통화면 null이다. */
    public static Integer toWon(Integer amount, String currency) {
        if (amount == null || currency == null) return null;
        Double rate = WON_PER_UNIT.get(currency);
        if (rate == null) return null;
        if (amount == 0) return 0;
        long won = Math.round(amount * rate / ROUND_TO_WON) * ROUND_TO_WON;
        return (int) Math.max(ROUND_TO_WON, won);
    }
}
