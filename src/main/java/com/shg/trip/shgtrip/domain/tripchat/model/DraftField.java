package com.shg.trip.shgtrip.domain.tripchat.model;

/**
 * 여행 조건 필드. 각 필드는 소속 항목이 있어, 그 항목을 고쳐 답하면 소속 필드만 다시 채운다.
 * 이름이 Haiku 출력 스키마의 enum 값이므로 바꾸면 프롬프트 설명도 함께 바꾼다.
 */
public enum DraftField {
    DESTINATION(Slot.CITY, false),
    START_DATE(Slot.DATES, false),
    END_DATE(Slot.DATES, false),
    /** "11|mid", "2027-3|whole"처럼 월과 구간(early·mid·late·whole). 연도는 말했을 때만. */
    DATE_APPROX(Slot.DATES, false),
    NIGHTS(Slot.DATES, false),
    COMPANION(Slot.COMPANY, false),
    PARTY_SIZE(Slot.COMPANY, false),
    PRIORITIES(Slot.MOOD, false),
    THEMES(Slot.MOOD, true),
    CATEGORIES(Slot.MOOD, true),
    PACE(Slot.MOOD, false),
    TRANSPORT(Slot.TRANSPORT, false),
    WALKING(Slot.TRANSPORT, false),
    /** booked·wish·none. 이름·지역은 LODGING. */
    LODGING_STATE(Slot.ANCHORS, false),
    LODGING(Slot.ANCHORS, false),
    MUST_VISIT(Slot.ANCHORS, true),
    BOOKED(Slot.ANCHORS, true),
    BUDGET(Slot.EXTRAS, false),
    /** total·per_person·excluding_lodging */
    BUDGET_SCOPE(Slot.EXTRAS, false),
    ARRIVAL_TIME(Slot.EXTRAS, false),
    DEPARTURE_TIME(Slot.EXTRAS, false),
    ARRIVAL_NOTE(Slot.EXTRAS, false),
    DEPARTURE_NOTE(Slot.EXTRAS, false),
    FOOD_LIKES(Slot.EXTRAS, true),
    FOOD_AVOID(Slot.EXTRAS, true),
    AVOID_PLACES(Slot.EXTRAS, true),
    AVOID_ACTIVITIES(Slot.EXTRAS, true),
    NOTES(Slot.EXTRAS, true);

    private final Slot slot;
    private final boolean list;

    DraftField(Slot slot, boolean list) {
        this.slot = slot;
        this.list = list;
    }

    public Slot slot() {
        return slot;
    }

    public boolean isList() {
        return list;
    }
}
