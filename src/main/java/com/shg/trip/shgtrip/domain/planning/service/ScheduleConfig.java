package com.shg.trip.shgtrip.domain.planning.service;

final class ScheduleConfig {
    final int morningStart;
    final int eveningCap;
    /** 시간창 용량 보정(활동 개수 ±). */
    final int densityDelta;
    /** 실내 성향 테마 여부 — 저녁 버킷 배치에 관대. */
    final boolean indoorPreferred;
    /**
     * 사용자가 고른 pace 원문(tight/normal/relaxed, null 가능) — 거리 상한 계수
     * ({@link ScheduleTuning#distanceLimits})에 쓴다. cfg는 이미 모든 배치 경로에 전달되므로
     * pace를 별도 파라미터로 또 흘리지 않기 위해 여기 둔다.
     */
    final String pace;
    /**
     * 마지막 날을 뺀 매일 점심과 저녁 사이에 카페·디저트 1곳을 넣는지. 맛집·로맨틱 테마나 커플 여행이면
     * 켠다 — 오후 카페는 이런 여행의 기본 구성인데, 없으면 "식당 → 관광 → 식당"만 반복된다(실측 115).
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
}
