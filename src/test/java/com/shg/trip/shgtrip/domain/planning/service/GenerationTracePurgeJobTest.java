package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GenerationTracePurgeJobTest {

    @Test
    @DisplayName("관문에서 막힌 생성 기록은 30일이 지나면 지운다")
    void purgesRejectedTracesOlderThan30Days() {
        GenerationTraceRepository repository = mock(GenerationTraceRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-12-31T00:00:00Z"), ZoneOffset.UTC);

        new GenerationTracePurgeJob(repository, clock).purge();

        verify(repository).deleteFailedBefore(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
    }
}
