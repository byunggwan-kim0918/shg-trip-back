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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    @DisplayName("관문에서 막힌 생성은 일정 없이 사용자 id·판정·규칙 밖 거부 사유만 남기고 생성 직후 스텝은 비운다")
    void recordsRejectedWithoutItinerary() {
        var repository = mock(com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository.class);
        var guard = mock(com.shg.trip.shgtrip.domain.user.service.ActiveUserGuard.class);
        var tx = mock(org.springframework.transaction.support.TransactionTemplate.class);
        doAnswer(inv -> {
            inv.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        GenerationTraceRecorder recorder = new GenerationTraceRecorder(repository,
                com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(),
                new com.shg.trip.shgtrip.global.config.AnthropicProperties("haiku", "sonnet", 1000), guard, tx);
        ItineraryRules.Verdict hard = new ItineraryRules.Verdict(ItineraryRules.VERSION, List.of(
                new ItineraryRules.Violation(ItineraryRules.Rule.DAY_MISSING, 2, "day2 일정 없음")), 0, 0);

        recorder.recordRejected(7L, "job-x", null, null, null, null, List.of(), 48, null, 1200L, hard, null);
        recorder.recordRejected(7L, "job-y", null, null, null, null, List.of(), 0, null, 1200L, null, "구조 검증: 시각 겹침");
        recorder.recordRejected(7L, "job-z", null, null, null, null, List.of(), 0, null, 1200L, hard, "구조 검증: 시각 겹침");

        var saved = org.mockito.ArgumentCaptor.forClass(com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace.class);
        verify(repository, org.mockito.Mockito.times(3)).save(saved.capture());
        verify(guard, org.mockito.Mockito.times(3)).lockActive(7L);
        var byRules = saved.getAllValues().get(0);
        assertThat(byRules.getItineraryId()).isNull();
        assertThat(byRules.getUserId()).isEqualTo(7L);
        assertThat(byRules.getGeneratedSteps()).isNull();
        assertThat(byRules.getSearchPoolSize()).isEqualTo(48);
        assertThat(byRules.getScore()).contains("\"rule\":\"DAY_MISSING\"", "\"hard\":true")
                .doesNotContain("rejectReason");
        assertThat(saved.getAllValues().get(1).getScore()).isEqualTo("{\"rejectReason\":\"구조 검증: 시각 겹침\"}");
        assertThat(saved.getAllValues().get(2).getScore())
                .contains("\"rule\":\"DAY_MISSING\"", "\"rejectReason\":\"구조 검증: 시각 겹침\"");
    }
}
