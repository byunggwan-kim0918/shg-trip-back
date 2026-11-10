package com.shg.trip.shgtrip.domain.tripchat.entity;

import java.util.Set;

public enum TripChatStatus {
    ASKING,
    REVIEW,
    CONFIRMED,
    ARCHIVED;

    public static final Set<TripChatStatus> ACTIVE = Set.of(ASKING, REVIEW, CONFIRMED);
}
