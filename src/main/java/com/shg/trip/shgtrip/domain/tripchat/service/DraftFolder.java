package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Fact;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.EventOp;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 이벤트를 순서대로 접는다. 같은 필드는 나중 값이 이기되, 사용자가 말한 값은 모델 추론이 덮지 못한다.
 * 항목 수정은 그 항목 필드를 지우는 이벤트 뒤에 새 해석을 붙이는 것이라 다른 항목의 답은 그대로 남는다.
 */
public final class DraftFolder {

    private DraftFolder() {}

    public static Draft fold(List<DraftEvent> events) {
        Map<DraftField, Fact> values = new EnumMap<>(DraftField.class);
        Map<DraftField, List<Item>> lists = new EnumMap<>(DraftField.class);
        Map<Slot, FieldStatus> outcomes = new EnumMap<>(Slot.class);

        List<DraftEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong(DraftEvent::seq));
        for (DraftEvent e : ordered) {
            if (e.field() == null) {
                if (e.op() == EventOp.CLEAR) outcomes.remove(e.slot());
                else if (e.status() != null) outcomes.put(e.slot(), e.status());
                continue;
            }
            if (e.field().isList()) applyList(lists, e);
            else applyValue(values, e);
        }
        lists.values().removeIf(List::isEmpty);
        return new Draft(values, lists, outcomes);
    }

    private static void applyValue(Map<DraftField, Fact> values, DraftEvent e) {
        switch (e.op()) {
            case SET -> {
                Fact current = values.get(e.field());
                if (current != null && current.source().fromUser() && !e.source().fromUser()) return;
                values.put(e.field(), new Fact(e.value(), e.source(), e.raw(), e.seq()));
            }
            case CLEAR -> values.remove(e.field());
            default -> { }
        }
    }

    private static void applyList(Map<DraftField, List<Item>> lists, DraftEvent e) {
        List<Item> items = lists.computeIfAbsent(e.field(), f -> new ArrayList<>());
        String key = TextNormalizer.key(e.value());
        switch (e.op()) {
            case ADD -> {
                for (int i = 0; i < items.size(); i++) {
                    Item existing = items.get(i);
                    if (!TextNormalizer.key(existing.text()).equals(key)) continue;
                    if (existing.source().fromUser() && !e.source().fromUser()) return;
                    items.set(i, new Item(e.value(), e.source(), e.raw(),
                            e.date() != null ? e.date() : existing.date(),
                            e.time() != null ? e.time() : existing.time(), e.seq()));
                    return;
                }
                items.add(new Item(e.value(), e.source(), e.raw(), e.date(), e.time(), e.seq()));
            }
            case REMOVE -> items.removeIf(i -> TextNormalizer.key(i.text()).equals(key));
            case CLEAR -> items.clear();
            default -> { }
        }
    }
}
