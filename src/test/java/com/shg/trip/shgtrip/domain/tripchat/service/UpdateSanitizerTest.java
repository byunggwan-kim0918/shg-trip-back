package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateSanitizerTest {

    private static Interpretation.Update u(String field, String op, String value, String source, String raw) {
        return new Interpretation.Update(field, op, value, "known", source, raw, "", "");
    }

    private static List<DraftEvent> run(String answer, String outcome, Interpretation.Update... updates) {
        Interpretation it = new Interpretation(List.of(updates), outcome, null, false);
        AtomicLong seq = new AtomicLong();
        return UpdateSanitizer.toEvents(it, Slot.CITY, answer, seq::incrementAndGet);
    }

    @Test
    @DisplayName("원문 조각이 답에 없으면 사용자 발언이 아니라 추론으로 낮춘다")
    void downgradesUnsupportedUserSource() {
        List<DraftEvent> events = run("부산 가고 싶어",
                "answered",
                u("DESTINATION", "set", "부산", "user", "부산"),
                u("PACE", "set", "relaxed", "user", "느긋하게"));

        assertThat(events.get(0).source()).isEqualTo(FieldSource.USER);
        assertThat(events.get(1).source()).isEqualTo(FieldSource.INFERRED);
        assertThat(events.get(1).raw()).isNull();
    }

    @Test
    @DisplayName("원문 비교는 띄어쓰기·문장부호를 무시한다")
    void evidenceIgnoresSpacing() {
        List<DraftEvent> events = run("블루라인 파크 가고 싶어!", "answered",
                u("MUST_VISIT", "add", "블루라인파크", "user", "블루라인파크 가고 싶어"));

        assertThat(events.get(0).source()).isEqualTo(FieldSource.USER);
    }

    @Test
    @DisplayName("테마·카테고리는 분류어라 늘 추론이다")
    void themesAlwaysInferred() {
        List<DraftEvent> events = run("바다 보고 싶어", "answered", u("THEMES", "add", "ocean", "user", "바다"));

        assertThat(events.get(0).source()).isEqualTo(FieldSource.INFERRED);
    }

    @Test
    @DisplayName("허용되지 않은 값·형식이 틀린 값은 버린다")
    void dropsInvalidValues() {
        List<DraftEvent> events = run("x", "answered",
                u("THEMES", "add", "spa_meditation", "inferred", ""),
                u("START_DATE", "set", "11월 20일", "user", "x"),
                u("UNKNOWN_FIELD", "set", "a", "user", "x"),
                u("DESTINATION", "add", "부산", "user", "x"),
                u("PARTY_SIZE", "set", "40", "user", "x"));

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("시각·예산 형식을 맞춘다")
    void normalizesTimeAndBudget() {
        List<DraftEvent> events = run("9:30 도착, 50만원", "answered",
                u("ARRIVAL_TIME", "set", "9:30", "user", "9:30 도착"),
                u("BUDGET", "set", "500,000", "user", "50만원"));

        assertThat(events).extracting(DraftEvent::value).containsExactly("09:30", "500000");
    }

    @Test
    @DisplayName("미정·맡김 답은 항목 상태 이벤트가 된다")
    void outcomeBecomesSlotEvent() {
        List<DraftEvent> events = run("아직 몰라", "undecided");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.field()).isNull();
            assertThat(e.slot()).isEqualTo(Slot.CITY);
            assertThat(e.status()).isEqualTo(FieldStatus.UNDECIDED);
        });
    }

    @Test
    @DisplayName("작년·한 달 살기처럼 범위를 벗어난 표현이면 모델 날짜를 버린다")
    void dropsDatesForOutOfRangeExpressions() {
        List<DraftEvent> events = run("작년 1월처럼 가고 싶어", "answered",
                u("START_DATE", "set", "2027-01-10", "user", "작년 1월"),
                u("DESTINATION", "set", "부산", "inferred", ""));

        assertThat(events).extracting(DraftEvent::field).containsExactly(DraftField.DESTINATION);
    }

    @Test
    @DisplayName("모델 추론으로는 값을 지우지 못한다")
    void inferredClearIsDropped() {
        List<DraftEvent> events = run("부산 말고", "answered",
                u("LODGING", "clear", "", "inferred", ""),
                u("MUST_VISIT", "remove", "블루라인파크", "user", ""),
                u("DESTINATION", "clear", "", "user", "부산 말고"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo(DraftField.DESTINATION);
            assertThat(e.op()).isEqualTo(com.shg.trip.shgtrip.domain.tripchat.model.EventOp.CLEAR);
        });
    }

    @Test
    @DisplayName("범위를 벗어난 표현과 무관한 날짜는 남긴다")
    void outOfRangeKeepsUnrelatedDates() {
        List<DraftEvent> events = run("작년엔 1월에 갔고 이번엔 11월 20일부터", "answered",
                u("START_DATE", "set", "2026-11-20", "user", "11월 20일부터"),
                u("END_DATE", "set", "2027-01-05", "user", "작년엔 1월"));

        assertThat(events).extracting(DraftEvent::field).containsExactly(DraftField.START_DATE);
    }
}
