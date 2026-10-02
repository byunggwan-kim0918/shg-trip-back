package com.shg.trip.shgtrip.domain.planning.service;

/** 식사 슬롯 배정 결과(없는 슬롯은 null). */
record MealSlots(Integer breakfast, Integer lunch, Integer dinner) {}
