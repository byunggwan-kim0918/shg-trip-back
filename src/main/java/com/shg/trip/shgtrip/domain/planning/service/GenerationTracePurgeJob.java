package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/** 관문에서 막힌 생성 기록은 일정 삭제로 지워지지 않아 기간이 지나면 지운다. 요청 문장이 들어 있다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationTracePurgeJob {

    static final Duration FAILED_RETENTION = Duration.ofDays(30);

    private final GenerationTraceRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 50 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purge() {
        int deleted = repository.deleteFailedBefore(OffsetDateTime.now(clock).minus(FAILED_RETENTION));
        if (deleted > 0) log.info("실패 생성 기록 정리 {}건", deleted);
    }
}
