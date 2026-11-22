package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 대안으로 바꾸기 전에 보여 줄 변화.
 *
 * @param incoming           앞 스텝에서 이 스텝으로 오는 구간. 같은 날 앞 스텝이 없으면 null
 * @param outgoing           이 스텝에서 같은 날 다음 스텝으로 가는 구간. 없으면 null
 * @param openAtTime         대안이 이 스텝 시각에 영업하는지. 영업시간이나 날짜를 모르면 null
 * @param closedThatDay      그날이 대안의 휴무일인지
 * @param alreadyOnDay       대안 장소가 이미 다른 스텝으로 들어 있는 날. 없으면 null
 * @param resolvedNotices    바꾸면 사라지는 안내
 * @param newNotices         바꾸면 새로 생기는 안내
 */
public record AlternativePreviewResponse(
        LegChange incoming,
        LegChange outgoing,
        BigDecimal costBefore,
        BigDecimal costAfter,
        Boolean openAtTime,
        boolean closedThatDay,
        Integer alreadyOnDay,
        List<String> resolvedNotices,
        List<String> newNotices
) {
    public record Leg(String mode, Integer durationMin, BigDecimal distanceKm, BigDecimal cost) {

        public static Leg of(ItineraryStep step) {
            return new Leg(step.getTransportationMode(), step.getTransportationDuration(),
                    step.getTransportationDistance(), step.getTransportationCost());
        }

        public static Leg of(GeoUtils.TransportLeg leg) {
            return new Leg(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost());
        }
    }

    public record LegChange(Leg before, Leg after) {}
}
