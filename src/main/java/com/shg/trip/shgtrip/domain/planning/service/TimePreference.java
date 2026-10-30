package com.shg.trip.shgtrip.domain.planning.service;

/**
 * 장소의 적합 시간대. {@code MORNING_ONLY}는 긴 등산처럼 오전에 시작해야만 성립하는 장소다
 * (오후 버킷·저녁 버킷 어디에도 넣지 않는다).
 */
enum TimePreference { DAYTIME, EVENING, FLEXIBLE, MORNING_ONLY }
