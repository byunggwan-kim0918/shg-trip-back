package com.shg.trip.shgtrip.domain.planning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SentenceParseRequest(

        @NotBlank(message = "문장을 입력해주세요.")
        @Size(max = 500, message = "문장은 최대 500자까지 입력할 수 있습니다.")
        String sentence
) {
}
