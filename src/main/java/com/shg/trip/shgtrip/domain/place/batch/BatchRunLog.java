package com.shg.trip.shgtrip.domain.place.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 배치 단계 실행 이력(batch_runs). 기록 실패가 배치를 멈추지 않게 예외는 경고로만 남긴다. */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
class BatchRunLog {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcTemplate jdbcTemplate;

    /** @return 이력 행 id, 기록하지 못하면 null */
    Long start(UUID runId, String stage) {
        try {
            return jdbcTemplate.queryForObject(
                    "INSERT INTO batch_runs (run_id, stage, status) VALUES (?, ?, 'RUNNING') RETURNING id",
                    Long.class, runId, stage);
        } catch (RuntimeException e) {
            log.warn("배치 이력 기록 실패({}): {}", stage, e.getMessage());
            return null;
        }
    }

    void finish(Long id, boolean succeeded, String error) {
        if (id == null) return;
        try {
            jdbcTemplate.update("UPDATE batch_runs SET status = ?, finished_at = now(), error_message = ? WHERE id = ?",
                    succeeded ? "SUCCESS" : "FAILED",
                    error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH)), id);
        } catch (RuntimeException e) {
            log.warn("배치 이력 갱신 실패(id={}): {}", id, e.getMessage());
        }
    }
}
