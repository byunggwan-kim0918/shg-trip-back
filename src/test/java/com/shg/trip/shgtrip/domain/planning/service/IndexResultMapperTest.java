package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * injectRequiredPlaces — 사용자 필수 방문 장소(userSelected)가 Sonnet 출력에서 누락됐을 때
 * 결정론적으로 주입되는지 검증. 프롬프트 ★ 지시는 힌트일 뿐, 최종 보장은 이 코드가 담당한다.
 */
class IndexResultMapperTest {

    private final IndexResultMapper mapper = new IndexResultMapper();

    private PlaceCandidate place(int index, String name, String category, double lat, double lng,
                                 boolean userSelected) {
        PlaceCandidate c = new PlaceCandidate(index, (long) index, name, "addr-" + index, category,
                List.of(), "Jeju", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                "d", BigDecimal.valueOf(4.3), 0.9);
        return userSelected ? c.asUserSelected() : c;
    }

    @Test
    @DisplayName("누락된 필수 장소는 spare에서 제거되고 centroid가 가장 가까운 day에 주입된다")
    void injectRequiredPlaces_missingPlaceInjectedIntoNearestDay() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Day1근처", "Landmarks and Outdoors > Park", 33.50, 126.50, false),
                place(2, "Day2근처", "Landmarks and Outdoors > Park", 33.25, 126.40, false),
                // 필수 장소 — Day2 클러스터 인근인데 Sonnet이 누락하고 spare에 넣음
                place(3, "필수장소", "Landmarks and Outdoors > Park", 33.26, 126.41, true),
                place(4, "Hotel", "Travel and Transportation > Lodging > Hotel", 33.40, 126.45, false)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1), 4, null),
                        new SelectionOutput.DayPlan(2, null, List.of(2), null, null)
                ),
                List.of(),
                List.of(3)
        );

        SelectionOutput fixed = mapper.injectRequiredPlaces(selection, candidates);

        assertThat(fixed.days().get(1).placeIndices()).contains(3); // Day2(최근접)에 주입
        assertThat(fixed.spareIndices()).doesNotContain(3);
    }

    @Test
    @DisplayName("이미 포함된 필수 장소는 그대로 두되 spare 중복 기재만 정리한다")
    void injectRequiredPlaces_alreadyIncludedOnlyCleansSpare() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "필수장소", "Landmarks and Outdoors > Park", 33.50, 126.50, true),
                place(2, "Hotel", "Travel and Transportation > Lodging > Hotel", 33.51, 126.51, false)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1), 2, null)),
                List.of(),
                List.of(1) // 프롬프트 위반: 메인과 spare 중복 기재
        );

        SelectionOutput fixed = mapper.injectRequiredPlaces(selection, candidates);

        assertThat(fixed.days().get(0).placeIndices()).containsExactly(1);
        assertThat(fixed.spareIndices()).doesNotContain(1);
    }

    @Test
    @DisplayName("필수 숙소(LODGING)가 어느 day의 숙소도 아니면 첫 day의 숙소로 교체된다")
    void injectRequiredPlaces_lodgingReplacesFirstDayAccommodation() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "Park", "Landmarks and Outdoors > Park", 33.50, 126.50, false),
                place(2, "기본호텔", "Travel and Transportation > Lodging > Hotel", 33.51, 126.51, false),
                place(3, "필수호텔", "Travel and Transportation > Lodging > Resort", 33.52, 126.52, true)
        );
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1), 2, null)),
                List.of(),
                List.of(3)
        );

        SelectionOutput fixed = mapper.injectRequiredPlaces(selection, candidates);

        assertThat(fixed.days().get(0).accommodationIndex()).isEqualTo(3);
        assertThat(fixed.spareIndices()).doesNotContain(3);
    }

    @Test
    @DisplayName("필수 숙소는 4일+ 여행에서 마지막날(귀가일) 제외 전 일정 숙소로 교체되고, 밀려난 숙소는 spare로 회수된다")
    void injectRequiredPlaces_lodgingReplacesAllDaysAndRecoversDisplaced() {
        List<PlaceCandidate> candidates = List.of(
                place(1, "D1", "Landmarks and Outdoors > Park", 33.50, 126.50, false),
                place(2, "D2", "Landmarks and Outdoors > Park", 33.51, 126.51, false),
                place(3, "D3", "Landmarks and Outdoors > Park", 33.52, 126.52, false),
                place(4, "기본호텔", "Travel and Transportation > Lodging > Hotel", 33.53, 126.53, false),
                place(5, "필수호텔", "Travel and Transportation > Lodging > Resort", 33.54, 126.54, true)
        );
        // 4일 여행: 1~3일 숙소=기본호텔(4), 4일차=귀가(null). 필수호텔(5)이 누락+spare.
        SelectionOutput selection = new SelectionOutput(
                "concept",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(1), 4, null),
                        new SelectionOutput.DayPlan(2, null, List.of(2), 4, null),
                        new SelectionOutput.DayPlan(3, null, List.of(3), 4, null),
                        new SelectionOutput.DayPlan(4, null, List.of(), null, null)
                ),
                List.of(),
                List.of(5)
        );

        SelectionOutput fixed = mapper.injectRequiredPlaces(selection, candidates);

        // 1~3일 모두 필수호텔(5), 귀가일(4일)은 null 유지
        assertThat(fixed.days().get(0).accommodationIndex()).isEqualTo(5);
        assertThat(fixed.days().get(1).accommodationIndex()).isEqualTo(5);
        assertThat(fixed.days().get(2).accommodationIndex()).isEqualTo(5);
        assertThat(fixed.days().get(3).accommodationIndex()).isNull();
        // 밀려난 기본호텔(4)은 어디에도 안 쓰이므로 spare로 회수
        assertThat(fixed.spareIndices()).contains(4);
        assertThat(fixed.spareIndices()).doesNotContain(5);
    }
}
