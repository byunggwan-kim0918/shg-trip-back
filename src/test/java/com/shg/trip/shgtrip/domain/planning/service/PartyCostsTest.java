package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartyCostsTest {

    private static StepData leg(String mode, BigDecimal cost, TransportRoute.Fare fare) {
        TransportRoute route = fare == null ? null : new TransportRoute("GTFS_SCHEDULE", "GTFS_SCHEDULE", "FOUND", "otp",
                null, null, null, null, null, 0, 0, List.of(), fare, null, null, null);
        return new StepData(1, 1, "10:00", "11:00", new PlaceData("곳", null, "Arts and Entertainment > Museum", null, "KR"), List.of(), mode, 20, BigDecimal.ONE, cost,
                null, null, route);
    }

    private static TransportRoute.Fare fare(String amount, String currency, String per) {
        return TransportRoute.Fare.of(amount == null ? null : new BigDecimal(amount), currency, per, "PUBLIC_DATA",
                "otp", null, null, List.of());
    }

    @Test
    void 경로_요금은_사람마다_내는_요금이면_인원수만큼_차_요금이면_탈_차_수만큼_곱한다() {
        List<StepData> scaled = PartyCosts.scale(List.of(
                leg("BUS", new BigDecimal("1500"), fare("1500", "KRW", TransportRoute.Fare.PERSON)),
                leg("TAXI", new BigDecimal("12000"), fare("12000", "KRW", TransportRoute.Fare.VEHICLE)),
                leg("BUS", new BigDecimal("1848"), fare("220", "JPY", TransportRoute.Fare.PERSON))), 5);

        assertThat(scaled.get(0).transportationCost()).isEqualByComparingTo("7500");
        assertThat(scaled.get(0).transportationRoute().fare().units()).isEqualTo(5);
        assertThat(scaled.get(1).transportationCost()).as("다섯 명은 택시 두 대").isEqualByComparingTo("24000");
        assertThat(scaled.get(1).transportationRoute().fare().units()).isEqualTo(2);
        TransportRoute.Fare yen = scaled.get(2).transportationRoute().fare();
        assertThat(yen.total()).as("현지 통화 금액을 남긴다").isEqualByComparingTo("1100");
        assertThat(yen.currency()).isEqualTo("JPY");
        assertThat(scaled.get(2).transportationCost()).isEqualByComparingTo("9240");
        assertThat(yen.ratesAsOf()).isNotNull();
    }

    @Test
    void 요금을_모르는_구간은_이동_비용을_0원이_아니라_비운다() {
        List<StepData> scaled = PartyCosts.scale(List.of(
                leg("SUBWAY", null, TransportRoute.Fare.unknown(TransportRoute.Fare.PERSON, "otp-kr", null))), 2);

        assertThat(scaled.get(0).transportationCost()).isNull();
        assertThat(scaled.get(0).transportationRoute().fare().travellers()).isEqualTo(2);
    }

    @Test
    void 경로_근거가_없는_구간은_전처럼_도시_간_열차만_인원수를_곱한다() {
        List<StepData> scaled = PartyCosts.scale(List.of(leg("TRAIN", new BigDecimal("59800"), null),
                leg("CAR", new BigDecimal("8000"), null)), 2);

        assertThat(scaled.get(0).transportationCost()).isEqualByComparingTo("119600");
        assertThat(scaled.get(1).transportationCost()).isEqualByComparingTo("8000");
    }

    @Test
    void 환율이_없는_통화는_원화로_바꾸지_않고_현지_금액만_남긴다() {
        TransportRoute.Fare f = fare("3", "EUR", TransportRoute.Fare.PERSON).forParty(2, 4);

        assertThat(f.total()).isEqualByComparingTo("6");
        assertThat(f.totalWon()).isNull();
        assertThat(f.wonPerUnit()).isNull();
    }
}
