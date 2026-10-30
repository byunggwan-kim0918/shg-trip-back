package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 모델이 스키마 밖 필드(실측: "placeIndices_comment")를 덧붙여도 일정 생성 전체가 실패하지 않게 무시한다. */
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
    /** 하위 호환 생성자 — highlight/rest 미지정 시 빈 리스트(서사 흐름 가중치 비활성). */
    public SelectionOutput(String concept, List<DayPlan> days, List<List<Integer>> pairs,
                            List<Integer> spareIndices) {
        this(concept, days, pairs, spareIndices, List.of(), List.of());
    }

    /** 하위 호환 생성자 — 시간대 의도 없음. */
    public SelectionOutput(String concept, List<DayPlan> days, List<List<Integer>> pairs,
                            List<Integer> spareIndices, List<Integer> highlightIndices,
                            List<Integer> restIndices) {
        this(concept, days, pairs, spareIndices, highlightIndices, restIndices, List.of());
    }

    /**
     * days만 바꾼 사본. 필드를 나열해 다시 만들면 새 필드가 추가될 때 그 경로에서 조용히 유실된다
     * (highlight/rest가 4-인자 생성자로 사라졌던 것과 같은 부류).
     */
    public SelectionOutput withDays(List<DayPlan> newDays) {
        return new SelectionOutput(concept, newDays, pairs, spareIndices, highlightIndices, restIndices, timing);
    }

    /** days와 spare를 바꾼 사본. */
    public SelectionOutput withDaysAndSpare(List<DayPlan> newDays, List<Integer> newSpare) {
        return new SelectionOutput(concept, newDays, pairs, newSpare, highlightIndices, restIndices, timing);
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
