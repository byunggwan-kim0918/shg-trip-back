package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;

import java.time.LocalDate;
import java.util.List;

/** 커플이 아니고 도착·출발 시각을 모르는 한 도시 일정의 생성 인자. */
final class ScheduleTestArgs {

    private ScheduleTestArgs() {}

    static ScheduleArgs of(String pace, String transportPref, LocalDate startDate, List<String> themes,
                           TransportationHub hub, boolean compact, List<String> categories) {
        return new ScheduleArgs(pace, transportPref, startDate, themes, hub, compact, categories, false,
                null, null, null);
    }
}
