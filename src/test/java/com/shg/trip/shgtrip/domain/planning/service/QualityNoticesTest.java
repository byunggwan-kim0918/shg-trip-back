package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Verdict;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityNoticesTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 20);

    @Test
    @DisplayName("HARD·CORE만 일차를 앞에 둔 안내가 되고, 여행 전체 위반은 날짜별 안내 뒤에 온다")
    void coreAndHardBecomeDayNotices() {
        Verdict verdict = verdict(
                new Violation(Rule.MEAL_KIND_REPEATED, 0, "같은 종류 shellfish 4끼(상한 3)",
                        List.of("청해 조개구이", "황금조개구이", "조개마을", "청사포 조개")),
                new Violation(Rule.SAME_DAY_MEAL_KIND, 3, "day3 점심·저녁 같은 종류(chinese_noodle)",
                        List.of("자갈치해물짬뽕", "짬뽕지존")),
                new Violation(Rule.LUNCH_MISSING, 2, "day2 점심 없음"),
                new Violation(Rule.LEG_OVER_CAP, 2, "day2 구간 상한 초과(기장)", List.of("기장")),
                new Violation(Rule.SAME_DAY_MEAL_GROUP, 4, "day4 점심·저녁 같은 묶음(seafood)", List.of("a", "b")));

        assertThat(QualityNotices.of(verdict, context(5, null))).containsExactly(
                "2일차에 점심 식사가 없어요.",
                "3일차 점심과 저녁이 같은 종류예요(자갈치해물짬뽕·짬뽕지존). 한 곳을 대안으로 바꿔 보세요.",
                "같은 종류 식당이 4번 나와요('청해 조개구이' 등). 대안으로 바꿔 보세요.");
    }

    @Test
    @DisplayName("이른 종료는 출발 시각을 모르는 마지막 날에만 출발 시간을 알려 달라는 제안이 된다")
    void earlyFinishSuggestsDepartureOnlyOnLastDay() {
        Verdict verdict = verdict(
                new Violation(Rule.EARLY_FINISH, 3, "day3 일정이 15:12에 끝남", List.of("15:12")),
                new Violation(Rule.EARLY_FINISH, 2, "day2 일정이 16:00에 끝남", List.of("16:00")));

        assertThat(QualityNotices.of(verdict, context(3, null))).containsExactly(
                "3일차 일정이 15:12에 끝나요. 다음에 만들 때 출발 시간을 알려 주면 마지막 날을 더 채울 수 있어요.");
        assertThat(QualityNotices.of(verdict, context(3, 15 * 60))).isEmpty();
    }

    @Test
    @DisplayName("장소 이름이 든 안내는 근거의 이름을 따옴표로 넣는다")
    void placeNoticesQuoteEvidence() {
        Verdict verdict = verdict(
                new Violation(Rule.ADDRESS_REGION_MISMATCH, 4, "day4 주소와 지역 불일치(타타스베이글)", List.of("타타스베이글")),
                new Violation(Rule.REQUIRED_EXPERIENCE_MISSING, 0, "필수 경험 장소 빠짐(광안리해수욕장)",
                        List.of("광안리해수욕장")),
                new Violation(Rule.CLOSED_DAY, 1, "day1 휴무일 방문(국립부산과학관)", List.of("국립부산과학관")));

        assertThat(QualityNotices.of(verdict, context(5, null))).containsExactly(
                "1일차 '국립부산과학관'은(는) 휴무일이에요. 다른 곳으로 바꿔 주세요.",
                "4일차 '타타스베이글'의 주소가 여행지와 달라요. 대안으로 바꾸거나 빼 주세요.",
                "꼭 가고 싶다고 한 '광안리해수욕장'을(를) 일정에 넣지 못했어요.");
    }

    @Test
    @DisplayName("거부 사유는 다시 만들어도 막히는 출발 시각을 먼저 말하고, HARD가 없으면 없다")
    void rejectionPrefersInputCause() {
        Verdict verdict = verdict(
                new Violation(Rule.DAY_MISSING, 2, "day2 일정 없음"),
                new Violation(Rule.DEPARTURE_MISSED, 3, "day3 사용자 출발 시각 초과"),
                new Violation(Rule.LUNCH_MISSING, 1, "day1 점심 없음"));

        assertThat(QualityNotices.rejection(verdict, null)).isEqualTo(new QualityNotices.Rejection(
                "마지막 날 출발 시각까지 공항·역에 닿는 일정을 만들 수 없어 저장하지 않았어요.", "출발 시간을 확인한 뒤 다시 만들어 주세요."));
        assertThat(QualityNotices.rejection(verdict(new Violation(Rule.LUNCH_MISSING, 1, "day1 점심 없음")), null)).isNull();
    }

    @Test
    @DisplayName("출발 시각 외 거부 사유는 이른 일차부터 고르고, 휴무일은 장소 이름을 넣는다")
    void rejectionOtherwiseEarliestDay() {
        Verdict verdict = verdict(
                new Violation(Rule.DAY_MISSING, 3, "day3 일정 없음"),
                new Violation(Rule.CLOSED_DAY, 2, "day2 휴무일 방문(국립부산과학관)", List.of("국립부산과학관")));

        assertThat(QualityNotices.rejection(verdict, null)).isEqualTo(new QualityNotices.Rejection(
                "2일차에 휴무일인 '국립부산과학관'이(가) 들어가 저장하지 않았어요.", "다시 만들어 주세요."));
    }

    @Test
    @DisplayName("출발 시각을 준 여행의 마지막 날이 비면 출발 시각을 확인하라고 하고, 출발 시각이 없으면 다시 만들라고 한다")
    void emptyLastDayBlamesDepartureOnlyWhenGiven() {
        Verdict verdict = verdict(new Violation(Rule.DAY_MISSING, 3, "day3 일정 없음"));

        assertThat(QualityNotices.rejection(verdict, context(3, 0))).isEqualTo(new QualityNotices.Rejection(
                "마지막 날 출발 시각이 일러 그날 일정을 넣지 못해 저장하지 않았어요.", "출발 시간을 확인한 뒤 다시 만들어 주세요."));
        assertThat(QualityNotices.rejection(verdict, context(3, null))).isEqualTo(new QualityNotices.Rejection(
                "3일차 일정을 채우지 못해 저장하지 않았어요.", "다시 만들어 주세요."));
    }

    @Test
    @DisplayName("숙소·출발 허브에 묶인 위반은 편집으로 할 수 없는 일을 시키지 않는다")
    void anchorNoticesAskNothingImpossible() {
        assertThat(QualityNotices.of(verdict(
                new Violation(Rule.NOT_ENDING_AT_LODGING, 2, "day2 숙소로 끝나지 않음"),
                new Violation(Rule.DEPARTURE_LATE, 3, "day3 출발 마감 초과")), context(3, null)))
                .containsExactly("2일차 일정이 숙소에서 끝나지 않아요.", "3일차 일정이 늦게 끝나 돌아가는 길이 빠듯해요.");
    }

    @Test
    @DisplayName("HARD 규칙은 모두 거부 사유 문구가 있다")
    void everyHardRuleHasRejectionText() {
        for (Rule rule : Rule.values()) {
            if (rule.severity != ItineraryRules.Severity.HARD) continue;
            assertThat(QualityNotices.rejection(verdict(new Violation(rule, 1, rule.name(), List.of("장소"))), null))
                    .as(rule.name()).isNotNull();
        }
    }

    private static Verdict verdict(Violation... violations) {
        return new Verdict(ItineraryRules.VERSION, List.of(violations), 0, 0);
    }

    private static ItineraryRules.Context context(int days, Integer hubDeadline) {
        return new ItineraryRules.Context("normal", "car", START, days, hubDeadline);
    }
}
