package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 장소 비용은 1인 기준으로 적재돼 있어 일정에 보일 때 인원수를 곱한다. */
public final class PartyCosts {

    private PartyCosts() {
    }

    /**
     * 식사·카페·입장료·도시 간 열차·비행기 요금을 인원수만큼 곱한다. 숙소(1실)·교통(차 1대)은 그대로 둔다.
     * 대안을 고르면 그 비용이 스텝 비용을 대체하므로 대안도 같이 곱한다. 경로 요금이 있는 구간은 그 요금의 단위(사람·차)대로
     * 곱하고, 요금을 모르면 이동 비용을 비운다.
     */
    public static List<StepData> scale(List<StepData> steps, Integer partySize) {
        boolean anyFare = steps.stream().anyMatch(s -> s.transportationRoute() != null
                && s.transportationRoute().fare() != null);
        if ((partySize == null || partySize <= 1) && !anyFare) return steps;
        BigDecimal factor = BigDecimal.valueOf(partySize == null || partySize < 1 ? 1 : partySize);
        List<StepData> scaled = new ArrayList<>(steps.size());
        for (StepData s : steps) {
            List<AlternativeData> alts = s.alternatives() == null ? null : s.alternatives().stream()
                    .map(a -> isPerPersonCost(a.category()) && a.estimatedCost() != null
                            ? a.withEstimatedCost(a.estimatedCost().multiply(factor))
                            : a)
                    .toList();
            BigDecimal cost = s.place() != null && isPerPersonCost(s.place().category()) && s.estimatedCost() != null
                    ? s.estimatedCost().multiply(factor) : s.estimatedCost();
            TransportRoute route = s.transportationRoute();
            BigDecimal fare;
            if (route != null && route.fare() != null) {
                route = route.withFare(route.fare().forParty(partySize, seatsPerVehicle(s.transportationMode())));
                fare = route.fare().totalWon();
            } else {
                fare = s.transportationMode() != null && s.transportationCost() != null
                        && ScheduleTuning.PER_PERSON_FARE_MODES.contains(s.transportationMode())
                        ? s.transportationCost().multiply(factor) : s.transportationCost();
            }
            scaled.add(new StepData(s.stepOrder(), s.dayNumber(), s.startTime(), s.endTime(), s.place(), alts,
                    s.transportationMode(), s.transportationDuration(), s.transportationDistance(),
                    fare, s.notes(), cost, route));
        }
        return scaled;
    }

    /** 차 한 대에 타는 여행자 수. 택시는 중형 정원, 렌터카·자가용은 승용 정원이다. */
    public static int seatsPerVehicle(String mode) {
        return "TAXI".equals(mode) ? TripRoutes.TAXI_SEATS : TripRoutes.CAR_SEATS;
    }

    private static boolean isPerPersonCost(String category) {
        String major = PlaceCategoryConstants.majorCategory(category);
        return "DINING".equals(major) || "CAFE".equals(major) || "ATTRACTION".equals(major);
    }
}
