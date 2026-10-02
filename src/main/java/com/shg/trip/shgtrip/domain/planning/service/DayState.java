package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import java.util.ArrayList;
import java.util.List;

class DayState {
    final int dayNumber;
    Integer arrivalHubIndex;
    final List<Integer> placeIndices;
    Integer accommodationIndex;
    Integer departureHubIndex;

    DayState(SelectionOutput.DayPlan plan) {
        this.dayNumber = plan.dayNumber();
        this.arrivalHubIndex = plan.arrivalHubIndex();
        this.placeIndices = new ArrayList<>(plan.placeIndices() != null ? plan.placeIndices() : List.of());
        this.accommodationIndex = plan.accommodationIndex();
        this.departureHubIndex = plan.departureHubIndex();
    }
}
