package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.END_OF_DAY_MINUTES;

/**
 * 일정 확정에서 되풀이되는 잔손질 — 시각 파싱/표기와 1-based 후보 인덱싱.
 *
 * <p>{@link RouteOptimizer}와 {@link InvariantChecker}가 같은 규칙을 봐야 해서 밖으로 뺐다.
 * 특히 시각 표기는 자정 wrap 처리가 한쪽에만 있으면 "26:16"과 "02:16"이 섞여 나온다.
 */
@Slf4j
final class ScheduleTimes {

    private ScheduleTimes() {}

    /** "HH:mm" → 분. 파싱할 수 없으면 0(= 비교에서 가장 이른 값). */
    static int toMinutesOrZero(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return 0;
        String[] p = hhmm.split(":");
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 분 → "HH:mm".
     *
     * <p>누적 시각이 자정을 넘으면 %24 wrap으로 26:16→02:16처럼 시간이 역행해 보인다.
     * 일과 종료(23:59)로 클램프해 같은 날 안에서 시각이 단조증가하도록 보장한다.
     */
    static String formatMinutes(int minutes) {
        int clamped = minutes;
        if (clamped > END_OF_DAY_MINUTES) {
            log.warn("일과 시간 초과({}분) — {}로 클램프", minutes, "23:59");
            clamped = END_OF_DAY_MINUTES;
        }
        return String.format("%02d:%02d", clamped / 60, clamped % 60);
    }

    /** 1-based 후보 인덱스 조회. 범위를 벗어나면 null. */
    static PlaceCandidate byIndex(List<PlaceCandidate> candidates, int index) {
        if (index < 1 || index > candidates.size()) return null;
        return candidates.get(index - 1);
    }
}
