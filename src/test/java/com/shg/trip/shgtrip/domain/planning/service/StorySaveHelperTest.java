package com.shg.trip.shgtrip.domain.planning.service;

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

    private StorySaveHelper helper;

    @BeforeEach
    void setUp() {
        helper = new StorySaveHelper(itineraryStepRepository);
    }

    private StepData step(int order) {
        return new StepData(order, 1, "09:00", "11:00",
                new PlaceData("장소" + order, null, "관광", "지역", "국가"),
                List.of(), "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("모든 스텝의 story(notes)를 갱신한다")
    void saveStory_allMatched_updatesNotes() {
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
    }

    @Test
    @DisplayName("story는 title/tags를 절대 건드리지 않는다 — 구조 저장 후 사용자 편집 유실 방지(회귀 가드)")
    void saveStory_neverOverwritesTitleOrTags() {
        // storyOutput에 title/tags가 있어도 notes만 갱신하고 itinerary의 title/tags는 덮어쓰지 않는다.
        // (덮어쓰면 complete 직후 사용자의 제목/태그 편집이 비동기로 유실됨)
        List<StepData> steps = List.of(step(1));
        AssemblyItineraryOutput output = new AssemblyItineraryOutput(
                "새 제목", List.of("새태그"),
                List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기")));
        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기")))
                .thenReturn(1);

        helper.saveStory(42L, steps, output);

        // notes만 호출되고 그 외 상호작용은 없다 (title/tags 갱신 경로 자체가 제거됨).
        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 1, "이야기");
        verifyNoMoreInteractions(itineraryStepRepository);
    }

    @Test
    @DisplayName("LLM stepOrder가 실제 step과 어긋나면(0행 갱신) 해당 step만 건너뛰고 나머지는 정상 처리한다")
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

        // stepOrder=2는 LLM 응답에 없어 호출 안 됨, stepOrder=3은 fixedSteps에 없어 루프 대상이 아님
        verify(itineraryStepRepository, times(1)).updateNotesByItineraryIdAndStepOrder(anyLong(), anyInt(), anyString());
        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 1, "이야기1");
    }
}
