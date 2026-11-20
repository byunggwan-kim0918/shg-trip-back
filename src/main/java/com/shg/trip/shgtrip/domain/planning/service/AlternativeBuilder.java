package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.placeKey;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 스텝별 대안 장소. */
final class AlternativeBuilder {

    private AlternativeBuilder() {
    }


    /** 대안은 날짜 순서대로 만들어 뒷날에 본일정으로 들어간 장소가 앞날 대안에 남는다. */
    static List<StepData> pruneUsedAndRenumber(List<StepData> steps) {
        Set<String> usedPlaceKeys = steps.stream()
                .map(StepData::place)
                .filter(Objects::nonNull)
                .map(p -> placeKey(p.name(), p.address()))
                .collect(Collectors.toSet());
        int order = 1;
        List<StepData> renumbered = new ArrayList<>(steps.size());
        for (StepData step : steps) {
            renumbered.add(withoutUsedAlternatives(step, usedPlaceKeys).withStepOrder(order++));
        }
        return renumbered;
    }

    static StepData withoutUsedAlternatives(StepData s, Set<String> usedPlaceKeys) {
        if (s.alternatives() == null || s.alternatives().isEmpty()) return s;
        List<AlternativeData> filtered = s.alternatives().stream()
                .filter(a -> !usedPlaceKeys.contains(placeKey(a.name(), a.address())))
                .collect(Collectors.toList());
        if (filtered.size() == s.alternatives().size()) return s;
        return new StepData(s.stepOrder(), s.dayNumber(), s.startTime(), s.endTime(),
                s.place(), filtered, s.transportationMode(),
                s.transportationDuration(), s.transportationDistance(),
                s.transportationCost(), s.notes(), s.estimatedCost());
    }

}
