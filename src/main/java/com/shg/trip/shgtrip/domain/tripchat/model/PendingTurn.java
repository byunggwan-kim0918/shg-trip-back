package com.shg.trip.shgtrip.domain.tripchat.model;

import java.time.OffsetDateTime;

/** 해석 중인 답. 같은 답이 두 번 해석되거나, 늦게 끝난 해석이 그 사이 바뀐 상태를 덮어쓰지 않게 한다. */
public record PendingTurn(String clientTurnId, String text, OffsetDateTime startedAt) {}
