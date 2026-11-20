package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.ChatState;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.EventOp;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.QuestionKind;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import com.shg.trip.shgtrip.domain.tripchat.model.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripChatContextTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static OpenQuestion base() {
        return QuestionBank.base(Slot.TRANSPORT, Events.of().draft(), TODAY, null);
    }

    private static Turn turn(Slot slot, String q, String a) {
        return new Turn("t", "q", slot, QuestionKind.BASE, q, a, "text", null);
    }

    @Test
    @DisplayName("조건마다 근거가 된 답 번호와 원문, 대화 전체, 경고 횟수를 담는다")
    void carriesProvenanceConversationAndModeration() {
        ChatState st = new ChatState();
        st.getTurns().add(turn(Slot.CITY, "어디로 떠나볼까요?", "춘천 가요"));
        st.getTurns().add(turn(Slot.MOOD, "뭘 하고 싶어요?", "호수 보면서 산책하고 싶어요"));
        var draft = Events.of().user(DraftField.DESTINATION, "춘천", "춘천")
                .user(DraftField.PRIORITIES, "호수 보며 산책하기", "호수 보면서 산책하고 싶어요").draft();
        OpenQuestion q = QuestionBank.base(Slot.TRANSPORT, draft, TODAY, null);

        String context = TripChatContext.build(draft, st, q, TODAY, 1, 3);

        assertThat(context).contains("<today>2026-11-10 (화)</today>")
                .contains("- DESTINATION: 춘천 (user, 1번 답 \"춘천\")")
                .contains("- PRIORITIES: 호수 보며 산책하기 (user, 2번 답 \"호수 보면서 산책하고 싶어요\")")
                .contains("2. 질문(MOOD): 뭘 하고 싶어요? / 답: 호수 보면서 산책하고 싶어요")
                .contains("<removed>\n없음\n</removed>")
                .contains("<moderation>앞선 경고 1회. 이번 답이 부적절하면 2번째이고, 3번째에 대화 입력이 제한된다</moderation>")
                .doesNotContain("<answer>");
    }

    @Test
    @DisplayName("같은 원문이 여러 답에 있으면 가장 최근 답 번호를 쓴다")
    void latestAnswerWins() {
        List<Turn> turns = List.of(turn(Slot.CITY, "q", "바다 보고 싶어"), turn(Slot.MOOD, "q", "역시 바다 보고 싶어"));

        assertThat(TripChatContext.answerNumber("바다 보고 싶어", turns)).isEqualTo(2);
        assertThat(TripChatContext.answerNumber("산", turns)).isZero();
        assertThat(TripChatContext.answerNumber(null, turns)).isZero();
    }

    @Test
    @DisplayName("지운 조건은 넣은 답과 지운 답 번호와 함께 남고, 다시 넣으면 빠진다")
    void removedConditionsCarryWhenAndUntilReAdded() {
        List<Turn> turns = List.of(turn(Slot.MOOD, "q", "해산물 먹고 바다 보고 싶어요"),
                turn(Slot.EXTRAS, "q", "알레르기가 있어서 해산물은 빼주세요"));
        Events ev = Events.of().user(DraftField.FOOD_LIKES, "해산물", "해산물 먹고 바다 보고 싶어요")
                .user(DraftField.PRIORITIES, "바다 보기", "해산물 먹고 바다 보고 싶어요");
        List<DraftEvent> events = new ArrayList<>(ev.list());
        events.add(new DraftEvent(10, Slot.EXTRAS, DraftField.FOOD_LIKES, EventOp.REMOVE, "해산물", FieldStatus.KNOWN,
                FieldSource.USER, "해산물은 빼주세요", null, null));
        events.add(new DraftEvent(11, Slot.MOOD, DraftField.PRIORITIES, EventOp.REMOVE, "없던 바람", FieldStatus.KNOWN,
                FieldSource.USER, "해산물은 빼주세요", null, null));

        assertThat(TripChatContext.removedLines(events, turns)).containsExactly(
                "- FOOD_LIKES: 해산물 (1번 답 \"해산물 먹고 바다 보고 싶어요\"에서 넣음, 2번 답에서 지움)");

        events.add(new DraftEvent(12, Slot.EXTRAS, DraftField.FOOD_LIKES, EventOp.ADD, "해산물", FieldStatus.KNOWN,
                FieldSource.USER, "해산물", null, null));
        assertThat(TripChatContext.removedLines(events, turns)).isEmpty();
    }

    @Test
    @DisplayName("항목을 통째로 고쳐 말하면 그 전에 지운 것은 되살릴 대상에서 빠진다")
    void clearForgetsEarlierRemovals() {
        List<Turn> turns = List.of(turn(Slot.MOOD, "q", "해산물 먹고 싶어요"), turn(Slot.EXTRAS, "q", "해산물은 빼주세요"));
        List<DraftEvent> events = new ArrayList<>(Events.of()
                .user(DraftField.FOOD_LIKES, "해산물", "해산물 먹고 싶어요").list());
        events.add(new DraftEvent(10, Slot.EXTRAS, DraftField.FOOD_LIKES, EventOp.REMOVE, "해산물", FieldStatus.KNOWN,
                FieldSource.USER, "해산물은 빼주세요", null, null));
        events.add(DraftEvent.clearField(11, DraftField.FOOD_LIKES));

        assertThat(TripChatContext.removedLines(events, turns)).isEmpty();
    }

    @Test
    @DisplayName("예약 날짜·시각은 값 밖 괄호에 둬 지울 때 값을 그대로 옮겨 쓸 수 있다")
    void bookingDateStaysOutsideValue() {
        ChatState st = new ChatState();
        st.getTurns().add(turn(Slot.ANCHORS, "q", "12월 4일 저녁 7시 뮤지컬 예약했어요"));
        var draft = Events.of().booked("뮤지컬", "2026-12-04", "19:00").draft();

        String context = TripChatContext.build(draft, st, base(), TODAY, 0, 3);

        assertThat(context).contains("- BOOKED: 뮤지컬 (2026-12-04 19:00 예약, user");
    }
}
