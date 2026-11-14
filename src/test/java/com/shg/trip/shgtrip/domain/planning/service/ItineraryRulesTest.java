package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Severity;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryRulesTest {

    private static final String SEAFOOD = "Dining and Drinking > Restaurant > Seafood Restaurant";
    private static final String CHINESE = "Dining and Drinking > Restaurant > Asian Restaurant > Chinese Restaurant";
    private static final String KOREAN = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String DESSERT = "Dining and Drinking > Dessert Shop";
    private static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House";
    private static final String BEACH = "Landmarks and Outdoors > Beach";
    private static final String TEMPLE = "Landmarks and Outdoors > Temple";
    private static final String PENSION = "Travel and Transportation > Lodging > Vacation Rental";
    private static final LocalDate START = LocalDate.of(2026, 11, 20);

    private final ItineraryRules rules = new ItineraryRules();

    @Test
    @DisplayName("카페·디저트 네 곳에 관광이 없는 날(91 3일차)은 카페 상한과 비식사 활동 없음으로 잡힌다")
    void cafeHoppingDayWithoutSights() {
        List<StepData> day = List.of(
                step(1, 1, "08:46", "09:56", "브리앙", DESSERT, 13.8),
                step(2, 1, "10:03", "11:13", "베어케이커리", DESSERT, 5.9),
                step(3, 1, "11:36", "12:46", "자갈치해물짬뽕", CHINESE, 19.6),
                step(4, 1, "13:08", "13:58", "베이커리카페 네모", CAFE, 18.3),
                step(5, 1, "14:24", "15:24", "카페 Ccd. 부산송도점", CAFE, 21.7),
                step(6, 1, "17:30", "18:40", "짬뽕지존", CHINESE, 14.7),
                step(7, 1, "18:52", "19:22", "텐테이블펜션", PENSION, 9.8));

        Verdict verdict = rules.evaluate(day, List.of(), context("relaxed", 1));

        assertThat(rulesOf(verdict)).contains(Rule.CAFE_PER_DAY, Rule.NO_NON_FOOD_ACTIVITY);
        assertThat(verdict.violations()).filteredOn(v -> v.rule() == Rule.CAFE_PER_DAY).singleElement()
                .extracting(ItineraryRules.Violation::evidence).asList()
                .containsExactly("브리앙", "베어케이커리", "베이커리카페 네모", "카페 Ccd. 부산송도점");
        assertThat(verdict.violations()).filteredOn(v -> v.rule() == Rule.SAME_DAY_MEAL_KIND).singleElement()
                .extracting(ItineraryRules.Violation::evidence).asList().containsExactly("자갈치해물짬뽕", "짬뽕지존");
        assertThat(verdict.has(Severity.CORE)).isTrue();
        assertThat(verdict.has(Severity.HARD)).isFalse();
    }

    @Test
    @DisplayName("같은 날 점심·저녁이 같은 종류면 위반, 종류를 모르는 식당끼리는 반복으로 세지 않는다")
    void sameDayMealKind() {
        List<StepData> clams = List.of(
                step(1, 1, "09:00", "10:00", "해운대해수욕장", BEACH, 4.9),
                step(2, 1, "11:30", "12:40", "청해 조개구이 장어구이", SEAFOOD, 3.6),
                step(3, 1, "14:09", "15:39", "해동용궁사", TEMPLE, 8.0),
                step(4, 1, "17:30", "18:40", "황금조개구이", SEAFOOD, 8.3),
                step(5, 1, "18:48", "19:18", "텐테이블펜션", PENSION, 4.8));
        List<StepData> unknownKinds = List.of(
                step(1, 1, "09:00", "10:00", "해운대해수욕장", BEACH, 4.9),
                step(2, 1, "11:30", "12:40", "한식당", KOREAN, 3.6),
                step(3, 1, "14:09", "15:39", "해동용궁사", TEMPLE, 8.0),
                step(4, 1, "17:30", "18:40", "백반집", KOREAN, 8.3),
                step(5, 1, "18:48", "19:18", "텐테이블펜션", PENSION, 4.8));

        Verdict clamDay = rules.evaluate(clams, List.of(), context("relaxed", 1));
        Verdict unknownDay = rules.evaluate(unknownKinds, List.of(), context("relaxed", 1));

        assertThat(rulesOf(clamDay)).contains(Rule.SAME_DAY_MEAL_KIND);
        assertThat(rulesOf(unknownDay)).doesNotContain(Rule.SAME_DAY_MEAL_KIND);
        assertThat(unknownDay.unknownKindMeals()).isEqualTo(2);
    }

    @Test
    @DisplayName("6일 여행에 같은 종류 6끼면 여행 전체 상한(3끼) 초과, 한 요리뿐인 묶음은 묶음 반복으로 따로 세지 않는다")
    void mealKindRepeatedAcrossTrip() {
        List<StepData> steps = new ArrayList<>();
        int order = 1;
        for (int d = 1; d <= 6; d++) {
            steps.add(step(order++, d, "09:00", "10:30", "해변" + d, BEACH, 5.0));
            steps.add(step(order++, d, "11:30", "12:40", "조개구이" + d, SEAFOOD, 5.0));
            steps.add(step(order++, d, "14:00", "15:30", "사찰" + d, TEMPLE, 5.0));
            steps.add(step(order++, d, "17:30", "18:40", "국숫집" + d, "Dining and Drinking > Restaurant > Noodle Restaurant", 5.0));
            if (d < 6) steps.add(step(order++, d, "19:00", "19:30", "펜션", PENSION, 5.0));
        }

        Verdict verdict = rules.evaluate(steps, List.of(), context("relaxed", 6));

        assertThat(verdict.violations())
                .filteredOn(v -> v.rule() == Rule.MEAL_KIND_REPEATED)
                .extracting(ItineraryRules.Violation::message)
                .anyMatch(m -> m.contains("shellfish 6끼"));
        assertThat(rulesOf(verdict)).doesNotContain(Rule.MEAL_GROUP_REPEATED);
    }

    @Test
    @DisplayName("요리가 달라도 같은 묶음이 여행 상한을 넘으면 묶음 반복(SOFT), 같은 날 다른 요리는 같은 묶음(SOFT)으로만 센다")
    void mealGroupRepeatedAcrossTrip() {
        String[][] meals = {{"광안횟집", "청해 조개구이"}, {"해운대 물회", "황금조개구이"}, {"자갈치 곰장어", "할매국밥"}};
        List<StepData> steps = new ArrayList<>();
        int order = 1;
        for (int d = 1; d <= 3; d++) {
            steps.add(step(order++, d, "09:00", "10:30", "해변" + d, BEACH, 5.0));
            steps.add(step(order++, d, "11:30", "12:40", meals[d - 1][0], SEAFOOD, 5.0));
            steps.add(step(order++, d, "14:00", "15:30", "사찰" + d, TEMPLE, 5.0));
            steps.add(step(order++, d, "17:30", "18:40", meals[d - 1][1], SEAFOOD, 5.0));
            if (d < 3) steps.add(step(order++, d, "19:00", "19:30", "펜션", PENSION, 5.0));
        }

        Verdict verdict = rules.evaluate(steps, List.of(), context("normal", 3));

        assertThat(verdict.violations())
                .filteredOn(v -> v.rule() == Rule.MEAL_GROUP_REPEATED)
                .extracting(ItineraryRules.Violation::message)
                .containsExactly("같은 묶음 seafood 5끼(상한 3)");
        assertThat(Rule.MEAL_GROUP_REPEATED.severity).isEqualTo(Severity.SOFT);
        assertThat(rulesOf(verdict)).doesNotContain(Rule.SAME_DAY_MEAL_KIND);
        assertThat(verdict.violations()).filteredOn(v -> v.rule() == Rule.SAME_DAY_MEAL_GROUP)
                .extracting(ItineraryRules.Violation::day).containsExactly(1, 2);
        assertThat(Rule.SAME_DAY_MEAL_GROUP.severity).isEqualTo(Severity.SOFT);
    }

    @Test
    @DisplayName("횟집 점심과 요리를 모르는 해산물집 저녁은 같은 날 같은 종류(CORE)다")
    void unknownDishSeafoodOverlapsRawFishSameDay() {
        List<StepData> day = List.of(
                step(1, 1, "09:00", "10:00", "해운대해수욕장", BEACH, 4.9),
                step(2, 1, "11:30", "12:40", "광안횟집", SEAFOOD, 3.6),
                step(3, 1, "14:09", "15:39", "해동용궁사", TEMPLE, 8.0),
                step(4, 1, "17:30", "18:40", "바다식당", SEAFOOD, 8.3),
                step(5, 1, "18:48", "19:18", "텐테이블펜션", PENSION, 4.8));

        assertThat(rulesOf(rules.evaluate(day, List.of(), context("normal", 1)))).contains(Rule.SAME_DAY_MEAL_KIND);
    }

    @Test
    @DisplayName("자동차 여유 일정의 하루 136km(91 5일차)는 상한 초과, 보통 페이스면 상한 안")
    void dayDistanceDependsOnPace() {
        List<StepData> day = List.of(
                step(1, 1, "09:00", "11:00", "범어사", TEMPLE, 19.6),
                step(2, 1, "11:46", "12:56", "조개야조개야", SEAFOOD, 30.4),
                step(3, 1, "13:45", "14:55", "안영순베이커리카페", "Dining and Drinking > Bakery", 32.0),
                step(4, 1, "15:04", "16:04", "일광해수욕장", BEACH, 5.3),
                step(5, 1, "17:30", "18:40", "욜로조개구이", SEAFOOD, 34.2),
                step(6, 1, "19:02", "19:32", "텐테이블펜션", PENSION, 14.5));

        Verdict relaxed = rules.evaluate(day, List.of(), context("relaxed", 1));
        assertThat(relaxed.violations()).filteredOn(v -> v.rule() == Rule.DAY_DISTANCE_OVER_CAP)
                .singleElement().satisfies(v -> {
                    assertThat(v.severity()).isEqualTo(Severity.SOFT);
                    assertThat(v.evidence()).containsExactly("136");
                });
        assertThat(rulesOf(rules.evaluate(day, List.of(), context("normal", 1))))
                .doesNotContain(Rule.DAY_DISTANCE_OVER_CAP);
    }

    @Test
    @DisplayName("여행 일수보다 날짜가 모자라면 쓸 수 없는 일정(HARD)")
    void missingDayIsHard() {
        List<StepData> steps = List.of(
                step(1, 1, "09:00", "10:00", "해변", BEACH, null),
                step(2, 2, "09:00", "10:00", "사찰", TEMPLE, 5.0));

        Verdict verdict = rules.evaluate(steps, List.of(), context("normal", 3));

        assertThat(rulesOf(verdict)).contains(Rule.DAY_MISSING);
        assertThat(verdict.has(Severity.HARD)).isTrue();
    }

    @Test
    @DisplayName("부산 장소의 주소가 경기 용인이면 주소-지역 불일치, 해외 지역은 판정하지 않는다")
    void addressRegionMismatch() {
        StepData busanWithYonginAddress = new StepData(1, 1, "10:08", "11:18",
                new PlaceData("타타스베이글", "경기 용인시 처인구 백령로20번길 3", "Dining and Drinking > Bagel Shop",
                        "Busan", "KR"), List.of(), null, null, null, null, null, null);
        StepData osaka = new StepData(2, 1, "12:00", "13:00",
                new PlaceData("Dotonbori", "경기 어딘가", BEACH, "Osaka", "JP"),
                List.of(), null, null, null, null, null, null);

        Verdict verdict = rules.evaluate(List.of(busanWithYonginAddress, osaka), List.of(), context("normal", 1));

        assertThat(verdict.violations())
                .filteredOn(v -> v.rule() == Rule.ADDRESS_REGION_MISMATCH)
                .extracting(ItineraryRules.Violation::message)
                .containsExactly("day1 주소와 지역 불일치(타타스베이글)");
    }

    @Test
    @DisplayName("출발 시각을 모르는 마지막 날의 이른 종료는 SOFT라 관문 판정에 영향이 없다")
    void earlyFinishWithoutDepartureIsSoft() {
        List<StepData> lastDay = List.of(
                step(1, 1, "09:00", "10:30", "유엔기념공원", TEMPLE, 5.0),
                step(2, 1, "11:30", "12:40", "조개공장", SEAFOOD, 6.3),
                step(3, 1, "14:38", "16:08", "국립해양박물관", TEMPLE, 5.8));

        Verdict verdict = rules.evaluate(lastDay, List.of(), context("relaxed", 1));

        assertThat(rulesOf(verdict)).contains(Rule.EARLY_FINISH);
        assertThat(verdict.violations()).filteredOn(v -> v.rule() == Rule.EARLY_FINISH)
                .allMatch(v -> v.severity() == Severity.SOFT);
    }

    @Test
    @DisplayName("후보의 보강 사실이 이름 추정보다 앞선다: 총칭 한식당 두 곳도 보강 종류가 같으면 같은 날 반복")
    void candidateFactsTakePrecedence() {
        List<StepData> day = List.of(
                step(1, 1, "09:00", "10:00", "해변", BEACH, 4.0),
                step(2, 1, "11:30", "12:40", "바다식당", KOREAN, 3.0),
                step(3, 1, "14:00", "15:00", "사찰", TEMPLE, 4.0),
                step(4, 1, "17:30", "18:40", "항구식당", KOREAN, 3.0));
        List<PlaceCandidate> candidates = List.of(
                mealCandidate(1, "바다식당", "SEAFOOD"), mealCandidate(2, "항구식당", "SEAFOOD"));

        assertThat(rulesOf(rules.evaluate(day, List.of(), context("relaxed", 1))))
                .doesNotContain(Rule.SAME_DAY_MEAL_KIND);
        assertThat(rulesOf(rules.evaluate(day, candidates, context("relaxed", 1))))
                .contains(Rule.SAME_DAY_MEAL_KIND);
    }

    @Test
    @DisplayName("여러 날 나오는 숙소의 주소 불일치는 한 번만 센다")
    void addressMismatchCountedOncePerPlace() {
        PlaceData pension = new PlaceData("펜션", "경기 용인시 어딘가", PENSION, "Busan", "KR", null, null, 7L, null);
        List<StepData> steps = List.of(
                new StepData(1, 1, "19:00", "19:30", pension, List.of(), null, null, null, null, null, null),
                new StepData(2, 2, "19:00", "19:30", pension, List.of(), null, null, null, null, null, null));

        assertThat(rulesOf(rules.evaluate(steps, List.of(), context("normal", 2))))
                .filteredOn(r -> r == Rule.ADDRESS_REGION_MISMATCH).hasSize(1);
    }

    @Test
    @DisplayName("사용자 출발이 17시 전이면 마지막 날 이른 종료를 면제하고, 21시 출발이면 잡는다")
    void earlyFinishExemptionFollowsDepartureDeadline() {
        List<StepData> lastDay = List.of(
                step(1, 1, "09:00", "10:30", "공원", TEMPLE, 5.0),
                step(2, 1, "11:30", "12:40", "식당", SEAFOOD, 6.3),
                step(3, 1, "14:00", "15:30", "박물관", TEMPLE, 5.8));

        assertThat(rulesOf(rules.evaluate(lastDay, List.of(),
                new ItineraryRules.Context("normal", "car", START, 1, 16 * 60))))
                .doesNotContain(Rule.EARLY_FINISH);
        assertThat(rulesOf(rules.evaluate(lastDay, List.of(),
                new ItineraryRules.Context("normal", "car", START, 1, 21 * 60))))
                .contains(Rule.EARLY_FINISH);
    }

    @Test
    @DisplayName("필수 경험에 맞춘 장소가 일정에 없을 때만 핵심 위반이다")
    void requiredExperienceMissingOnlyWhenMatchedPlaceDropped() {
        StepData beach = new StepData(1, 1, "19:00", "20:00",
                new PlaceData("광안리해수욕장", null, BEACH, "Busan", "KR", null, null, 501L, null),
                List.of(), null, null, null, null, null, null);
        PlaceCandidate tower = new PlaceCandidate(2, 502L, "부산타워", null, TEMPLE, List.of(), "Busan", "KR",
                BigDecimal.valueOf(35.1), BigDecimal.valueOf(129.0), null, null, 0.9);

        assertThat(rulesOf(rules.evaluate(List.of(beach), List.of(tower),
                new ItineraryRules.Context("normal", "car", START, 1, null, Map.of(501L, "광안리해수욕장")))))
                .doesNotContain(Rule.REQUIRED_EXPERIENCE_MISSING);
        Verdict missing = rules.evaluate(List.of(beach), List.of(tower),
                new ItineraryRules.Context("normal", "car", START, 1, null, Map.of(501L, "광안리해수욕장", 502L, "부산타워")));
        assertThat(missing.violations()).filteredOn(v -> v.rule() == Rule.REQUIRED_EXPERIENCE_MISSING)
                .extracting(ItineraryRules.Violation::message).containsExactly("필수 경험 장소 빠짐(부산타워)");
        assertThat(missing.core()).isTrue();
    }

    @Test
    @DisplayName("기록에 남는 판정 JSON에 등급과 HARD·CORE 여부가 들어간다")
    void verdictJsonCarriesSeverity() throws Exception {
        List<StepData> steps = List.of(step(1, 1, "09:00", "10:00", "해변", BEACH, null));
        Verdict verdict = rules.evaluate(steps, List.of(), context("normal", 2));

        String json = com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(verdict);

        assertThat(json).contains("\"rule\":\"DAY_MISSING\"", "\"severity\":\"HARD\"",
                "\"hard\":true", "\"core\":false", "\"version\":" + ItineraryRules.VERSION);
    }

    private static PlaceCandidate mealCandidate(int index, String name, String cuisine) {
        return new PlaceCandidate(index, null, name, null, KOREAN, List.of(), "Busan", "KR",
                BigDecimal.valueOf(35.1), BigDecimal.valueOf(129.0), null, null, 0.9, null, null,
                null, 70, false, null, null, null, null, "MEAL", cuisine, false, null);
    }

    static ItineraryRules.Context context(String pace, int dayCount) {
        return new ItineraryRules.Context(pace, "car", START, dayCount, null);
    }

    private static List<Rule> rulesOf(Verdict verdict) {
        return verdict.violations().stream().map(ItineraryRules.Violation::rule).toList();
    }

    static StepData step(int order, int day, String start, String end, String name, String category,
                                 Double displayKm) {
        return new StepData(order, day, start, end, new PlaceData(name, null, category, "Busan", "KR"),
                List.of(), displayKm == null ? null : "CAR", displayKm == null ? null : 10,
                displayKm == null ? null : BigDecimal.valueOf(displayKm), null, null, null);
    }
}
