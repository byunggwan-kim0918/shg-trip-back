package com.shg.trip.shgtrip.domain.itinerary.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * orderedStepIds는 해당 day의 모든 스텝 id를 새 순서로 나열해야 한다.
 * 누락·중복·다른 day나 다른 일정의 stepId는 서버가 거부한다.
 */
public record StepReorderRequest(

        @NotNull(message = "dayNumber는 필수입니다.")
        Integer dayNumber,

        @NotEmpty(message = "재정렬할 스텝 순서가 비어 있습니다.")
        List<Long> orderedStepIds
) {
}
