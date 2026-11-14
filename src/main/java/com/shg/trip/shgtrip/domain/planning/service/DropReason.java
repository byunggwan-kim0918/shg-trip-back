package com.shg.trip.shgtrip.domain.planning.service;

/** 고른 장소가 그날 일정에 남지 않은 사유. {@link #OTHER}를 뺀 나머지는 하루 확정({@link DayScheduler#scheduleOne})이 남긴다. */
public enum DropReason {
    /** 구간 상한이나 하루 이동 상한을 넘긴다. */
    DISTANCE,
    /** 저녁 뒤 활동이 숙소에서 멀다. */
    FAR_FROM_LODGING,
    /** 오전·오후·저녁 시간 예산에 들어가지 않는다. */
    TIME_BUDGET,
    /** 출발 거점 도착 기한을 넘긴다. */
    DEPARTURE_TIME,
    /** 배정된 시각에 영업하지 않는다. */
    CLOSED_AT_TIME,
    /** 시작이 그 장소 유형의 시작 상한보다 늦다. */
    LATE_START,
    /** 하루 세 끼를 넘는 식당이다. */
    EXTRA_MEAL,
    /** 그날 있는 끼니 시각에 영업하지 않아 어느 끼니에도 들어가지 못한 식당이다. */
    NO_MEAL_SLOT,
    /** 아침 자리가 없는 날이거나 아침으로 두기에 멀다. */
    BREAKFAST,
    /** 하루 확정 밖에서 빠졌다. 다른 날과 겹쳤거나, 다른 도시 날에 놓였거나, 끼니를 넣을 자리를 내주었다. */
    OTHER
}
