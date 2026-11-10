package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.EventOp;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.util.ArrayList;
import java.util.List;

/** 테스트용 이벤트 작성기. */
final class Events {

    private final List<DraftEvent> events = new ArrayList<>();
    private long seq;

    static Events of() {
        return new Events();
    }

    Events user(DraftField field, String value) {
        return add(field, value, FieldSource.USER, value);
    }

    Events user(DraftField field, String value, String raw) {
        return add(field, value, FieldSource.USER, raw);
    }

    Events inferred(DraftField field, String value) {
        return add(field, value, FieldSource.INFERRED, null);
    }

    Events tentative(DraftField field, String value, String raw) {
        return add(field, value, FieldSource.TENTATIVE, raw);
    }

    Events booked(String name, String date, String time) {
        events.add(new DraftEvent(++seq, Slot.ANCHORS, DraftField.BOOKED, EventOp.ADD, name, FieldStatus.KNOWN,
                FieldSource.USER, name, date, time));
        return this;
    }

    Events outcome(Slot slot, FieldStatus status) {
        events.add(DraftEvent.slotOutcome(++seq, slot, status, null));
        return this;
    }

    Events clearSlot(Slot slot) {
        events.add(DraftEvent.clearSlot(++seq, slot));
        for (DraftField f : DraftField.values()) {
            if (f.slot() == slot) events.add(DraftEvent.clearField(++seq, f));
        }
        return this;
    }

    private Events add(DraftField field, String value, FieldSource source, String raw) {
        events.add(new DraftEvent(++seq, field.slot(), field, field.isList() ? EventOp.ADD : EventOp.SET, value,
                FieldStatus.KNOWN, source, raw, null, null));
        return this;
    }

    List<DraftEvent> list() {
        return events;
    }

    Draft draft() {
        return DraftFolder.fold(events);
    }

    /** 부산 11/20~25 커플, 바다 휴식, 자동차. 정해둔 것 없음. */
    static Events busanTrip() {
        return of().user(DraftField.DESTINATION, "부산")
                .user(DraftField.START_DATE, "2026-11-20", "11월 20일부터 25일까지")
                .user(DraftField.END_DATE, "2026-11-25", "11월 20일부터 25일까지")
                .user(DraftField.COMPANION, "couple", "남자친구랑 둘이")
                .user(DraftField.PRIORITIES, "바다 보면서 느긋하게 쉬기")
                .inferred(DraftField.THEMES, "ocean").inferred(DraftField.THEMES, "healing")
                .inferred(DraftField.PACE, "relaxed")
                .user(DraftField.TRANSPORT, "car", "자동차 이용할 거임")
                .outcome(Slot.ANCHORS, FieldStatus.NONE);
    }
}
