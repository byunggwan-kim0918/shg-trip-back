package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostSummaryTest {

    private static ItineraryStepResponse step(int day, String mode, String cost, String placeCost, TransportRoute.Fare fare) {
        TransportRoute route = fare == null ? null : new TransportRoute("GTFS_SCHEDULE", "GTFS_SCHEDULE", "FOUND",
                "otp", null, null, null, null, null, 0, 0, List.of(), fare, null, null, null);
        return new ItineraryStepResponse(1L, 1, day, "10:00", "11:00", null, mode, mode == null ? null : 20,
                BigDecimal.ONE, cost == null ? null : new BigDecimal(cost), null, null, null,
                placeCost == null ? null : new BigDecimal(placeCost), List.of(), false, route);
    }

    @Test
    void 요금을_모르는_이동은_0원으로_더하지_않고_따로_세어_합계를_확정하지_않는다() {
        TransportRoute.Fare known = TransportRoute.Fare.of(new BigDecimal("1500"), "KRW", TransportRoute.Fare.PERSON,
                "PUBLIC_DATA", "otp", null, null, List.of()).forParty(2, 4);
        TransportRoute.Fare unknown = TransportRoute.Fare.unknown(TransportRoute.Fare.PERSON, "otp-kr", null)
                .forParty(2, 4);
        ItineraryResponse.CostSummary c = ItineraryResponse.CostSummary.of(List.of(
                step(1, null, null, "30000", null),
                step(1, "BUS", "3000", "20000", known),
                step(2, "SUBWAY", null, "10000", unknown)), new BigDecimal("50000"));

        assertThat(c.transportCost()).isEqualByComparingTo("3000");
        assertThat(c.unknownTransportLegs()).isEqualTo(1);
        assertThat(c.complete()).isFalse();
        assertThat(c.total()).isEqualByComparingTo("63000");
        assertThat(c.perPerson()).isEqualByComparingTo("31500");
        assertThat(c.overBudget()).as("아는 금액만으로 예산을 넘는다").isTrue();
        assertThat(c.days()).extracting(ItineraryResponse.CostSummary.Day::unknownTransportLegs).containsExactly(0, 1);
        assertThat(c.fareBasis()).containsEntry("PUBLIC_DATA", 1).containsEntry("UNKNOWN", 1);
    }

    @Test
    void 통행료_주차비_대여료처럼_빠진_항목이_있으면_금액을_다_알아도_확정하지_않는다() {
        TransportRoute.Fare fuel = TransportRoute.Fare.of(new BigDecimal("16000"), "KRW", TransportRoute.Fare.VEHICLE,
                "ESTIMATED", "distance estimate", null, null, List.of("TOLL", "PARKING", "RENTAL_FEE")).forParty(4, 5);
        ItineraryResponse.CostSummary c = ItineraryResponse.CostSummary.of(List.of(
                step(1, "CAR", "16000", "10000", fuel)), new BigDecimal("50000"));

        assertThat(c.unknownTransportLegs()).isZero();
        assertThat(c.complete()).isFalse();
        assertThat(c.excludes()).containsExactly("PARKING", "RENTAL_FEE", "TOLL");
        assertThat(c.overBudget()).as("빠진 항목이 있어 예산 안이라고 판단하지 않는다").isNull();
    }

    @Test
    void 모든_금액을_알고_예산_안이면_예산을_지킨다고_본다_모르는_금액이_있으면_판단하지_않는다() {
        ItineraryResponse.CostSummary complete = ItineraryResponse.CostSummary.of(List.of(
                step(1, "WALK", "0", "10000", null)), new BigDecimal("50000"));
        ItineraryResponse.CostSummary partial = ItineraryResponse.CostSummary.of(List.of(
                step(1, "BUS", null, "10000", null)), new BigDecimal("50000"));

        assertThat(complete.overBudget()).isFalse();
        assertThat(complete.perPerson()).as("인원을 모르면 인당 금액을 내지 않는다").isNull();
        assertThat(partial.overBudget()).isNull();
    }
}
