package com.shg.trip.shgtrip.domain.place.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 좌표·제목은 2026-11 searchKeyword2 실제 응답 기준. */
class TourApiMatcherTest {

    private static final String SIGHT = "Landmarks and Outdoors > Mountain";
    private static final String MUSEUM = "Arts and Entertainment > Museum";
    private static final String DINING = "Dining and Drinking > Restaurant";

    /** 기준점에서 북쪽으로 meters만큼 떨어진 Hit. */
    private static TourApiMatcher.Hit hit(String title, int type, double lat, double lng, double meters) {
        return new TourApiMatcher.Hit(title, "id-" + title, type, lat + meters / 111_000.0, lng);
    }

    @Test
    @DisplayName("괄호 병기를 떼고 찾고, 대형 명소의 좌표 차이(390m)를 허용한다")
    void bracketsAndOffset() {
        assertThat(TourApiMatcher.keyword("성산일출봉 (Seongsan Ilchulbong)")).isEqualTo("성산일출봉");
        var m = TourApiMatcher.pick("성산일출봉 (Seongsan Ilchulbong)", SIGHT, 33.46, 126.94, List.of(
                hit("성산흑돼지두루치기 성산일출봉점", 39, 33.46, 126.94, 716),
                hit("성산일출봉 [유네스코 세계자연유산]", 12, 33.46, 126.94, 390)));

        assertThat(m).get().extracting(x -> x.hit().title()).isEqualTo("성산일출봉 [유네스코 세계자연유산]");
    }

    @Test
    @DisplayName("이름이 같은 후보가 포함 관계 후보보다 우선한다(더 멀어도)")
    void exactBeatsPartial() {
        var m = TourApiMatcher.pick("국립중앙박물관", MUSEUM, 37.52, 126.98, List.of(
                hit("국립중앙박물관 어린이박물관", 14, 37.52, 126.98, 35),
                hit("국립중앙박물관", 14, 37.52, 126.98, 319)));

        assertThat(m).get().extracting(x -> x.hit().title()).isEqualTo("국립중앙박물관");
    }

    @Test
    @DisplayName("지역 접두가 붙은 표제도 포함 관계로 찾는다(경주 불국사)")
    void partialName() {
        var m = TourApiMatcher.pick("불국사 (佛國寺)", SIGHT, 35.79, 129.33, List.of(
                hit("경주 불국사 [유네스코 세계유산]", 12, 35.79, 129.33, 270),
                hit("불국사(서울)", 12, 35.79, 129.33, 275_257)));

        assertThat(m).get().extracting(x -> x.hit().title()).isEqualTo("경주 불국사 [유네스코 세계유산]");
    }

    @Test
    @DisplayName("멀리 있는 같은 이름·다른 유형·축제는 붙이지 않는다")
    void rejects() {
        assertThat(TourApiMatcher.pick("우도", SIGHT, 33.5, 126.95, List.of(
                hit("가우도", 12, 33.5, 126.95, 113_419)))).isEmpty();
        assertThat(TourApiMatcher.pick("청계천", SIGHT, 37.57, 126.98, List.of(
                hit("겨울, 청계천의 빛", 15, 37.57, 126.98, 15)))).isEmpty();
        assertThat(TourApiMatcher.pick("흑본오겹 함덕점", DINING, 33.54, 126.67, List.of(
                hit("흑본오겹 함덕점", 12, 33.54, 126.67, 9)))).isEmpty();
        assertThat(TourApiMatcher.pick("중문색달해수욕장", SIGHT, 33.24, 126.41, List.of(
                hit("중문색달해수욕장", 12, 33.24, 126.41, 1200)))).isEmpty();
    }

    @Test
    @DisplayName("식당은 음식점(39)만 붙인다")
    void diningType() {
        assertThat(TourApiMatcher.pick("흑본오겹 함덕점", DINING, 33.54, 126.67, List.of(
                hit("흑본오겹 함덕점", 39, 33.54, 126.67, 9)))).isPresent();
    }
}
