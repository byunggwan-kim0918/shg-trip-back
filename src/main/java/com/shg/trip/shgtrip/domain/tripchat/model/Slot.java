package com.shg.trip.shgtrip.domain.tripchat.model;

import java.util.List;

/** 대화에서 묻는 항목. EXTRAS는 확인 화면의 "더 알려주고 싶은 게 있어요"로만 채운다. */
public enum Slot {
    CITY("여행지"),
    DATES("날짜"),
    COMPANY("동행"),
    MOOD("취향"),
    TRANSPORT("이동"),
    ANCHORS("정해둔 것"),
    EXTRAS("더 알려준 것");

    public static final List<Slot> ASK_ORDER = List.of(CITY, DATES, COMPANY, MOOD, TRANSPORT, ANCHORS);

    private final String label;

    Slot(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
