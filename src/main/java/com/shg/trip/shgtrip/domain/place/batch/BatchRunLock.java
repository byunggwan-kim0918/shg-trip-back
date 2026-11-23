package com.shg.trip.shgtrip.domain.place.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 배치 중복 실행 방지. 스케줄 실행과 손으로 돌린 실행이 겹치면 같은 행을 동시에 끄고 같은 보강을 두 번 낸다.
 * PostgreSQL 세션 잠금이라 잠근 커넥션을 실행 내내 쥐고 있어야 하고, 프로세스가 끝나면 세션과 함께 풀린다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
class BatchRunLock implements AutoCloseable {

    /** 이 애플리케이션 배치 전용 잠금 키. 다른 advisory lock 사용처와 겹치지 않는 고정값이다. */
    static final long LOCK_KEY = 0x5348475f42415443L;

    private final DataSource dataSource;
    private Connection held;

    /** @return 잠금을 얻으면 true, 다른 배치가 쥐고 있으면 false */
    synchronized boolean tryAcquire() throws SQLException {
        if (held != null) return true;
        Connection connection = dataSource.getConnection();
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) {
                    held = connection;
                    return true;
                }
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        connection.close();
        return false;
    }

    @Override
    public synchronized void close() {
        if (held == null) return;
        try (Connection connection = held;
             PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, LOCK_KEY);
            ps.execute();
        } catch (SQLException e) {
            log.warn("배치 잠금 해제 실패(풀로 돌아간 커넥션에 남다가 프로세스가 끝나면 풀린다): {}", e.getMessage());
        } finally {
            held = null;
        }
    }
}
