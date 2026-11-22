package com.shg.trip.shgtrip.domain.planning.dto;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 일정이 지켜야 하는 여행 조건. 최종 검사가 이것으로 일정을 받아들일지 정한다.
 *
 * @param wants      사용자가 꼭 넣어 달라고 한 장소·음식·경험
 * @param cityDays   도시별 머무는 날 수. 정하지 않았으면 비어 있다
 * @param avoid      피할 음식·활동
 * @param lowMobility 오래 걷기 힘든 동행(부모님, 걷기 힘들다고 한 경우)
 * @param transport  car·transit·walk
 * @param arrival    첫날 도착 시각. 없으면 null
 * @param departure  마지막 날 출발 시각. 없으면 null
 * @param pace       relaxed·normal·tight. 모르면 null
 * @param chosenPlaceIds 사용자가 직접 고른 장소. 피할 조건에 걸려도 사용자가 고른 것이라 짓는 쪽이 남긴다
 */
public record TripConditions(List<Want> wants, Map<String, Integer> cityDays, List<PlaceRestriction> avoid,
                             boolean lowMobility, String transport, LocalTime arrival, LocalTime departure,
                             String pace, Set<Long> chosenPlaceIds) {

    public TripConditions {
        chosenPlaceIds = chosenPlaceIds == null ? Set.of() : Set.copyOf(chosenPlaceIds);
    }

    public TripConditions(List<Want> wants, Map<String, Integer> cityDays, List<PlaceRestriction> avoid,
                          boolean lowMobility, String transport, LocalTime arrival, LocalTime departure,
                          String pace) {
        this(wants, cityDays, avoid, lowMobility, transport, arrival, departure, pace, Set.of());
    }

    /**
     * @param text      요청에 쓴 말("성산일출봉 일출", "고등어회")
     * @param timing    sunrise·evening. 시각이 없는 요청이면 null
     * @param satisfy   이 요청을 채우는 장소 이름. 식당은 메뉴 정보가 없을 때만 이름으로 본다
     * @param food      먹어야 하는 음식. 식당 이름·메뉴에 이 말이 있어야 채운 것으로 본다. 음식 요청이 아니면 null
     * @param notInData 여행지 장소 데이터에서 이 요청을 채울 곳을 찾지 못했는지. 모르면 false다
     * @param infeasible 날짜·시각 조건으로 넣을 날이 없다고 짓는 쪽이 판단해 여행자에게 이미 알린 요청인지
     */
    public record Want(String text, String timing, List<String> satisfy, String food, boolean notInData,
                       boolean infeasible) {

        public Want(String text, String timing, List<String> satisfy, String food, boolean notInData) {
            this(text, timing, satisfy, food, notInData, false);
        }

        public Want(String text, String timing, List<String> satisfy, String food) {
            this(text, timing, satisfy, food, false, false);
        }
    }
}
