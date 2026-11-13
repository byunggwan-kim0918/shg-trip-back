package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DEPARTURE_CHECKIN_BUFFER_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.END_OF_DAY_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.OVERSEAS_DEPARTURE_CHECKIN_BUFFER_MINUTES;

/**
 * {@link RouteOptimizer}와 {@link InvariantChecker}가 같은 시각·인덱스 규칙을 봐야 한다.
 * 자정 wrap 처리가 한쪽에만 있으면 "26:16"과 "02:16"이 섞여 나온다.
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

    /** "HH:mm" → 분. 비었거나 형식이 틀리면 null(= 제약 없음). */
    static Integer toMinutesOrNull(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{1,2}:\\d{2}")) return null;
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /** 첫날 시작 시각(분). 도착 시각이 없으면 null. */
    static Integer firstDayStart(ScheduleArgs args) {
        return toMinutesOrNull(args.arrivalTime());
    }

    /** 마지막 날 출발 허브 도착 기한(분). 출발 시각이 없으면 null. 해외는 국제선 체크인만큼 더 이르다. */
    static Integer departureHubDeadline(ScheduleArgs args) {
        Integer departure = toMinutesOrNull(args.departureTime());
        if (departure == null) return null;
        boolean overseas = args.country() != null && !"KR".equalsIgnoreCase(args.country());
        int buffer = overseas ? OVERSEAS_DEPARTURE_CHECKIN_BUFFER_MINUTES : DEPARTURE_CHECKIN_BUFFER_MINUTES;
        return Math.max(0, departure - buffer);
    }

    /**
     * 분 → "HH:mm". 자정을 넘으면 23:59로 클램프한다.
     * %24 wrap이면 26:16→02:16처럼 같은 날 안에서 시각이 역행해 보인다.
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
