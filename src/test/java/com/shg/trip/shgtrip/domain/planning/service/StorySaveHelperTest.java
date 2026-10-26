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

    /** 구조 저장 시점의 임시 제목(= Sonnet concept). 이 값과 같을 때만 제목이 교체된다. */
    private static final String DRAFT_TITLE = "제주의 바다와 오름을 차로 누비며 흑돼지 맛집을 즐기는 힐링 여행";

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

        helper.saveStory(42L, steps, output, DRAFT_TITLE);

        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 1, "이야기1");
        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 2, "이야기2");
    }

    @Test
    @DisplayName("제목이 아직 임시값이면 story 단계의 짧은 제목으로 교체한다")
    void saveStory_replacesDraftTitle() {
        List<StepData> steps = List.of(step(1));
        AssemblyItineraryOutput output = new AssemblyItineraryOutput(
                "제주 오름과 흑돼지 4일", List.of("새태그"),
                List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기")));
        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기")))
                .thenReturn(1);
        when(itineraryRepository.updateTitleIfUnchanged(42L, "제주 오름과 흑돼지 4일", DRAFT_TITLE))
                .thenReturn(1);

        helper.saveStory(42L, steps, output, DRAFT_TITLE);

        verify(itineraryRepository).updateTitleIfUnchanged(42L, "제주 오름과 흑돼지 4일", DRAFT_TITLE);
    }

    @Test
    @DisplayName("그사이 사용자가 제목을 바꿨으면(0행) 덮어쓰지 않고 넘어간다 — 편집 유실 방지(회귀 가드)")
    void saveStory_doesNotClobberUserEditedTitle() {
        List<StepData> steps = List.of(step(1));
        AssemblyItineraryOutput output = new AssemblyItineraryOutput(
                "제주 오름과 흑돼지 4일", List.of("새태그"),
                List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기")));
        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기")))
                .thenReturn(1);
        // WHERE 조건(제목이 아직 임시값)에 걸리지 않아 0행 — 사용자가 이미 고친 상태
        when(itineraryRepository.updateTitleIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(0);

        helper.saveStory(42L, steps, output, DRAFT_TITLE);

        // 조건부 UPDATE 한 번만 시도하고, 그 외 제목 관련 추가 조작은 없다
        verify(itineraryRepository, times(1)).updateTitleIfUnchanged(42L, "제주 오름과 흑돼지 4일", DRAFT_TITLE);
        verifyNoMoreInteractions(itineraryRepository);
    }

    @Test
    @DisplayName("story 제목이 비었거나 임시값과 같으면 제목 UPDATE를 시도하지 않는다")
    void saveStory_skipsTitleUpdateWhenNothingToChange() {
        List<StepData> steps = List.of(step(1));
        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기")))
                .thenReturn(1);

        helper.saveStory(42L, steps, new AssemblyItineraryOutput(
                "   ", List.of(), List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기"))), DRAFT_TITLE);
        helper.saveStory(42L, steps, new AssemblyItineraryOutput(
                DRAFT_TITLE, List.of(), List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기"))), DRAFT_TITLE);

        verifyNoInteractions(itineraryRepository);
    }

    @Test
    @DisplayName("제목이 100자를 넘으면 잘라서 저장한다 — 사용자 편집 제약과 같은 상한")
    void saveStory_truncatesOverlongTitle() {
        List<StepData> steps = List.of(step(1));
        String overlong = "가".repeat(140);
        when(itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(eq(42L), eq(1), eq("이야기")))
                .thenReturn(1);
        when(itineraryRepository.updateTitleIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(1);

        helper.saveStory(42L, steps, new AssemblyItineraryOutput(
                overlong, List.of(), List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기"))), DRAFT_TITLE);

        verify(itineraryRepository).updateTitleIfUnchanged(42L, "가".repeat(100), DRAFT_TITLE);
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

        helper.saveStory(42L, steps, output, DRAFT_TITLE);

        // stepOrder=2는 LLM 응답에 없어 호출 안 됨, stepOrder=3은 fixedSteps에 없어 루프 대상이 아님
        verify(itineraryStepRepository, times(1)).updateNotesByItineraryIdAndStepOrder(anyLong(), anyInt(), anyString());
        verify(itineraryStepRepository).updateNotesByItineraryIdAndStepOrder(42L, 1, "이야기1");
    }
}
