package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 모델이 스키마 밖 필드(예: "placeIndices_comment")를 덧붙여도 일정 생성 전체가 실패하지 않게 무시한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SelectionOutput(
    String concept,
    List<DayPlan> days,
    List<List<Integer>> pairs,
    List<Integer> spareIndices,
    List<Integer> highlightIndices,
    List<Integer> restIndices,
    List<TimingHint> timing
) {
    /** highlight/rest는 빈 리스트로 두어 서사 흐름 가중치를 끈다. */
    public SelectionOutput(String concept, List<DayPlan> days, List<List<Integer>> pairs,
                            List<Integer> spareIndices) {
        this(concept, days, pairs, spareIndices, List.of(), List.of());
    }

    public SelectionOutput(String concept, List<DayPlan> days, List<List<Integer>> pairs,
                            List<Integer> spareIndices, List<Integer> highlightIndices,
                            List<Integer> restIndices) {
        this(concept, days, pairs, spareIndices, highlightIndices, restIndices, List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DayPlan(
        int dayNumber,
        Integer arrivalHubIndex,
        List<Integer> placeIndices,
        Integer accommodationIndex,
        Integer departureHubIndex
    ) {}

    /**
     * 요청 의도상 특정 시간대에 가야 의미가 있는 장소(야경 → evening, 아침시장 → morning).
     * 순서·시각은 백엔드가 정하므로, 이 힌트가 없으면 야경 명소가 낮에 배치된다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimingHint(int index, String when) {}
}
