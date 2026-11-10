package com.shg.trip.shgtrip.domain.tripchat.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 이벤트를 접은 현재 여행 조건. DraftFolder만 만든다. */
public record Draft(
        Map<DraftField, Fact> values,
        Map<DraftField, List<Item>> lists,
        Map<Slot, FieldStatus> outcomes
) {
    public record Fact(String value, FieldSource source, String raw, long seq) {}

    public record Item(String text, FieldSource source, String raw, String date, String time, long seq) {}

    public Optional<Fact> fact(DraftField field) {
        return Optional.ofNullable(values.get(field));
    }

    public String value(DraftField field) {
        Fact f = values.get(field);
        return f == null ? null : f.value();
    }

    public List<Item> items(DraftField field) {
        return lists.getOrDefault(field, List.of());
    }

    /** 사용자가 값을 말했으면 KNOWN, 아니면 미정·없음 같은 항목 상태, 둘 다 없으면 null. */
    public FieldStatus slotStatus(Slot slot) {
        boolean userValue = values.entrySet().stream()
                .anyMatch(e -> e.getKey().slot() == slot && e.getValue().source().fromUser())
                || lists.entrySet().stream()
                .anyMatch(e -> e.getKey().slot() == slot
                        && e.getValue().stream().anyMatch(i -> i.source().fromUser()));
        if (userValue) return FieldStatus.KNOWN;
        return outcomes.get(slot);
    }

    public boolean resolved(Slot slot) {
        return slotStatus(slot) != null;
    }

    /** 항목을 채운 사용자 원문 조각 중 가장 최근 것. 수정 화면의 입력창을 채운다. */
    public Optional<String> latestRaw(Slot slot) {
        long best = -1;
        String raw = null;
        for (Map.Entry<DraftField, Fact> e : values.entrySet()) {
            Fact f = e.getValue();
            if (e.getKey().slot() == slot && f.source().fromUser() && f.raw() != null && f.seq() > best) {
                best = f.seq();
                raw = f.raw();
            }
        }
        for (Map.Entry<DraftField, List<Item>> e : lists.entrySet()) {
            if (e.getKey().slot() != slot) continue;
            for (Item i : e.getValue()) {
                if (i.source().fromUser() && i.raw() != null && i.seq() > best) {
                    best = i.seq();
                    raw = i.raw();
                }
            }
        }
        return Optional.ofNullable(raw);
    }
}
