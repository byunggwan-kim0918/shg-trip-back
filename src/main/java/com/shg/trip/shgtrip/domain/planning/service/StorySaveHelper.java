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
 * Story(notes/title/tags) 저장 전용 빈.
 * StoryGenerationService(@Async)에서 자기 호출 없이 @Transactional을 적용하기 위해 분리
 * (ItinerarySaveHelper와 동일한 이유). 스텝별 notes 업데이트 + title/tags 업데이트를 단일
 * 트랜잭션으로 묶어, 중간에 예외가 나도 일부 스텝만 story가 채워진 채로 남는 상태를 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorySaveHelper {

    private final ItineraryStepRepository itineraryStepRepository;
    private final ItineraryRepository itineraryRepository;

    @Transactional
    public void saveStory(Long itineraryId, List<StepData> fixedSteps, AssemblyItineraryOutput storyOutput) {
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

        if (storyOutput.title() != null && storyOutput.tags() != null) {
            itineraryRepository.updateTitleAndTags(itineraryId, storyOutput.title(), storyOutput.tags());
        }
    }
}
