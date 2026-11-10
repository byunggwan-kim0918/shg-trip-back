package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.repository.TripChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/** 대화 원문이 남는 테이블이라 만료·보관된 세션을 지운다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripChatPurgeJob {

    private static final Duration ARCHIVED_RETENTION = Duration.ofDays(1);

    private final TripChatSessionRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purge() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int deleted = repository.deleteExpired(now, now.minus(ARCHIVED_RETENTION));
        if (deleted > 0) log.info("대화 세션 정리 {}건", deleted);
    }
}
