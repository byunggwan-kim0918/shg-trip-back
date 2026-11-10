package com.shg.trip.shgtrip.domain.tripchat.model;

public enum FieldSource {
    USER,
    /** 사용자가 망설이며 말한 값("흠 걸어서?"). 사용자 값이지만 확정으로 표시하지 않는다. */
    TENTATIVE,
    INFERRED;

    public boolean fromUser() {
        return this == USER || this == TENTATIVE;
    }
}
