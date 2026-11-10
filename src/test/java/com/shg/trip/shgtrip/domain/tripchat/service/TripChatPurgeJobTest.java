package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.repository.TripChatSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TripChatPurgeJobTest {

    @Test
    @DisplayName("만료된 세션과 하루 지난 보관 세션을 지운다")
    void purgesExpiredAndOldArchived() {
        TripChatSessionRepository repository = mock(TripChatSessionRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneId.of("Asia/Seoul"));

        new TripChatPurgeJob(repository, clock).purge();

        OffsetDateTime now = OffsetDateTime.now(clock);
        verify(repository).deleteExpired(now, now.minusDays(1));
    }
}
