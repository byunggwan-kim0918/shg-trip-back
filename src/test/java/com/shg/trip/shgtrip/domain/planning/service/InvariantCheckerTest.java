package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvariantCheckerTest {

    private static final String ATTRACTION = "Landmarks and Outdoors > Tourist Attraction";
    private static final String AIRPORT = "Travel and Transportation > Transport Hub > Airport > Airport Terminal";

    @Test
    @DisplayName("도시를 옮기는 열차·비행기 구간은 하루 이동 예산에 넣지 않는다")
    void intercityLegIsOutsideDayBudget() {
        PlaceData osaka = new PlaceData("道頓堀", null, ATTRACTION, "Osaka", "JP");
        StepData train = new StepData(1, 3, "12:30", "14:00", osaka, List.of(), "TRAIN", 197,
                BigDecimal.valueOf(524), null, null, null);
        StepData car = new StepData(1, 3, "12:30", "14:00", osaka, List.of(), "CAR", 40,
                BigDecimal.valueOf(30), null, null, null);

        assertThat(InvariantChecker.isOutsideDayBudget(List.of(train), 0, false, false)).isTrue();
        assertThat(InvariantChecker.isOutsideDayBudget(List.of(car), 0, false, false)).isFalse();
    }

    @Test
    @DisplayName("해외 관문 공항에서 나오는 첫 구간과 공항으로 가는 마지막 구간만 하루 이동 예산에서 뺀다")
    void overseasHubTransferIsOutsideDayBudget() {
        StepData haneda = new StepData(1, 1, "10:00", "10:30", new PlaceData("羽田空港", null, AIRPORT, "Tokyo", "JP"),
                List.of(), null, null, null, null, null, null);
        StepData gimpo = new StepData(1, 1, "10:00", "10:30", new PlaceData("김포공항", null, AIRPORT, "Seoul", "KR"),
                List.of(), null, null, null, null, null, null);
        StepData sight = new StepData(2, 1, "11:30", "13:00", new PlaceData("浅草寺", null, ATTRACTION, "Tokyo", "JP"),
                List.of(), "TAXI", 50, BigDecimal.valueOf(25), null, null, null);

        assertThat(InvariantChecker.isOutsideDayBudget(List.of(haneda, sight), 1, true, false)).isTrue();
        assertThat(InvariantChecker.isOutsideDayBudget(List.of(gimpo, sight), 1, true, false)).isFalse();
        assertThat(InvariantChecker.isOutsideDayBudget(List.of(haneda, sight), 1, false, false)).isFalse();
    }
}
