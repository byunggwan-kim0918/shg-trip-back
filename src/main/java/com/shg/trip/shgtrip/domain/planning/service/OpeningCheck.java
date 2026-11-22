package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.time.LocalDate;

/**
 * 저장된 일정의 한 스텝 자리에 장소를 넣었을 때 그 날짜·시각에 영업하는지. 생성 때와 같은 영업시간 판독을 쓰고,
 * 판정 규칙처럼 숙소·교통 거점의 영업시간은 보지 않는다.
 */
public final class OpeningCheck {

    private OpeningCheck() {
    }

    /** @return 영업시간이나 날짜·시각을 모르거나 숙소·교통 거점이면 null */
    public static Boolean openDuring(Place place, LocalDate date, String startTime, String endTime) {
        Integer start = ScheduleTimes.toMinutesOrNull(startTime);
        Integer end = ScheduleTimes.toMinutesOrNull(endTime);
        if (!keepsHours(place) || date == null || start == null || end == null
                || OpeningHoursText.rangesFor(hoursOf(place), date.getDayOfWeek()) == null) {
            return null;
        }
        return OpeningHoursText.isOpenDuring(hoursOf(place), date.getDayOfWeek(), start, end);
    }

    public static boolean closedOn(Place place, LocalDate date) {
        return keepsHours(place) && date != null
                && OpeningHoursText.isClosedOnDay(hoursOf(place), date.getDayOfWeek());
    }

    private static String hoursOf(Place place) {
        return DayTiming.openingHoursOf(place.getName(), place.getCategory(), place.getOpeningHours());
    }

    private static boolean keepsHours(Place place) {
        if (place == null) return false;
        CandidatePolicy.Role role = CandidatePolicy.roleOf(SavedItineraryFacts.candidateOf(place));
        return role != CandidatePolicy.Role.LODGING && role != CandidatePolicy.Role.TRANSIT;
    }
}
