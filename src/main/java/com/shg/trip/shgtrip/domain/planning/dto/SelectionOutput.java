package com.shg.trip.shgtrip.domain.planning.dto;

import java.util.List;

public record SelectionOutput(
    String concept,
    List<DayPlan> days,
    List<List<Integer>> pairs,
    List<Integer> spareIndices,
    List<Integer> highlightIndices,
    List<Integer> restIndices
) {
    /** 하위 호환 생성자 — highlight/rest 미지정 시 빈 리스트(서사 흐름 가중치 비활성). */
    public SelectionOutput(String concept, List<DayPlan> days, List<List<Integer>> pairs,
                            List<Integer> spareIndices) {
        this(concept, days, pairs, spareIndices, List.of(), List.of());
    }

    public record DayPlan(
        int dayNumber,
        Integer arrivalHubIndex,
        List<Integer> placeIndices,
        Integer accommodationIndex,
        Integer departureHubIndex
    ) {}
}
