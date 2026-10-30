package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryStepRepository;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Story(notes) 저장 전용 빈.
 * StoryGenerationService(@Async)에서 자기 호출 없이 @Transactional을 적용하기 위해 분리
 * (ItinerarySaveHelper와 동일한 이유). 스텝별 notes 업데이트를 단일 트랜잭션으로 묶어,
 * 중간에 예외가 나도 일부 스텝만 story가 채워진 채로 남는 상태를 막는다.
 * tags는 구조 저장 시점에 확정되므로 여기서 건드리지 않는다. title은 "아직 임시값일 때만" 교체한다
 * ({@link #replaceTitleIfUntouched}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorySaveHelper {

    /** 제목 상한 — 사용자 편집 제약({@code ItineraryUpdateRequest} 100자)과 같은 값을 쓴다. */
    private static final int MAX_TITLE_LENGTH = 100;

    private final ItineraryStepRepository itineraryStepRepository;
    private final ItineraryRepository itineraryRepository;

    /**
     * @param draftTitle 구조 저장 시점에 넣어둔 임시 제목("서울 1박 2일" 같은 결정론적 문구).
     *                   제목이 아직 이 값과 같을 때만 story 단계의 제목으로 교체한다.
     */
    @Transactional
    public void saveStory(Long itineraryId, List<StepData> fixedSteps, AssemblyItineraryOutput storyOutput,
                          String draftTitle) {
        Map<Integer, String> storyByOrder = new HashMap<>();
        if (storyOutput.steps() != null) {
            for (AssemblyItineraryOutput.StoryStep s : storyOutput.steps()) {
                storyByOrder.put(s.stepOrder(), s.story());
            }
        }

        int expected = 0;
        int updated = 0;
        for (StepData step : fixedSteps) {
            String story = storyByOrder.get(step.stepOrder());
            if (story == null) {
                continue;
            }
            expected++;
            int affected = itineraryStepRepository.updateNotesByItineraryIdAndStepOrder(
                    itineraryId, step.stepOrder(), story);
            if (affected == 0) {
                log.warn("story 업데이트가 0행에 적용됨 - stepOrder 불일치 의심 (itineraryId={}, stepOrder={})",
                        itineraryId, step.stepOrder());
            } else {
                updated += affected;
            }
        }
        if (updated < expected) {
            log.warn("story 갱신 불일치 - 기대 {}건, 실제 적용 {}건 (itineraryId={})", expected, updated, itineraryId);
        }

        replaceTitleIfUntouched(itineraryId, storyOutput.title(), draftTitle);

        // tags는 구조 저장 시점에 enrich의 searchTags로 확정된다. story 단계의 tags는 쓰지 않는다 —
        // 태그는 사용자가 더 자주 손대는 값이고, 제목처럼 "임시값과 같은지"로 판별하기 어렵다
        // (순서·개수가 달라도 같은 집합일 수 있어 비교가 불안정하다).
    }

    /**
     * 제목이 아직 구조 저장 시점의 임시값일 때만 story 단계의 제목으로 교체한다.
     *
     * <p>임시 제목은 Sonnet의 concept 문장이라 "~를 즐기는 ~ 여행" 같은 한 줄 설명이 된다(실측 79번:
     * 72자). story 단계는 같은 일정을 보고 더 제목다운 문장을 만들어 돌려주는데, 예전에는 그걸
     * 버렸다. 다만 complete와 story 사이 몇 초 동안 사용자가 제목을 고칠 수 있어 무조건 덮으면
     * 그 편집이 사라진다 — WHERE 조건으로 그 경우를 걸러낸다.
     */
    private void replaceTitleIfUntouched(Long itineraryId, String storyTitle, String draftTitle) {
        if (storyTitle == null || storyTitle.isBlank() || draftTitle == null) return;

        String next = storyTitle.strip();
        if (next.length() > MAX_TITLE_LENGTH) next = next.substring(0, MAX_TITLE_LENGTH).strip();
        if (next.equals(draftTitle)) return; // 바꿀 게 없음

        int affected = itineraryRepository.updateTitleIfUnchanged(itineraryId, next, draftTitle);
        if (affected == 0) {
            log.info("제목 교체 생략 — 그사이 제목이 변경됨(사용자 편집 추정) (itineraryId={})", itineraryId);
        } else {
            log.info("제목 교체: itineraryId={} '{}' → '{}'", itineraryId, draftTitle, next);
        }
    }
}
