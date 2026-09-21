package com.shg.trip.shgtrip.domain.planning.service;

/**
 * 테마 하나의 하루 시간창 성향.
 *
 * @param morningStart     하루 시작 시각(분)
 * @param eveningCap       저녁 상한 시각(분)
 * @param densityDelta     시간창 용량(활동 개수) 보정. +1이면 하루 활동이 하나 더 들어간다
 * @param indoorPreferred  실내 성향(우천·야간에 강한 테마) — 저녁 버킷 배치에 관대해진다
 */
record ThemeSchedule(int morningStart, int eveningCap, int densityDelta, boolean indoorPreferred) {}
