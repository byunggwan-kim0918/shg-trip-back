package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 모델이 장소 이름으로 쓴 일정 초안. 좌표·시각·이동은 담지 않고, 코드가 이름을 장소에 연결한 뒤 시간표를 짓는다.
 *
 * @param concept 여행 전체를 한 문장으로
 * @param wants   사용자가 직접 요구한 것. 모델이 추천한 곳은 담지 않는다. 요구가 없으면 비어 있다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftPlan(String concept, List<Want> wants, List<Day> days) {

    /**
     * @param kind    place(장소에 가기)·food(메뉴 먹기)·activity(시각이 핵심인 경험)·city(도시 배분)
     * @param text    요청에 쓴 말
     * @param place   가야 하는 장소의 정식 이름. 없으면 null
     * @param food    먹어야 하는 메뉴. 없으면 null
     * @param time    sunrise·morning·afternoon·evening·night. 시각이 상관없으면 null
     * @param satisfy 이 요구를 채우는 실제 장소·식당 이름. 메뉴 정보가 있는 식당은 메뉴로 다시 확인한다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Want(String id, String kind, String text, String place, String food, String time,
                       List<String> satisfy) {
    }

    /**
     * @param area        그날 도는 권역
     * @param lodgingArea 그날 밤 숙소 권역. 마지막 날은 null
     * @param spares      일정이 비거나 갈 수 없을 때 대신 넣을 근처 장소
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Day(int day, String area, @JsonProperty("lodging_area") String lodgingArea,
                      List<Item> items, List<Item> spares) {
    }

    /**
     * @param slot       morning·lunch·afternoon·dinner·evening
     * @param kind       sight·experience·night·market·shop·cafe·meal
     * @param name       장소 정식 이름, 또는 이름이 아닌 경우(placeNamed=false) 음식 종류·카페 성격
     * @param area       동네. 이름이 아닌 항목을 그 근처에서 찾는다
     * @param want       이 항목이 채우는 요구 id. 모델이 추천한 항목은 null이다
     * @param experience 추천한 경험("신라 고분 산책"). 같은 경험의 예비 장소로 바꿔도 하루의 취지가 남는다
     * @param reason     이 여행자에게 이 경험을 넣은 이유. 여행자에게 보이는 문장이라 장소 이름·시각을 담지 않는다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String slot, String kind, String name, String area,
                       @JsonProperty("place_named") boolean placeNamed, String want, String experience,
                       String reason) {

        public Item(String slot, String kind, String name, String area, boolean placeNamed) {
            this(slot, kind, name, area, placeNamed, null, null, null);
        }
    }
}
