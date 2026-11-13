package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.math.BigDecimal;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 스텝 예상 비용. */
final class CostEstimator {

    private CostEstimator() {
    }

    static BigDecimal estimateCost(PlaceCandidate c) {
        String category = PlaceCategoryConstants.majorCategory(c.category());
        Integer pl = c.priceLevel();
        return switch (category) {
            case "DINING" -> BigDecimal.valueOf(pl != null ? pl * DINING_WON_PER_PRICE_LEVEL : DINING_DEFAULT_WON);
            case "CAFE" -> BigDecimal.valueOf(pl != null ? pl * CAFE_WON_PER_PRICE_LEVEL : CAFE_DEFAULT_WON);
            // 관광지 입장료: enrich가 채운 admissionFee 우선(무료=0, 유료=실제 입장료). 없으면
            // priceLevel을 "유료 시설" 신호로만 쓴다(대부분 자연 명소는 무료라 0이 기본).
            case "ATTRACTION" -> BigDecimal.valueOf(c.admissionFee() != null
                    ? c.admissionFee() : (pl != null ? PAID_ATTRACTION_DEFAULT_WON : 0L));
            case "LODGING" -> BigDecimal.valueOf(estimateLodgingCost(c, pl));
            default -> BigDecimal.ZERO; // TRANSIT_HUB/OTHER는 스텝 비용 개념 없음
        };
    }

    /** 숙소 1박 추정가: priceLevel 우선, 없으면 유형(hostel/resort/그 외)별 기본값. 유형은 보강 속성 우선. */
    private static long estimateLodgingCost(PlaceCandidate c, Integer priceLevel) {
        if (priceLevel != null) return priceLevel * LODGING_WON_PER_PRICE_LEVEL;
        String type = c.attributes() == null ? null : c.attributes().lodgingType();
        if (type != null) {
            return switch (type) {
                case "HOSTEL", "GUESTHOUSE" -> LODGING_HOSTEL_DEFAULT_WON;
                case "RESORT" -> LODGING_RESORT_DEFAULT_WON;
                default -> LODGING_HOTEL_DEFAULT_WON;
            };
        }
        String lower = c.category() == null ? "" : c.category().toLowerCase();
        if (lower.contains("hostel") || lower.contains("guest")) return LODGING_HOSTEL_DEFAULT_WON;
        if (lower.contains("resort") || lower.contains("pool villa") || lower.contains("villa")) {
            return LODGING_RESORT_DEFAULT_WON;
        }
        return LODGING_HOTEL_DEFAULT_WON;
    }
}
