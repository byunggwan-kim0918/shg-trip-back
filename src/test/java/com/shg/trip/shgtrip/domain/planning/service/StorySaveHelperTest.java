package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryStepRepository;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorySaveHelperTest {

    @Mock
    private ItineraryStepRepository itineraryStepRepository;
    @Mock
    private ItineraryRepository itineraryRepository;

    private StorySaveHelper helper;

    @BeforeEach
    void setUp() {
        helper = new StorySaveHelper(itineraryStepRepository, itineraryRepository);
    }

    private StepData step(int order) {
        return new StepData(order, 1, "09:00", "11:00",
                new PlaceData("장소" + order, null, "관광", "지역", "국가"),
                List.of(), "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("모든 스텝의 story와 title/tags를 갱신한다")
    void saveStory_allMatched_updatesEverything() {
        List<StepData> steps = List.of(step(1), step(2));
        AssemblyItineraryOutput output = new AssemblyItineraryOutput(
                "제목", List.of("태그1"),
                List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기1"),
                        new AssemblyItineraryOutput.StoryStep(2, "이야기2")));

        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기1")))
                .thenReturn(1);
        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(2), eq("이야기2")))
                .thenReturn(1);

        helper.saveStory(42L, steps, output);

        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 1, "이야기1");
        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 2, "이야기2");
        verify(itineraryRepository).updateTitleAndTags(42L, "제목", List.of("태그1"));
    }

    @Test
    @DisplayName("LLM이 반환한 stepOrder가 실제 step과 어긋나면(0행 갱신) 해당 step은 건너뛰고 나머지는 정상 처리한다")
    void saveStory_stepOrderMismatch_skipsThatStepOnly() {
        // fixedSteps는 stepOrder 1,2를 갖지만 LLM은 1,3을 반환 — 3은 존재하지 않는 stepOrder
        List<StepData> steps = List.of(step(1), step(2));
        AssemblyItineraryOutput output = new AssemblyItineraryOutput(
                null, null,
                List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기1"),
                        new AssemblyItineraryOutput.StoryStep(3, "유령스텝")));

        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기1")))
                .thenReturn(1);

        helper.saveStory(42L, steps, output);

        // stepOrder=2는 LLM 응답에 없으므로 호출 자체가 안 됨, stepOrder=3은 fixedSteps에 없어 루프 대상이 아님
        verify(itineraryStepRepository, times(1)).updateNotesByItineraryIdAndStepOrder(anyLong(), anyInt(), anyString());
        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 1, "이야기1");
        // title/tags가 null이면 업데이트 호출 자체를 안 함
        verifyNoInteractions(itineraryRepository);
    }

    @Test
    @DisplayName("title/tags가 없으면 itineraryRepository를 호출하지 않는다")
    void saveStory_noTitleOrTags_doesNotUpdateItinerary() {
        List<StepData> steps = List.of(step(1));
        AssemblyItineraryOutput output = new AssemblyItineraryOutput(
                null, null, List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기")));
        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기")))
                .thenReturn(1);

        helper.saveStory(42L, steps, output);

        verifyNoInteractions(itineraryRepository);
    }
}
