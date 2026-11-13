package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryStepRepository;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        // 편집(ItineraryService)과 같은 순서로 일정 행부터 잠가야 스텝·제목 갱신이 편집과 교착하지 않는다.
        Itinerary itinerary = itineraryRepository.findByIdForUpdate(itineraryId).orElse(null);
        if (itinerary == null) {
            log.info("story 저장 생략 - 일정이 삭제됨 (itineraryId={})", itineraryId);
            return;
        }
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
            if (story == null || step.place() == null) {
                continue;
            }
            expected++;
            // story 도착 전에 순서 변경·삭제·대안 선택이 있으면 stepOrder가 다른 장소를 가리킨다.
            int affected = itineraryStepRepository.updateNotesByDayAndPlace(
                    itineraryId, step.dayNumber(), step.place().name(), story);
            if (affected == 0) {
                log.warn("story 업데이트가 0행에 적용됨 - 편집으로 빠진 장소 (itineraryId={}, stepOrder={})",
                        itineraryId, step.stepOrder());
            } else {
                updated += affected;
            }
        }
        if (updated < expected) {
            log.warn("story 갱신 불일치 - 기대 {}건, 실제 적용 {}건 (itineraryId={})", expected, updated, itineraryId);
        }

        int days = itinerary.getStartDate() == null || itinerary.getEndDate() == null ? 0
                : (int) ChronoUnit.DAYS.between(itinerary.getStartDate(), itinerary.getEndDate()) + 1;
        replaceTitleIfUntouched(itineraryId, storyOutput.title(), draftTitle, days);

        // story 단계의 tags는 쓰지 않는다. 사용자가 자주 손대는 값이라 제목처럼 임시값 비교로
        // 편집 여부를 판별하기 어렵다.
    }

    private static final Pattern NIGHTS_AND_DAYS = Pattern.compile("\\d+박\\s*\\d+일");
    private static final Pattern TRAILING_DAYS = Pattern.compile("(?<![\\d월]\\s?)\\d+일$");

    /**
     * 제목의 여행 기간이 실제와 다르면 실제 값으로 바꾼다. 모델이 프롬프트 예시의 일수를 따라 쓴다.
     * "N박 M일"과 끝에 붙은 "N일"만 본다. 날짜("12월 24일")·"1일 1카페"는 기간이 아니다. 하루 일정은 "당일치기"다.
     */
    static String withActualDuration(String title, int days) {
        if (days <= 0) return title;
        Matcher nightsAndDays = NIGHTS_AND_DAYS.matcher(title);
        if (nightsAndDays.find()) return nightsAndDays.replaceAll(days == 1 ? "당일치기" : (days - 1) + "박 " + days + "일");
        return TRAILING_DAYS.matcher(title).replaceAll(days + "일");
    }

    /**
     * 제목이 아직 구조 저장 시점의 임시값일 때만 교체한다.
     * complete와 story 사이에 사용자가 고친 제목을 무조건 덮으면 그 편집이 사라진다.
     */
    private void replaceTitleIfUntouched(Long itineraryId, String storyTitle, String draftTitle, int days) {
        if (storyTitle == null || storyTitle.isBlank() || draftTitle == null) return;

        String next = withActualDuration(storyTitle.strip(), days);
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
