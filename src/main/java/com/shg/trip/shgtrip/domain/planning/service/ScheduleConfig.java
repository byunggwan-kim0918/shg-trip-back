package com.shg.trip.shgtrip.domain.planning.service;

final class ScheduleConfig {
    final int morningStart;
    final int eveningCap;
    /** 시간창 용량 보정(활동 개수 ±). */
    final int densityDelta;
    /** 실내 성향 테마 여부 — 저녁 버킷 배치에 관대. */
    final boolean indoorPreferred;

    ScheduleConfig(int morningStart, int eveningCap, int densityDelta, boolean indoorPreferred) {
        this.morningStart = morningStart;
        this.eveningCap = eveningCap;
        this.densityDelta = densityDelta;
        this.indoorPreferred = indoorPreferred;
    }
}
