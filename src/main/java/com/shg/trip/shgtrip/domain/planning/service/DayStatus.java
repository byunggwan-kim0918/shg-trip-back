package com.shg.trip.shgtrip.domain.planning.service;

/**
 * day별 불변식 충족 상태 — 우선순위 역전 판정과 요약 로그가 같은 값을 본다.
 *
 * @param lodging         그날 묵을 숙소가 배정됐는지
 * @param lodgingRequired 숙박일인지(마지막날=귀가일은 false)
 */
record DayStatus(boolean lunch, boolean dinner, boolean dinnerRequired, boolean attraction,
                 boolean lodging, boolean lodgingRequired,
                 double totalKm, double maxLegKm, boolean withinBudget, int endMinutes) {}
