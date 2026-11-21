package com.shg.trip.shgtrip.domain.tripchat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStateTest {

    private static DraftEvent event(long seq, DraftField field, EventOp op, String value) {
        return new DraftEvent(seq, field.slot(), field, op, value, FieldStatus.KNOWN, FieldSource.USER, value, null, null);
    }

    @Test
    @DisplayName("v1 상태는 마지막 하고 싶은 것만 목록 항목으로 옮겨 이어간다")
    void migratesV1Priorities() {
        ChatState st = new ChatState();
        st.setSchemaVersion(1);
        st.setEvents(List.of(
                event(1, DraftField.DESTINATION, EventOp.SET, "부산"),
                event(2, DraftField.PRIORITIES, EventOp.SET, "야경 보기"),
                event(3, DraftField.THEMES, EventOp.ADD, "nightview"),
                event(4, DraftField.PRIORITIES, EventOp.SET, "바다 보며 쉬기")));

        assertThat(st.migrate()).isTrue();

        assertThat(st.getSchemaVersion()).isEqualTo(ChatState.SCHEMA_VERSION);
        assertThat(st.getEvents()).extracting(DraftEvent::seq).containsExactly(1L, 3L, 4L);
        assertThat(st.getEvents().get(2).op()).isEqualTo(EventOp.ADD);
        assertThat(st.getEvents().get(2).value()).isEqualTo("바다 보며 쉬기");
    }

    @Test
    @DisplayName("v1에서 추론이 여행자 값을 덮지 못했으면 이관 뒤에도 여행자 값이 남는다")
    void migrationKeepsUserValueOverInference() {
        ChatState st = new ChatState();
        st.setSchemaVersion(1);
        st.setEvents(List.of(
                event(1, DraftField.PRIORITIES, EventOp.SET, "바다 보기"),
                new DraftEvent(2, Slot.MOOD, DraftField.PRIORITIES, EventOp.SET, "휴식", FieldStatus.KNOWN,
                        FieldSource.INFERRED, null, null, null)));

        assertThat(st.migrate()).isTrue();

        assertThat(st.getEvents()).singleElement().extracting(DraftEvent::value).isEqualTo("바다 보기");
    }

    @Test
    @DisplayName("옮길 수 없는 버전은 이어가지 않는다")
    void unknownVersionIsNotMigrated() {
        ChatState st = new ChatState();
        st.setSchemaVersion(99);

        assertThat(st.migrate()).isFalse();
    }
}
