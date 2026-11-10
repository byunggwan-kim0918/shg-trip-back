package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.QuestionKind;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NextStepPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static OpenQuestion base(Slot slot) {
        return QuestionBank.base(slot, Events.of().draft(), TODAY, null);
    }

    private static Interpretation answered() {
        return new Interpretation(List.of(), "answered", null, false);
    }

    private static Interpretation withFollowUp(String outcome, String reason) {
        return new Interpretation(List.of(), outcome, new Interpretation.FollowUp(true, reason,
                "걷는 쪽으로 생각 중이군요.", "먼 곳은 택시나 버스도 괜찮아요?", "가까운 곳만 걸어서 둘러봐도 좋아요.",
                List.of("가까운 곳만 걸어 다닐래", "멀면 다른 교통편도 괜찮아"), "예: 멀면 택시도 괜찮아"), false);
    }

    private static NextStepPolicy.Decision decide(Draft draft, Set<String> asked, OpenQuestion q, Interpretation it,
                                                  boolean datesTouched) {
        return NextStepPolicy.decide(draft, asked, q, it, "text", datesTouched, TODAY);
    }

    @Test
    @DisplayName("첫 답에 여행지·날짜·동행이 다 있으면 취향 질문으로 바로 간다")
    void skipsAnsweredSlots() {
        Draft d = Events.of().user(DraftField.DESTINATION, "부산")
                .user(DraftField.START_DATE, "2026-11-20").user(DraftField.END_DATE, "2026-11-25")
                .user(DraftField.COMPANION, "couple", "남자친구랑 둘이").draft();

        NextStepPolicy.Decision decision = decide(d, new HashSet<>(Set.of("BASE:CITY")), base(Slot.CITY), answered(), true);

        assertThat(decision.question().slot()).isEqualTo(Slot.MOOD);
        assertThat(decision.question().kind()).isEqualTo(QuestionKind.BASE);
        assertThat(decision.question().lead()).isEqualTo("부산 여행을 생각하고 있군요.");
        assertThat(decision.question().examplesOpen()).isFalse();
    }

    @Test
    @DisplayName("사실이 충분한 짧은 답은 되묻지 않고 다음 항목으로 간다")
    void noFollowUpForSufficientAnswer() {
        Draft d = Events.busanTrip().clearSlot(Slot.ANCHORS).draft();

        NextStepPolicy.Decision decision = decide(d, new HashSet<>(), base(Slot.TRANSPORT), answered(), false);

        assertThat(decision.question().slot()).isEqualTo(Slot.ANCHORS);
        assertThat(decision.question().lead()).isEqualTo("차를 이용하는 여행으로 기억해둘게요.");
        assertThat(decision.question().tool()).isEqualTo(OpenQuestion.TOOL_NONE);
    }

    @Test
    @DisplayName("망설이는 답은 모델 문구로 한 번 되묻고, 같은 항목은 다시 되묻지 않는다")
    void tentativeFollowUpOnce() {
        Draft d = Events.of().tentative(DraftField.TRANSPORT, "walk", "흠 걸어서?").draft();
        Set<String> asked = new HashSet<>();

        OpenQuestion followUp = decide(d, asked, base(Slot.TRANSPORT), withFollowUp("tentative", "tentative"), false).question();
        assertThat(followUp.kind()).isEqualTo(QuestionKind.FOLLOW_UP);
        assertThat(followUp.title()).isEqualTo("먼 곳은 택시나 버스도 괜찮아요?");
        assertThat(followUp.lead()).isEqualTo("걷는 쪽으로 생각 중이군요.");
        assertThat(followUp.examples()).hasSize(2);
        assertThat(followUp.examplesOpen()).isTrue();
        assertThat(followUp.tool()).isEqualTo(OpenQuestion.TOOL_UNKNOWN);

        asked.add(followUp.askKey());
        NextStepPolicy.Decision again = decide(d, asked, followUp, withFollowUp("tentative", "tentative"), false);
        assertThat(again.question().kind()).isEqualTo(QuestionKind.BASE);
    }

    @Test
    @DisplayName("미정으로 답했으면 모델이 원해도 되묻지 않는다")
    void noFollowUpWhenUndecided() {
        Draft d = Events.of().outcome(Slot.MOOD, FieldStatus.UNDECIDED).draft();

        NextStepPolicy.Decision decision = decide(d, new HashSet<>(), base(Slot.MOOD), withFollowUp("undecided", "vague"), false);

        assertThat(decision.question().kind()).isEqualTo(QuestionKind.BASE);
    }

    @Test
    @DisplayName("미정·맡김으로 답하면 그에 맞는 반응으로 다음 질문을 연다")
    void undecidedAck() {
        Draft undecided = Events.busanTrip().clearSlot(Slot.MOOD).outcome(Slot.MOOD, FieldStatus.UNDECIDED)
                .clearSlot(Slot.TRANSPORT).draft();
        assertThat(decide(undecided, new HashSet<>(), base(Slot.MOOD), answered(), false).question().lead())
                .isEqualTo("취향은 미정으로 남겨둘게요.");

        Draft delegated = Events.busanTrip().clearSlot(Slot.MOOD).outcome(Slot.MOOD, FieldStatus.DELEGATED)
                .clearSlot(Slot.TRANSPORT).draft();
        assertThat(decide(delegated, new HashSet<>(), base(Slot.MOOD), answered(), false).question().lead())
                .isEqualTo("취향은 맡겨준 걸로 기억해둘게요.");
    }

    @Test
    @DisplayName("대략 날짜는 코드가 계산한 날짜를 예시로 한 번 되묻는다")
    void approxDateFollowUp() {
        Draft d = Events.of().user(DraftField.DESTINATION, "부산")
                .user(DraftField.DATE_APPROX, "11|mid", "11월 중순쯤").draft();
        Set<String> asked = new HashSet<>();

        OpenQuestion q = decide(d, asked, base(Slot.DATES), answered(), true).question();
        assertThat(q.kind()).isEqualTo(QuestionKind.FOLLOW_UP);
        assertThat(q.lead()).isEqualTo("11월 중순쯤으로 생각하고 있군요.");
        assertThat(q.examples()).first().isEqualTo("11월 13일(금)부터 15일(일)까지");

        asked.add(q.askKey());
        assertThat(decide(d, asked, q, answered(), true).question().slot()).isEqualTo(Slot.COMPANY);
    }

    @Test
    @DisplayName("당일치기는 생성할 수 없어 이유를 말하고 한 번 묻는다")
    void dayTripCritical() {
        Draft d = Events.of().user(DraftField.START_DATE, "2026-11-20").user(DraftField.END_DATE, "2026-11-20").draft();
        Set<String> asked = new HashSet<>();

        OpenQuestion q = decide(d, asked, base(Slot.DATES), answered(), true).question();
        assertThat(q.askKey()).isEqualTo("CRITICAL:dates_day_trip");
        assertThat(q.description()).contains("1박 이상");

        asked.add(q.askKey());
        assertThat(decide(d, asked, q, answered(), true).question().kind()).isEqualTo(QuestionKind.BASE);
    }

    @Test
    @DisplayName("예약 시간 누락은 되묻기 답에서도 한 번 묻는다")
    void bookingTimeMissingIsCritical() {
        Draft d = Events.busanTrip().booked("블루라인파크", null, null).draft();
        OpenQuestion followUp = QuestionBank.followUp(Slot.ANCHORS, "FOLLOW_UP:ANCHORS", "l", "t", "d", "p", List.of());

        OpenQuestion q = decide(d, new HashSet<>(Set.of("FOLLOW_UP:ANCHORS")), followUp,
                withFollowUp("answered", "booking_time_missing"), false).question();

        assertThat(q).isNotNull();
        assertThat(q.askKey()).isEqualTo("CRITICAL:booking_time_missing:ANCHORS");
    }

    @Test
    @DisplayName("여행 날짜를 바꿔 예약 날짜가 기간 밖이면 덮어쓰지 않고 묻는다")
    void bookingDateConflict() {
        Draft d = Events.busanTrip().booked("블루라인파크", "2026-11-21", "14:00")
                .user(DraftField.START_DATE, "2026-12-01").user(DraftField.END_DATE, "2026-12-03").draft();
        OpenQuestion edit = QuestionBank.edit(Slot.DATES, d, TODAY, null, true);

        OpenQuestion q = decide(d, new HashSet<>(), edit, answered(), true).question();

        assertThat(q.title()).isEqualTo("블루라인파크 예약은 어떻게 할까요?");
    }

    @Test
    @DisplayName("확인 화면에서 고친 항목은 다시 확인 화면으로 돌아간다")
    void editFromReviewReturns() {
        Draft d = Events.busanTrip().draft();
        OpenQuestion edit = QuestionBank.edit(Slot.COMPANY, d, TODAY, "남자친구랑 둘이", true);

        assertThat(decide(d, new HashSet<>(), edit, answered(), false).review()).isTrue();
    }

    @Test
    @DisplayName("모든 항목을 답하면 확인 화면으로 간다")
    void reviewWhenAllResolved() {
        assertThat(decide(Events.busanTrip().draft(), new HashSet<>(), base(Slot.ANCHORS), answered(), false).review())
                .isTrue();
    }

    @Test
    @DisplayName("질문 상한에 닿으면 남은 항목이 있어도 확인 화면으로 간다")
    void questionCap() {
        Set<String> asked = new HashSet<>();
        for (int i = 0; i < NextStepPolicy.MAX_QUESTIONS; i++) asked.add("K" + i);

        assertThat(decide(Events.of().draft(), asked, base(Slot.CITY), answered(), false).review()).isTrue();
    }

    @Test
    @DisplayName("여행과 무관한 답이면 같은 질문을 짧은 안내와 함께 다시 보인다")
    void offTopic() {
        OpenQuestion q = base(Slot.COMPANY);
        Interpretation it = new Interpretation(List.of(), "unclear", null, true);

        OpenQuestion again = decide(Events.of().draft(), new HashSet<>(), q, it, false).question();

        assertThat(again.id()).isEqualTo(q.id());
        assertThat(again.lead()).isEqualTo("여행 이야기로 돌아가 볼게요.");
    }

    @Test
    @DisplayName("길이를 넘는 모델 문구는 항목 템플릿으로 바꾼다")
    void longModelCopyFallsBackToTemplate() {
        Interpretation it = new Interpretation(List.of(), "unclear", new Interpretation.FollowUp(true, "vague", "",
                "가".repeat(80), "", List.of(), ""), false);

        OpenQuestion q = decide(Events.of().draft(), new HashSet<>(), base(Slot.MOOD), it, false).question();

        assertThat(q.title()).isEqualTo("어떤 순간이 더 끌리나요?");
    }
}
