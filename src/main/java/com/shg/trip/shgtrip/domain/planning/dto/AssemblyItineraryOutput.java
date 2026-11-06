package com.shg.trip.shgtrip.domain.planning.dto;

import java.util.List;

/**
 * 구조(day/순서/시간/교통/대안)는 RouteOptimizer가 이미 확정했으므로 Haiku는 story 텍스트만 생성한다.
 */
public record AssemblyItineraryOutput(
    String title,
    List<String> tags,
    List<StoryStep> steps
) {
    public record StoryStep(
        int stepOrder,
        String story
    ) {}
}
