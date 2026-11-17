package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SightNoiseTest {

    private static PlaceCandidate cand(String name, String category, String role) {
        return new PlaceCandidate(1, 1L, name, "주소", category, List.of(), "Seoul", "KR",
                BigDecimal.ONE, BigDecimal.ONE, null, null, 0.9, null, null, null, null, false, null, null,
                null, null, role, null, false, null);
    }

    @Test
    @DisplayName("근린공원·키즈카페·입구 레코드는 보강 역할이 SIGHT여도 방문지가 아니다")
    void lowValueBeatsEnrichedRole() {
        assertThat(CandidatePolicy.roleOf(cand("효창근린공원", "Landmarks and Outdoors > Park", "SIGHT")))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
        assertThat(CandidatePolicy.roleOf(cand("점프 키즈카페 마포점", "Sports and Recreation > Indoor Play Area", "ACTIVITY")))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
        assertThat(CandidatePolicy.roleOf(cand("화성행궁 입구", "Landmarks and Outdoors > Historic and Protected Site", "SIGHT")))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
        assertThat(CandidatePolicy.roleOf(cand("키즈카페 놀자", "Dining and Drinking > Cafe, Coffee, and Tea House > Café", "CAFE")))
                .isEqualTo(CandidatePolicy.Role.NOT_VISITABLE);
    }

    @Test
    @DisplayName("경기장·전시장은 운동·문화시설 분류이거나 경기장 분류일 때 경기·행사 장소로 본다")
    void eventVenues() {
        assertThat(CandidatePolicy.isEventOnlySight("제주월드컵경기장", "Sports and Recreation > Outdoor Activity")).isTrue();
        assertThat(CandidatePolicy.isEventOnlySight("고척스카이돔", "Arts and Entertainment > Stadium > Baseball Stadium")).isTrue();
        assertThat(CandidatePolicy.isEventOnlySight("Bexco", "Arts and Entertainment > Cultural Center")).isTrue();
        assertThat(CandidatePolicy.roleOf(cand("제주월드컵경기장", "Sports and Recreation > Outdoor Activity", "SIGHT")))
                .isEqualTo(CandidatePolicy.Role.SIGHT);
    }

    @Test
    @DisplayName("이름에 경기장·체육관·벡스코가 들어도 식당·카페·항구·사적지·박물관이면 경기·행사 장소가 아니다")
    void namedPlacesThatAreNotVenues() {
        assertThat(CandidatePolicy.isEventOnlySight("우리풍천장어 꼼장어체육관",
                "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant")).isFalse();
        assertThat(CandidatePolicy.isEventOnlySight("더벤티 벡스코점", "Dining and Drinking > Cafe, Coffee, and Tea House"))
                .isFalse();
        assertThat(CandidatePolicy.isEventOnlySight("수영만 요트경기장", "Landmarks and Outdoors > Harbor or Marina")).isFalse();
        assertThat(CandidatePolicy.isEventOnlySight("동대문운동장 조명탑", "Landmarks and Outdoors > Historic and Protected Site"))
                .isFalse();
        assertThat(CandidatePolicy.isEventOnlySight("동대문운동장기념관", "Arts and Entertainment > Museum > History Museum"))
                .isFalse();
    }

    @Test
    @DisplayName("사용자가 직접 고른 곳은 명시 목록에 있어도 보강 역할을 쓴다")
    void userSelectedKeepsRole() {
        assertThat(CandidatePolicy.roleOf(cand("효창근린공원", "Landmarks and Outdoors > Park", "SIGHT").asUserSelected()))
                .isEqualTo(CandidatePolicy.Role.SIGHT);
    }

    @Test
    @DisplayName("탐방로 입구는 등산 출발점이라 남긴다")
    void trailheadStays() {
        assertThat(CandidatePolicy.roleOf(cand("관음사 탐방로 입구", "Landmarks and Outdoors > Hiking Trail", "SIGHT")))
                .isEqualTo(CandidatePolicy.Role.SIGHT);
    }

    @Test
    @DisplayName("목록 밖 관광지와 식당은 보강 역할을 그대로 쓴다")
    void othersKeepEnrichedRole() {
        assertThat(CandidatePolicy.roleOf(cand("서울숲", "Landmarks and Outdoors > Park", "SIGHT")))
                .isEqualTo(CandidatePolicy.Role.SIGHT);
        assertThat(CandidatePolicy.roleOf(cand("입구식당", "Dining and Drinking > Restaurant", "MEAL")))
                .isEqualTo(CandidatePolicy.Role.MEAL);
    }
}
