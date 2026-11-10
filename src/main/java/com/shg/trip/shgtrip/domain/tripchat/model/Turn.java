package com.shg.trip.shgtrip.domain.tripchat.model;

import java.time.OffsetDateTime;

/** @param answerKind text·unknown·none */
public record Turn(
        String clientTurnId,
        String questionId,
        Slot slot,
        QuestionKind kind,
        String question,
        String answer,
        String answerKind,
        OffsetDateTime at
) {}
