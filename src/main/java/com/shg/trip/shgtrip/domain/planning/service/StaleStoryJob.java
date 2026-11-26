package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/** 서버 재시작·작업 거부로 이야기 작업이 끝나지 못한 일정은 PENDING으로 남아 열 때마다 화면이 작성 중으로 기다린다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleStoryJob {

    /** 보강 대기와 Haiku 호출 재시도를 거친 이야기 작업도 끝나는 시간. 이보다 늦게 끝난 작업은 DONE을 다시 기록한다. */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private final ItineraryRepository itineraryRepository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void failStale() {
        try {
            int failed = itineraryRepository.transitionStoryStatusCreatedBefore(Itinerary.StoryStatus.PENDING,
                    Itinerary.StoryStatus.FAILED, OffsetDateTime.now(clock).minus(STALE_AFTER));
            if (failed > 0) log.warn("끝나지 못한 이야기 작업 {}건을 실패로 기록", failed);
        } catch (RuntimeException e) {
            log.warn("끝나지 못한 이야기 작업 정리 실패: {}", e.getMessage());
        }
    }
}
