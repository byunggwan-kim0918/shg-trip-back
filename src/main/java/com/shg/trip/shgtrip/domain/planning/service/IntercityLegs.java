package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 여러 도시 여행에서 도시 사이 이동 한 번의 수단·시간·거리·요금. */
public final class IntercityLegs {

    /**
     * 도시 간 이동 한 번. 역·공항을 오가는 시간까지 포함한 소요 시간이다.
     *
     * @param distanceKm 비행기는 직선 km, 그 밖에는 하루 구간과 같은 도로 환산 km
     */
    public record IntercityLeg(String mode, int minutes, BigDecimal distanceKm, BigDecimal costWon) {}

    private static final ScheduleTuning.IntercityMode FALLBACK_MODE =
            new ScheduleTuning.IntercityMode("TRAIN", 160, 45, 0, null);
    private IntercityLegs() {}

    /** 도시 중심 사이 이동. 직선거리를 이동 수단 속도로 나누고 역·공항을 오가는 시간을 더한다. */
    public static IntercityLeg legBetween(TripCities.City from, TripCities.City to, String transportPref) {
        double km = GeoUtils.haversine(from.lat(), from.lng(), to.lat(), to.lng());
        ScheduleTuning.IntercityMode mode = intercityMode(from, to, transportPref);
        int minutes = (int) Math.ceil(km / mode.kmh() * 60) + mode.overheadMinutes();
        Integer won = KrwRates.toWon((int) Math.round(km * mode.farePerKm()), mode.currency());
        // 비행기는 길을 따라 돌지 않아 도로 계수를 곱하지 않는다.
        double shownKm = "FLIGHT".equals(mode.mode()) ? km : km * GeoUtils.ROAD_DISTANCE_FACTOR;
        return new IntercityLeg(mode.mode(), minutes, BigDecimal.valueOf(shownKm).setScale(2, RoundingMode.HALF_UP),
                won == null ? null : BigDecimal.valueOf(won));
    }

    private static ScheduleTuning.IntercityMode intercityMode(TripCities.City from, TripCities.City to,
                                                              String transportPref) {
        if (!"KR".equals(to.country())) {
            return ScheduleTuning.INTERCITY_MODES.getOrDefault(to.country(), FALLBACK_MODE);
        }
        if (DomesticCities.isIsland(from.name()) || DomesticCities.isIsland(to.name())) {
            return ScheduleTuning.KR_FLIGHT;
        }
        return "car".equals(transportPref) ? ScheduleTuning.KR_DRIVE : ScheduleTuning.KR_TRAIN;
    }
}
