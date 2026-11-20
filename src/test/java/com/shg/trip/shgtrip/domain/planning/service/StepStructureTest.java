package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepStructureTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";

    private static StepData step(int day, String start, String end, long id, String name, String category) {
        return new StepData(1, day, start, end, new PlaceData(name, null, category, "Busan", "KR", null, null, id, null),
                List.of(), null, null, null, null, null, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("시각이 이어지고 숙소만 같은 날 다시 나오면 문제가 없다")
    void validDay() {
        assertThat(StepStructure.problemOf(List.of(
                step(1, "09:00", "09:30", 9, "호텔", HOTEL), step(1, "10:00", "11:00", 1, "해동용궁사", SIGHT),
                step(1, "21:00", "21:30", 9, "호텔", HOTEL), step(2, "09:00", "10:00", 1, "해동용궁사", SIGHT)), List.of()))
                .isNull();
    }

    @Test
    @DisplayName("거점 역할 장소는 카테고리가 거점이 아니어도 같은 날 다시 나올 수 있다")
    void transitRoleReturnsSameDay() {
        String harbor = "Landmarks and Outdoors > Harbor or Marina";
        PlaceCandidate terminal = new PlaceCandidate(1, 5L, "여객선터미널", null, harbor, List.of(), "Jeju", "KR",
                BigDecimal.ONE, BigDecimal.ONE, null, null, 0.0, null, null, null, null, false, null, null, null, null,
                "TRANSIT", null, false, null, null, null, null);

        assertThat(StepStructure.problemOf(List.of(step(1, "09:00", "09:30", 5, "여객선터미널", harbor),
                step(1, "16:00", "16:30", 5, "여객선터미널", harbor)), List.of(terminal))).isNull();
    }

    @Test
    @DisplayName("같은 날 시각 겹침·같은 곳 두 번·형식 오류·뒤바뀐 일차를 막는다")
    void brokenDays() {
        assertThat(StepStructure.problemOf(List.of(step(1, "10:00", "11:00", 1, "A", SIGHT),
                step(1, "10:30", "11:30", 2, "B", SIGHT)), List.of())).contains("시각 겹침");
        assertThat(StepStructure.problemOf(List.of(step(1, "10:00", "11:00", 1, "A", SIGHT),
                step(1, "12:00", "13:00", 1, "A", SIGHT)), List.of())).contains("같은 날 두 번");
        assertThat(StepStructure.problemOf(List.of(step(1, "10시", "11:00", 1, "A", SIGHT)), List.of())).contains("시각 형식");
        assertThat(StepStructure.problemOf(List.of(step(2, "10:00", "11:00", 1, "A", SIGHT),
                step(1, "10:00", "11:00", 2, "B", SIGHT)), List.of())).contains("뒤에 옴");
    }
}
