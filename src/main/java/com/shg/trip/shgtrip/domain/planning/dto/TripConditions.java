package com.shg.trip.shgtrip.domain.planning.dto;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 일정이 지켜야 하는 여행 조건. 최종 검사가 이것으로 일정을 받아들일지 정한다.
 *
 * @param wants      사용자가 넣어 달라고 한 장소·음식·경험
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

    /** 요구를 일정에 넣을 수 있는지. 사용자가 그 요구를 원하는지와 따로 본다. */
    public enum Reach {
        /** 채울 장소를 데이터에서 찾았고 넣을 날도 있다. */
        PLACEABLE,
        /** 채울 장소를 데이터에서 찾지 못했다. 그런 곳이 없다는 뜻이 아니다. */
        NOT_FOUND,
        /** 날짜·도착·출발·해 뜨고 지는 시각으로 넣을 날이 없다. */
        NO_SLOT
    }

    /**
     * @param text     요청에 쓴 말("성산일출봉 일출", "고등어회")
     * @param timing   sunrise·evening. 시각이 없는 요청이면 null
     * @param satisfy  이 요청을 채우는 장소 이름. 식당은 메뉴 정보가 없을 때만 이름으로 본다
     * @param food     먹어야 하는 음식. 식당 이름·메뉴에 이 말이 있어야 채운 것으로 본다. 음식 요청이 아니면 null
     * @param reach    넣을 수 있는지. null이면 {@link Reach#PLACEABLE}
     * @param required 사용자가 꼭 갈 곳·꼭 할 경험으로 직접 정한 요구인지. 초안이 요청 문장에서 읽은 요구는 false다
     * @param accepted 넣지 못한다는 것을 대화에서 듣고도 그대로 진행하기로 했는지
     */
    public record Want(String text, String timing, List<String> satisfy, String food, Reach reach,
                       boolean required, boolean accepted) {

        public Want {
            reach = reach == null ? Reach.PLACEABLE : reach;
        }

        public Want(String text, String timing, List<String> satisfy, String food) {
            this(text, timing, satisfy, food, Reach.PLACEABLE, false, false);
        }
    }
}
