package com.shg.trip.shgtrip.domain.planning.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

@Slf4j
final class ScheduleConfig {
    final int morningStart;
    final int eveningCap;
    /** 시간창 용량 보정(활동 개수 ±). */
    final int densityDelta;
    /** 실내 성향 테마면 저녁 버킷 배치에 관대하다. */
    final boolean indoorPreferred;
    /**
     * 사용자가 고른 pace 원문(tight/normal/relaxed, null 가능). {@link ScheduleTuning#distanceLimits}용이며,
     * cfg가 이미 모든 배치 경로에 전달되므로 별도 파라미터로 흘리지 않는다.
     */
    final String pace;
    /**
     * 마지막 날을 뺀 매일 점심과 저녁 사이에 카페·디저트 1곳을 넣는지.
     * 맛집·로맨틱 테마나 커플 여행에서 없으면 "식당 → 관광 → 식당"만 반복된다.
     */
    final boolean cafeStop;

    /** 테마·커플 여부로 정한 여행 전체의 하루 설정. 날짜별 시작·마감은 {@link #forDay}가 더 좁힌다. */
    static ScheduleConfig forTrip(List<String> themes, String pace, boolean couple) {
        return forThemes(themes, pace).withCafeStop(couple || hasCafeTheme(themes));
    }

    ScheduleConfig(int morningStart, int eveningCap, int densityDelta, boolean indoorPreferred, String pace) {
        this(morningStart, eveningCap, densityDelta, indoorPreferred, pace, false);
    }

    ScheduleConfig(int morningStart, int eveningCap, int densityDelta, boolean indoorPreferred, String pace,
                   boolean cafeStop) {
        this.morningStart = morningStart;
        this.eveningCap = eveningCap;
        this.densityDelta = densityDelta;
        this.indoorPreferred = indoorPreferred;
        this.pace = pace;
        this.cafeStop = cafeStop;
    }

    private ScheduleConfig withCafeStop(boolean enabled) {
        return new ScheduleConfig(morningStart, eveningCap, densityDelta, indoorPreferred, pace, enabled);
    }

    ScheduleConfig withWindow(int start, int cap) {
        if (start == morningStart && cap == eveningCap) return this;
        return new ScheduleConfig(start, cap, densityDelta, indoorPreferred, pace, cafeStop);
    }

    /** 첫날 도착·마지막 날 출발을 반영한 그날의 시작 시각과 저녁 상한. */
    ScheduleConfig forDay(DayState day) {
        int start = day.startNotBefore != null
                ? Math.max(EARLIEST_DAY_START_MINUTES, day.startNotBefore) : morningStart;
        // 새벽 출발이면 기본 시작 시각으로는 마감을 지킬 수 없다
        if (day.startNotBefore == null && day.departureDeadline != null
                && day.departureDeadline - EARLY_DEPARTURE_LEAD_MINUTES < start) {
            start = Math.max(EARLIEST_DAY_START_MINUTES, day.departureDeadline - EARLY_DEPARTURE_LEAD_MINUTES);
        }
        int cap = day.departureDeadline != null ? Math.min(eveningCap, day.departureDeadline) : eveningCap;
        return withWindow(start, Math.max(start, cap));
    }

    /**
     * 테마 기반 하루 시간창 설정. 고른 테마 전부를 {@link ScheduleTuning#THEME_SCHEDULES}에서 찾아
     * "가장 이른 시작 · 가장 늦은 상한 · 밀도 보정 합"으로 병합한다. 미지의 테마는 무시하고,
     * 아무 테마도 매칭되지 않으면 기본값(09:00~22:00)을 쓴다.
     */
    private static ScheduleConfig forThemes(List<String> themes, String pace) {
        if (themes == null || themes.isEmpty()) {
            return new ScheduleConfig(DAY_START_MINUTES, DEFAULT_EVENING_CAP_MINUTES, 0, false, pace);
        }

        int morningStart = Integer.MAX_VALUE;
        int eveningCap = Integer.MIN_VALUE;
        int densityDelta = 0;
        boolean indoorPreferred = false;
        boolean matched = false;
        List<String> unmatched = new ArrayList<>();

        for (String theme : themes) {
            if (theme == null || theme.isBlank()) continue;
            ThemeSchedule ts = THEME_SCHEDULES.get(theme.trim().toLowerCase());
            if (ts == null) {
                unmatched.add(theme.trim());
                continue;
            }
            matched = true;
            morningStart = Math.min(morningStart, ts.morningStart());
            eveningCap = Math.max(eveningCap, ts.eveningCap());
            densityDelta += ts.densityDelta();
            indoorPreferred |= ts.indoorPreferred();
        }

        // 조용히 기본값으로 떨어지면 테마 시간창 누락을 아무도 모른다.
        // 슬러그가 생성 요청 계약(WizardOptionIds)이라 한글 별칭을 두면 같은 테마의 어휘가 두 벌이 된다.
        if (!unmatched.isEmpty()) {
            log.warn("테마 시간창 미매칭 {} — 기본 시간창으로 처리한다 (THEME_SCHEDULES 키는 영문 슬러그)",
                    unmatched);
        }

        if (!matched) {
            return new ScheduleConfig(DAY_START_MINUTES, DEFAULT_EVENING_CAP_MINUTES, 0, false, pace);
        }
        // 여러 테마를 고르면 밀도 보정이 합산돼 극단으로 갈 수 있어 ±1로 묶는다.
        densityDelta = Math.max(-1, Math.min(1, densityDelta));
        return new ScheduleConfig(
                Math.max(MIN_MORNING_START_MINUTES, morningStart),
                Math.min(MAX_EVENING_CAP_MINUTES, eveningCap),
                densityDelta, indoorPreferred, pace);
    }

    private static boolean hasCafeTheme(List<String> themes) {
        return themes != null && themes.stream()
                .anyMatch(t -> t != null && CAFE_STOP_THEMES.contains(t.trim().toLowerCase()));
    }
}
