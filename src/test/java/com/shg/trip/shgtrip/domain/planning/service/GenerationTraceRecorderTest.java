package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTraceRecorderTest {

    private static Place place(long id) {
        return Place.builder().id(id).name("p" + id).address("주소").category("관광")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build();
    }

    @Test
    @DisplayName("생성 직후 스텝을 (일차, 순서)로 정렬해 스텝 id·장소 id·시각·대안 장소 id로 남긴다")
    void snapshotsStepsInOrder() {
        ItineraryStep later = ItineraryStep.builder().id(2L).dayNumber(2).stepOrder(0)
                .startTime("09:00").endTime("10:00").place(place(20)).build();
        ItineraryStep first = ItineraryStep.builder().id(1L).dayNumber(1).stepOrder(0)
                .startTime("10:00").endTime("11:00").place(place(10)).build();
        first.addAlternative(AlternativeOption.builder().place(place(11)).optionOrder(1).build());
        ItineraryStep noPlace = ItineraryStep.builder().id(3L).dayNumber(1).stepOrder(1)
                .startTime("12:00").endTime("13:00").build();
        Itinerary itinerary = Itinerary.builder().id(7L).userId(1L).title("t").destination("제주")
                .startDate(LocalDate.of(2026, 11, 1)).endDate(LocalDate.of(2026, 11, 2))
                .steps(new ArrayList<>(List.of(later, first, noPlace))).build();

        List<Map<String, Object>> snapshot = GenerationTraceRecorder.stepSnapshot(itinerary);

        assertThat(snapshot).extracting(m -> m.get("stepId")).containsExactly(1L, 3L, 2L);
        assertThat(snapshot.get(0)).containsEntry("placeId", 10L).containsEntry("start", "10:00")
                .containsEntry("alternatives", List.of(11L));
        assertThat(snapshot.get(1)).containsEntry("placeId", null).containsEntry("alternatives", List.of());
    }
}
