package com.shg.trip.shgtrip.domain.tripchat.dto;

import jakarta.validation.constraints.NotNull;

public record TripChatVersionRequest(@NotNull Long expectedVersion) {}
