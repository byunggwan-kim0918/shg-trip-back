package com.shg.trip.shgtrip.domain.planning.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** day 구성 스냅샷 — 하위 수리가 상위 불변식을 깨면 이 상태로 되돌린다. */
record DaySnapshot(Map<Integer, List<Integer>> placeIndices,
                           Map<Integer, Integer[]> fixedSlots) {
    static DaySnapshot of(List<DayState> days) {
        Map<Integer, List<Integer>> places = new LinkedHashMap<>();
        Map<Integer, Integer[]> slots = new LinkedHashMap<>();
        for (DayState d : days) {
            places.put(d.dayNumber, new ArrayList<>(d.placeIndices));
            slots.put(d.dayNumber, new Integer[]{d.arrivalHubIndex, d.accommodationIndex, d.departureHubIndex});
        }
        return new DaySnapshot(places, slots);
    }

    void restore(List<DayState> days) {
        for (DayState d : days) {
            List<Integer> saved = placeIndices.get(d.dayNumber);
            if (saved != null) {
                d.placeIndices.clear();
                d.placeIndices.addAll(saved);
            }
            Integer[] slots = fixedSlots.get(d.dayNumber);
            if (slots != null) {
                d.arrivalHubIndex = slots[0];
                d.accommodationIndex = slots[1];
                d.departureHubIndex = slots[2];
            }
        }
    }
}
