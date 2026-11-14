package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionPromptWindowTest {

    private final SelectionCallGenerator generator =
            new SelectionCallGenerator(null, new AnthropicProperties("claude-haiku-5-5", "claude-sonnet-5-5", 8192));

    private final List<PlaceCandidate> candidates = List.of(new PlaceCandidate(
            1, 1L, "Phố cổ Hội An", "Hội An", "Landmarks and Outdoors > Historic and Protected Site", List.of(),
            "Hoi An", "VN", BigDecimal.valueOf(15.877), BigDecimal.valueOf(108.326), null, null, 0.5,
            null, null, null, null, false, null, null, null, 9));

    private VectorEnrichedInput input() {
        return new VectorEnrichedInput(
                "다낭", List.of("ocean"), List.of("attraction"),
                "normal", "any", null,
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 13),
                "다낭 3박4일", null,
                "다낭", "VN", List.of("Da Nang", "Hoi An"), List.of("바다"), null,
                "MEDIUM", "겨울", "컨텍스트", null, null);
    }

    @Test
    @DisplayName("늦은 도착일과 출발일의 관광 가능 시간을 알려, 짧은 날에는 핵심 장소를 두지 말라고 한다")
    void dayWindowsAreStated() {
        ScheduleArgs args = ScheduleArgs.of(input(), false, "22:00", "17:00");

        String message = generator.buildUserMessage(input(), candidates, 4, args);

        assertThat(message).contains("- 1일차: 22:00 도착. 관광할 시간이 없으니 highlight·timing 장소는 다른 날에 두세요");
        assertThat(message).contains("- 4일차(마지막 날): 17:00 출발, 15:00까지 공항·역 도착, 관광 약 4시간");
    }

    @Test
    @DisplayName("먼 두 도시면 도시 순서·이동 시간과 이동한 날의 시작 시각을 알리고 후보에 도시 이름을 붙인다")
    void cityOrderIsStated() {
        TripCities.City tokyo = new TripCities.City("Tokyo", "도쿄", "JP", 35.6812, 139.7671);
        TripCities.City osaka = new TripCities.City("Osaka", "오사카", "JP", 34.6937, 135.5023);
        TripCities cities = new TripCities(List.of(tokyo, osaka), Map.of(1, "Tokyo", 2, "Tokyo", 3, "Osaka", 4, "Osaka"));
        List<PlaceCandidate> tokyoCandidate = List.of(new PlaceCandidate(
                1, 1L, "Senso-ji", "Asakusa", "Landmarks and Outdoors > Historic and Protected Site", List.of(),
                "Tokyo", "JP", BigDecimal.valueOf(35.7148), BigDecimal.valueOf(139.7967), null, null, 0.5,
                null, null, null, null, false, null, null, null, 9));

        String message = generator.buildUserMessage(input(), tokyoCandidate, 4,
                ScheduleArgs.of(input(), false, null, null, cities));

        assertThat(message.lines().filter(l -> l.startsWith("- 도시 순서") || l.startsWith("- 3일차")).toList())
                .containsExactly(
                        "- 도시 순서: 도쿄(1~2일차) → 열차 약 3시간 17분 → 오사카(3~4일차). 각 날의 장소·숙소는 그날 도시 후보에서만 고르세요",
                        "- 3일차: 도쿄 → 오사카 이동, 12:17 도착, 관광 약 6시간");
        assertThat(message).contains("| 도쿄 · ");
        assertThat(message).doesNotContain("지리 구역");
    }

    @Test
    @DisplayName("마지막 날에 도시를 옮기면 그날을 도착·출발과 함께 한 줄로만 알린다")
    void transferOnLastDayUsesDepartureDeadline() {
        TripCities.City tokyo = new TripCities.City("Tokyo", "도쿄", "JP", 35.6812, 139.7671);
        TripCities.City osaka = new TripCities.City("Osaka", "오사카", "JP", 34.6937, 135.5023);
        TripCities cities = new TripCities(List.of(tokyo, osaka), Map.of(1, "Tokyo", 2, "Tokyo", 3, "Tokyo", 4, "Osaka"));

        String message = generator.buildUserMessage(input(), candidates, 4,
                ScheduleArgs.of(input(), false, null, "20:00", cities));

        assertThat(message.lines().filter(l -> l.startsWith("- 도시 순서") || l.startsWith("- 4일차")).toList())
                .containsExactly(
                        "- 도시 순서: 도쿄(1~3일차) → 열차 약 3시간 17분 → 오사카(4일차). 각 날의 장소·숙소는 그날 도시 후보에서만 고르세요",
                        "- 4일차: 도쿄 → 오사카 이동, 12:17 도착, 20:00 출발, 18:00까지 공항·역 도착, 관광 약 4시간");
    }

    @Test
    @DisplayName("도착·출발 시각이 없으면 메시지가 지금과 같다")
    void noTimesKeepsMessage() {
        assertThat(generator.buildUserMessage(input(), candidates, 4, ScheduleArgs.of(input(), false)))
                .isEqualTo(generator.buildUserMessage(input(), candidates, 4));
    }
}
