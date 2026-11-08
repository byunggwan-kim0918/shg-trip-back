package com.shg.trip.shgtrip.domain.planning.service;

/** {@code MORNING_ONLY}는 긴 등산처럼 오전에 시작해야만 성립해 오후·저녁 버킷에 넣지 않는다. */
enum TimePreference { DAYTIME, EVENING, FLEXIBLE, MORNING_ONLY }
