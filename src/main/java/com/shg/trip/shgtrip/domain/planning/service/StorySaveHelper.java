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
 * @Async 서비스에서 자기 호출 없이 @Transactional을 적용하려고 분리한 빈이다.
 * 스텝별 notes를 한 트랜잭션에 묶어야 중간 예외 때 일부 스텝만 story가 채워진 채 남지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorySaveHelper {

    /** 사용자 편집 제약({@code ItineraryUpdateRequest} 100자)과 같은 값이다. */
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

        // story 단계의 tags는 쓰지 않는다. 사용자가 자주 손대는 값이라 제목처럼 임시값 비교로
        // 편집 여부를 판별하기 어렵다.
    }

    /**
     * 제목이 아직 구조 저장 시점의 임시값일 때만 교체한다.
     * complete와 story 사이에 사용자가 고친 제목을 무조건 덮으면 그 편집이 사라진다.
     */
    private void replaceTitleIfUntouched(Long itineraryId, String storyTitle, String draftTitle) {
        if (storyTitle == null || storyTitle.isBlank() || draftTitle == null) return;

        String next = storyTitle.strip();
        if (next.length() > MAX_TITLE_LENGTH) next = next.substring(0, MAX_TITLE_LENGTH).strip();
        if (next.equals(draftTitle)) return;

        int affected = itineraryRepository.updateTitleIfUnchanged(itineraryId, next, draftTitle);
        if (affected == 0) {
            log.info("제목 교체 생략 — 그사이 제목이 변경됨(사용자 편집 추정) (itineraryId={})", itineraryId);
        } else {
            log.info("제목 교체: itineraryId={} '{}' → '{}'", itineraryId, draftTitle, next);
        }
    }
}
