package com.shg.trip.shgtrip.domain.tripchat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param clientRequestId 같은 값으로 다시 오면 새로 만들지 않고 기존 대화를 돌려준다(두 탭·재전송) */
public record CreateTripChatRequest(
        @NotBlank @Size(max = 64) String clientRequestId
) {}
