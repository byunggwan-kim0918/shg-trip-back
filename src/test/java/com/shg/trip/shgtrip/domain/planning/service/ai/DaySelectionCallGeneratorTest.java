package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.planning.service.DaySelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DaySelectionCallGeneratorTest {

    private static final DaySelectionCallGenerator.TripContext TRIP = new DaySelectionCallGenerator.TripContext(
            "부산", List.of("바다", "야경"), "커플(2인)", "광안리 야경은 꼭 보고 싶어요", "relaxed", "car");

    @Test
    @DisplayName("하루 선택 문구에 여행 맥락, 관광 수 범위, 앞날 장소와 후보 표시(추천·꼭 넣을 곳·저녁)를 넣는다")
    void userMessageCarriesTheDayContext() {
        DaySelector.Option base = option(3, "해운대해수욕장", "Landmarks and Outdoors > Beach", null, 0.0, false, false);
        DaySelector.DayOptions options = new DaySelector.DayOptions(2, 3, false, base, List.of(base),
                List.of(option(10, "할매국밥", "Dining and Drinking > Restaurant", "gukbap", 1.2, true, false)),
                List.of(option(11, "바다횟집", "Dining and Drinking > Restaurant", "raw_fish", 2.5, false, false)),
                List.of(new DaySelector.Option(20, "광안리해수욕장", "Landmarks and Outdoors > Beach", null, 6.0, 90,
                        "EVENING", 15, false, true)),
                1, 2, List.of("1일차: 감천문화마을, 자갈치시장"));

        String message = DaySelectionCallGenerator.userMessage(options, TRIP);

        assertThat(message).contains("- 요청: 광안리 야경은 꼭 보고 싶어요", "- 동행: 커플(2인)",
                "## 2일차 (전체 3일)", "- 하루 관광 수: 1~2곳", "- 이미 놓인 장소: 해운대해수욕장",
                "- 앞날: 1일차: 감천문화마을, 자갈치시장",
                "10 | 할매국밥 | Restaurant(gukbap) | 1.2 | 60 | 추천",
                "20 | 광안리해수욕장 | Beach | 6.0 | 90 | 저녁에 가는 곳 | 널리 알려짐 | 꼭 넣을 곳");
    }

    @Test
    @DisplayName("고를 후보가 하나도 없는 날은 모델을 부르지 않는다")
    void emptyDayDoesNotCallTheModel() {
        DaySelectionCallGenerator generator = new DaySelectionCallGenerator(null,
                new com.shg.trip.shgtrip.global.config.AnthropicProperties("haiku", "sonnet", 0));
        DaySelector.Option base = option(3, "해운대해수욕장", "Landmarks and Outdoors > Beach", null, 0.0, false, false);
        DaySelector.DayOptions empty = new DaySelector.DayOptions(1, 1, true, base, List.of(base), List.of(),
                List.of(), List.of(), 1, 2, List.of());

        assertThat(generator.selectorFor(TRIP, false).choose(empty)).isEmpty();
    }

    @Test
    @DisplayName("고르지 않은 칸의 \"null\"·빈 문자열은 버리고 숫자로 읽히는 번호만 받는다")
    void readsOnlyNumericPicks() {
        java.util.Map<String, Object> pick = new java.util.HashMap<>();
        pick.put("lunch", "null");
        pick.put("dinner", "12");
        pick.put("sights", List.of(20, "", 21L));

        assertThat(DaySelectionCallGenerator.picked(pick)).containsExactly(12, 20, 21);
    }

    private static DaySelector.Option option(int index, String name, String category, String kind, double km,
                                             boolean recommended, boolean requested) {
        return new DaySelector.Option(index, name, category, kind, km, 60, "DAYTIME", null, recommended, requested);
    }
}
