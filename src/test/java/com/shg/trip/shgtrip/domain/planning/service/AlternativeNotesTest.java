package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlternativeNotesTest {

    private static final String MEAL = "Dining and Drinking > Restaurant";
    private static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House";
    private static final String BEACH = "Landmarks and Outdoors > Beach";

    @Test
    @DisplayName("대안 설명은 종류, 지금 장소에서의 도로 환산 거리, 널리 알려진 곳 표시로 만든다")
    void describesKindDistanceAndFame() {
        PlaceCandidate current = place(1, "할매국밥", MEAL, "MEAL", 35.1000, 129.0300, null);

        assertThat(AlternativeNotes.describe(place(2, "바다횟집", MEAL, "MEAL", 35.1030, 129.0300, null), current))
                .isEqualTo("회 · 지금 장소에서 450m");
        assertThat(AlternativeNotes.describe(place(3, "해운대해수욕장", BEACH, "SIGHT", 35.1590, 129.1600, 40), current))
                .startsWith("해변 · 지금 장소에서 ").endsWith("km · 널리 알려진 곳");
    }

    @Test
    @DisplayName("종류를 모르면 역할로 부르고, 지금 장소나 그 좌표가 없으면 거리를 뺀다")
    void fallsBackToRoleAndSkipsUnknownDistance() {
        PlaceCandidate noCoords = new PlaceCandidate(1, 1L, "어딘가", "주소", MEAL, List.of(), "Busan", "KR", null, null,
                null, null, 0.8, null, null, null, 60, false, null, null, null, null, "MEAL", null, false, null);

        assertThat(AlternativeNotes.describe(place(2, "온새미로", MEAL, "MEAL", 35.1, 129.03, null), noCoords))
                .isEqualTo("식당");
        assertThat(AlternativeNotes.describe(place(2, "온새미로", MEAL, "MEAL", 35.1, 129.03, null), null))
                .isEqualTo("식당");
        assertThat(AlternativeNotes.label(place(3, "모퉁이", CAFE, "CAFE", 35.1, 129.03, null))).isEqualTo("카페");
    }

    @Test
    @DisplayName("거리는 1km 미만은 50m 단위, 10km 미만은 소수 한 자리, 그 이상은 정수 km이고 반올림 경계에서 단위가 넘어간다")
    void formatsDistance() {
        assertThat(AlternativeNotes.distance(0.01)).isEqualTo("50m");
        assertThat(AlternativeNotes.distance(0.62)).isEqualTo("600m");
        assertThat(AlternativeNotes.distance(0.98)).isEqualTo("1.0km");
        assertThat(AlternativeNotes.distance(9.97)).isEqualTo("10km");
        assertThat(AlternativeNotes.distance(3.46)).isEqualTo("3.5km");
        assertThat(AlternativeNotes.distance(23.6)).isEqualTo("24km");
    }

    @Test
    @DisplayName("스텝 설명은 종류와 널리 알려진 곳 표시만 담는다")
    void summarizesKindAndFame() {
        assertThat(AlternativeNotes.summary(place(3, "해운대해수욕장", BEACH, "SIGHT", 35.1590, 129.1600, 40)))
                .isEqualTo("해변 · 널리 알려진 곳");
        assertThat(AlternativeNotes.summary(place(2, "바다횟집", MEAL, "MEAL", 35.1030, 129.0300, null))).isEqualTo("회");
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat, double lng,
                                        Integer sitelinks) {
        return new PlaceCandidate(index, (long) index, name, "부산 해운대구 테스트로 " + index, category, List.of(), "Busan",
                "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60, false,
                null, null, null, sitelinks, role, null, false, null);
    }
}
