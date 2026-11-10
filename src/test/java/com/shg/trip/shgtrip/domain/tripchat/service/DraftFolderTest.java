package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DraftFolderTest {

    @Test
    @DisplayName("모델 추론은 사용자가 말한 값을 덮지 못한다")
    void inferredNeverOverridesUser() {
        Draft d = Events.of().user(DraftField.PACE, "relaxed", "느긋하게").inferred(DraftField.PACE, "tight").draft();

        assertThat(d.value(DraftField.PACE)).isEqualTo("relaxed");
        assertThat(d.fact(DraftField.PACE).orElseThrow().source()).isEqualTo(FieldSource.USER);
    }

    @Test
    @DisplayName("사용자가 나중에 바꿔 말한 값이 이긴다")
    void latestUserValueWins() {
        Draft d = Events.of().user(DraftField.DESTINATION, "부산").user(DraftField.DESTINATION, "제주").draft();

        assertThat(d.value(DraftField.DESTINATION)).isEqualTo("제주");
    }

    @Test
    @DisplayName("항목을 고쳐 답하면 그 항목만 비워지고 다른 항목 답은 남는다")
    void editClearsOnlyThatSlot() {
        Draft d = Events.busanTrip().clearSlot(Slot.MOOD).user(DraftField.PRIORITIES, "맛집 탐방").draft();

        assertThat(d.value(DraftField.PRIORITIES)).isEqualTo("맛집 탐방");
        assertThat(d.items(DraftField.THEMES)).isEmpty();
        assertThat(d.value(DraftField.PACE)).isNull();
        assertThat(d.value(DraftField.DESTINATION)).isEqualTo("부산");
        assertThat(d.value(DraftField.TRANSPORT)).isEqualTo("car");
    }

    @Test
    @DisplayName("목록은 띄어쓰기가 달라도 같은 항목으로 합치고 예약 시각을 보탠다")
    void listItemsMergeByNormalizedText() {
        Draft d = Events.of().booked("블루라인파크", null, null).booked("블루라인 파크", "2026-11-21", "14:00").draft();

        assertThat(d.items(DraftField.BOOKED)).hasSize(1);
        assertThat(d.items(DraftField.BOOKED).get(0).time()).isEqualTo("14:00");
    }

    @Test
    @DisplayName("미정으로 답한 항목도 답한 것으로 보고, 뒤에 값을 말하면 KNOWN이 된다")
    void slotStatus() {
        Events e = Events.of().outcome(Slot.DATES, FieldStatus.UNDECIDED);
        assertThat(e.draft().slotStatus(Slot.DATES)).isEqualTo(FieldStatus.UNDECIDED);
        assertThat(e.draft().resolved(Slot.COMPANY)).isFalse();

        e.user(DraftField.DATE_APPROX, "11|mid", "11월 중순쯤");
        assertThat(e.draft().slotStatus(Slot.DATES)).isEqualTo(FieldStatus.KNOWN);
    }

    @Test
    @DisplayName("추론 값만 있는 항목은 답하지 않은 것으로 본다")
    void inferredOnlySlotIsUnresolved() {
        Draft d = Events.of().inferred(DraftField.THEMES, "food").draft();

        assertThat(d.resolved(Slot.MOOD)).isFalse();
    }
}
