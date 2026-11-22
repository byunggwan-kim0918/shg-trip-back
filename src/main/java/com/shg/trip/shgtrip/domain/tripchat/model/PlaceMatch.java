package com.shg.trip.shgtrip.domain.tripchat.model;

import java.time.DayOfWeek;
import java.util.Set;

/**
 * @param major        PlaceCategoryConstants.majorCategory 값
 * @param openingHours 영업시간 원문. 모르면 null
 * @param closedDays   장소 사실의 정기 휴무 요일. 모르면 null이고, 이 값을 넣기 전에 맞춘 장소도 null이다
 */
public record PlaceMatch(Long placeId, String name, String major, String openingHours, Set<DayOfWeek> closedDays) {

    public PlaceMatch(Long placeId, String name, String major) {
        this(placeId, name, major, null, null);
    }
}
