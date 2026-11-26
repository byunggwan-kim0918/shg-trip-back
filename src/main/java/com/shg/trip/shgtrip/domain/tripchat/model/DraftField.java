package com.shg.trip.shgtrip.domain.tripchat.model;

/**
 * 여행 조건 필드. 각 필드는 소속 항목이 있어, 그 항목을 고쳐 답하면 소속 필드만 다시 채운다.
 * 이름이 Haiku 출력 스키마의 enum 값이므로 바꾸면 프롬프트 설명도 함께 바꾼다.
 */
public enum DraftField {
    DESTINATION(Slot.CITY, false),
    /** 들르겠다고 한 도시·지역 하나가 항목 하나다. 여행지가 넓은 지역(홋카이도)이면 그 안에서 들를 곳들이다. */
    VISIT_AREAS(Slot.CITY, true),
    START_DATE(Slot.DATES, false),
    END_DATE(Slot.DATES, false),
    /** "11|mid", "2027-3|whole"처럼 월과 구간(early·mid·late·whole). 연도는 말했을 때만. */
    DATE_APPROX(Slot.DATES, false),
    NIGHTS(Slot.DATES, false),
    COMPANION(Slot.COMPANY, false),
    PARTY_SIZE(Slot.COMPANY, false),
    /** 하고 싶은 것 하나가 항목 하나다. 한 항목을 철회해도 같은 답의 다른 바람은 남는다. */
    PRIORITIES(Slot.MOOD, true),
    THEMES(Slot.MOOD, true),
    CATEGORIES(Slot.MOOD, true),
    PACE(Slot.MOOD, false),
    TRANSPORT(Slot.TRANSPORT, false),
    /** "지역|이동 방법". 이동 방법은 TRANSPORT와 같은 값이다. 지역마다 다르게 말했을 때만 쓴다. */
    AREA_TRANSPORT(Slot.TRANSPORT, true),
    WALKING(Slot.TRANSPORT, false),
    /** booked·wish·none. 이름·지역은 LODGING. */
    LODGING_STATE(Slot.ANCHORS, false),
    LODGING(Slot.ANCHORS, false),
    MUST_VISIT(Slot.ANCHORS, true),
    /** 숙박 순서 전체를 "지역|박 수,지역|박 수" 한 값으로. 목록이면 같은 도시로 돌아오는 밤이 앞 항목과 합쳐진다. */
    STAYS(Slot.ANCHORS, false),
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
