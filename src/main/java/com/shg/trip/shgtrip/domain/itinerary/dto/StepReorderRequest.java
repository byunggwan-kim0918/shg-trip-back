package com.shg.trip.shgtrip.domain.itinerary.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 같은 day 내 스텝 드래그 재정렬 요청.
 * orderedStepIds는 해당 day에 속한 모든 스텝 id를 새 순서대로 나열한 것이어야 한다
 * (누락·중복·타 day/타 일정 stepId 주입은 서버에서 거부).
 */
public record StepReorderRequest(

        @NotNull(message = "dayNumber는 필수입니다.")
        Integer dayNumber,

        @NotEmpty(message = "재정렬할 스텝 순서가 비어 있습니다.")
        List<Long> orderedStepIds
) {
}
