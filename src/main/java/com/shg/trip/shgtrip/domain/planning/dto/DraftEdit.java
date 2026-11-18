package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 다 지은 일정에 모델이 내는 수정 한 건.
 *
 * @param op         remove(빼기) · replace(바꾸기) · add(넣기) · move(다른 날로 옮기기)
 * @param target     고칠 장소. 일정에 적힌 이름 그대로다. add면 null이거나 새 장소를 넣을 바로 앞 장소다
 * @param name       새 장소. 관광지는 정식 이름, 끼니·카페는 음식 종류·성격이다. remove·move면 null
 * @param slot       add·move에서 넣을 시간대(morning·lunch·afternoon·dinner·evening)
 * @param toDay      move의 도착 날짜
 * @param placeNamed name이 식당·카페의 실제 이름이면 true
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftEdit(String op, Integer day, String target, String name, String kind, String slot,
                        @JsonProperty("to_day") Integer toDay, @JsonProperty("place_named") boolean placeNamed,
                        String reason) {
}
