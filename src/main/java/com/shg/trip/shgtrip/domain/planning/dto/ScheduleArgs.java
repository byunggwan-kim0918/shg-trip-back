package com.shg.trip.shgtrip.domain.planning.dto;

import java.time.LocalDate;
import java.util.List;

public record ScheduleArgs(
        String pace,
        String transportPref,
        LocalDate startDate,
        List<String> themes,
        TransportationHub hub,
        boolean compact,
        List<String> categories,
        boolean couple,
        String arrivalTime,
        String departureTime,
        String country,
        TripCities cities,
        boolean resumesAfterTransfer,
        boolean continuesAfter
) {
    /**
     * @param transportPref        walk / car / any. 하루 동선의 거리 상한이 달라진다
     * @param startDate            요일로 정기휴무 장소를 피한다. null이면 휴무 회피를 건너뛴다
     * @param themes               야경류는 저녁 상한을 늦추고 일출·등산류는 아침 시작을 당긴다. 비면 09:00~22:00
     * @param hub                  도착·출발 허브 이름. 공항 본체와 부속시설 POI 중 허브를 이름으로 고른다. null이면 이름 교정을 건너뛴다
     * @param compact              유효 관광지가 부족한 풀이면 하한 quota를 완화해 엉뚱한 카테고리로 채우지 않는다
     * @param categories           요청 카테고리 id. 일정에 없는 카테고리를 보충한다. 비면 커버리지 보충을 건너뛴다
     * @param couple               커플 여행이면 테마와 무관하게 매일 오후 카페 스톱을 넣는다
     * @param arrivalTime          첫날 도착 "HH:mm", 모르면 null
     * @param departureTime        마지막 날 출발 "HH:mm", 모르면 null
     * @param cities               먼 도시를 오가는 여행이면 도시 순서, 아니면 null
     * @param resumesAfterTransfer 도시 간 이동으로 시작하는 구간. 도착 허브를 두지 않는다
     * @param continuesAfter       다음 도시로 이어지는 구간. 마지막 날도 숙소·저녁이 필요하고 출발 허브를 두지 않는다
     */
    public ScheduleArgs(String pace, String transportPref, LocalDate startDate, List<String> themes,
                        TransportationHub hub, boolean compact, List<String> categories, boolean couple,
                        String arrivalTime, String departureTime, String country) {
        this(pace, transportPref, startDate, themes, hub, compact, categories, couple, arrivalTime, departureTime,
                country, null, false, false);
    }

    public static ScheduleArgs of(VectorEnrichedInput input, boolean compact) {
        return of(input, compact, null, null);
    }

    /** @param arrivalTime·departureTime 첫날 도착·마지막 날 출발 "HH:mm", 모르면 null */
    public static ScheduleArgs of(VectorEnrichedInput input, boolean compact, String arrivalTime, String departureTime) {
        return of(input, compact, arrivalTime, departureTime, null);
    }

    public static ScheduleArgs of(VectorEnrichedInput input, boolean compact, String arrivalTime, String departureTime,
                                  TripCities cities) {
        return new ScheduleArgs(input.pace(), input.transportPref(), input.startDate(), input.themes(),
                input.transportationHub(), compact, input.categories(),
                input.companion() == VectorEnrichedInput.CompanionType.COUPLE, arrivalTime, departureTime,
                input.country(), cities, false, false);
    }

    /** 도시 구간 하나의 인자. 구간도 여행 전체의 일차 번호를 쓰므로 시작일은 그대로 두고 도시 정보만 지운다. */
    public ScheduleArgs forCityBlock(TransportationHub blockHub, String blockArrival, String blockDeparture,
                                     boolean resumes, boolean continues) {
        return new ScheduleArgs(pace, transportPref, startDate, themes, blockHub, compact, categories, couple,
                blockArrival, blockDeparture, country, null, resumes, continues);
    }
}
