package com.shg.trip.shgtrip.domain.tripchat.model;

/**
 * field가 null이면 항목 자체의 상태(미정·없음 등) 이벤트다.
 * date·time은 BOOKED 항목에만 쓴다.
 */
public record DraftEvent(
        long seq,
        Slot slot,
        DraftField field,
        EventOp op,
        String value,
        FieldStatus status,
        FieldSource source,
        String raw,
        String date,
        String time
) {
    public static DraftEvent slotOutcome(long seq, Slot slot, FieldStatus status, String raw) {
        return new DraftEvent(seq, slot, null, EventOp.SET, null, status, FieldSource.USER, raw, null, null);
    }

    public static DraftEvent clearSlot(long seq, Slot slot) {
        return new DraftEvent(seq, slot, null, EventOp.CLEAR, null, null, FieldSource.USER, null, null, null);
    }

    public static DraftEvent clearField(long seq, DraftField field) {
        return new DraftEvent(seq, field.slot(), field, EventOp.CLEAR, null, null, FieldSource.USER, null, null, null);
    }
}
