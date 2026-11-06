package com.shg.trip.shgtrip.domain.planning.service;

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

    ScheduleConfig withCafeStop(boolean enabled) {
        return new ScheduleConfig(morningStart, eveningCap, densityDelta, indoorPreferred, pace, enabled);
    }

    ScheduleConfig withWindow(int start, int cap) {
        if (start == morningStart && cap == eveningCap) return this;
        return new ScheduleConfig(start, cap, densityDelta, indoorPreferred, pace, cafeStop);
    }
}
