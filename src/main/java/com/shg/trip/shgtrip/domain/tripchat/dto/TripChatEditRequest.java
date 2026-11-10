package com.shg.trip.shgtrip.domain.tripchat.dto;

import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import jakarta.validation.constraints.NotNull;

public record TripChatEditRequest(
        @NotNull Long expectedVersion,
        @NotNull Slot slot
) {}
