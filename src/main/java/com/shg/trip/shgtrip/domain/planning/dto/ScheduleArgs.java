package com.shg.trip.shgtrip.domain.planning.dto;

import java.time.LocalDate;
import java.util.List;

public record ScheduleArgs(
        String pace,
        String transportPref,
        LocalDate startDate,
        List<String> themes,
        TransportationHub hub,
        boolean compact,
        List<String> categories,
        boolean couple,
        String arrivalTime,
        String departureTime,
        String country
) {
    public static ScheduleArgs of(VectorEnrichedInput input, boolean compact) {
        return of(input, compact, null, null);
    }

    /** @param arrivalTime·departureTime 첫날 도착·마지막 날 출발 "HH:mm", 모르면 null */
    public static ScheduleArgs of(VectorEnrichedInput input, boolean compact, String arrivalTime, String departureTime) {
        return new ScheduleArgs(input.pace(), input.transportPref(), input.startDate(), input.themes(),
                input.transportationHub(), compact, input.categories(),
                input.companion() == VectorEnrichedInput.CompanionType.COUPLE, arrivalTime, departureTime,
                input.country());
    }
}
