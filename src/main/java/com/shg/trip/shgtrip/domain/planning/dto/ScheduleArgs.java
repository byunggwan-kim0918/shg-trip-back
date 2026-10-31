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
        boolean couple
) {
    public static ScheduleArgs of(VectorEnrichedInput input, boolean compact) {
        return new ScheduleArgs(input.pace(), input.transportPref(), input.startDate(), input.themes(),
                input.transportationHub(), compact, input.categories(),
                input.companion() == VectorEnrichedInput.CompanionType.COUPLE);
    }
}
