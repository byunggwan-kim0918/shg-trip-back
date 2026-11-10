package com.shg.trip.shgtrip.domain.tripchat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param kind text(직접 입력)·unknown("아직 모르겠어요")·none("정해둔 건 없어요")
 * @param clientTurnId 같은 답을 다시 보내도 한 번만 반영한다
 */
public record TripChatAnswerRequest(
        @NotBlank @Size(max = 64) String questionId,
        @NotBlank @Size(max = 64) String clientTurnId,
        @NotBlank @Pattern(regexp = "text|unknown|none") String kind,
        @Size(max = 700, message = "답변은 700자 이내로 입력해주세요.") String text
) {}
