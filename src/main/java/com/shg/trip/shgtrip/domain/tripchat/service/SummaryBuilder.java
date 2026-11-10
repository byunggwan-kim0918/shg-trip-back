package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatResponse;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 확인 화면·답변 모아보기의 요약 행. 사용자 말은 사용자 말 그대로, 추론은 보조 줄에 (제안)으로 둔다. */
public final class SummaryBuilder {

    static final String UNSET = "아직 미정";

    private SummaryBuilder() {}

    public static TripChatResponse.Summary build(Draft draft, TripRequestMapper.Result mapping, LocalDate today) {
        List<TripChatResponse.Row> rows = new ArrayList<>();
        for (Slot slot : Slot.ASK_ORDER) {
            String value = value(slot, draft, today);
            rows.add(new TripChatResponse.Row(slot.name(), slot.label(), value,
                    mapping.notes().getOrDefault(slot, List.of()), !UNSET.equals(value)));
        }
        String extras = extras(draft);
        if (extras != null) {
            rows.add(new TripChatResponse.Row(Slot.EXTRAS.name(), Slot.EXTRAS.label(), extras,
                    mapping.notes().getOrDefault(Slot.EXTRAS, List.of()), true));
        }
        List<TripChatResponse.Fix> fixes = mapping.blockers().stream()
                .map(b -> new TripChatResponse.Fix(TripChatService.FIX_PREFIX + b.key(), b.slot().name(), b.title(),
                        b.description(), b.placeholder(), b.examples()))
                .toList();
        return new TripChatResponse.Summary(rows, fixes, mapping.ready());
    }

    static String value(Slot slot, Draft draft, LocalDate today) {
        String v = switch (slot) {
            case CITY -> draft.value(DraftField.DESTINATION);
            case DATES -> dates(draft, today);
            case COMPANY -> company(draft);
            case MOOD -> draft.value(DraftField.PRIORITIES);
            case TRANSPORT -> transport(draft);
            case ANCHORS -> anchors(draft);
            case EXTRAS -> extras(draft);
        };
        return v != null ? v : statusLabel(draft.slotStatus(slot));
    }

    private static String statusLabel(FieldStatus status) {
        if (status == null) return UNSET;
        return switch (status) {
            case ANY -> "상관없음";
            case DELEGATED -> "추천에 맡김";
            case NONE -> "정해둔 건 없음";
            default -> UNSET;
        };
    }

    private static String dates(Draft draft, LocalDate today) {
        TripRequestMapper.DateRange resolved = TripRequestMapper.dates(draft);
        LocalDate start = resolved.start();
        LocalDate end = resolved.end();
        if (start != null && end != null) {
            long nights = ChronoUnit.DAYS.between(start, end);
            String range = md(start) + " ~ " + (end.getMonthValue() != start.getMonthValue() ? md(end)
                    : end.getDayOfMonth() + "일(" + DateCandidates.weekday(end) + ")");
            return nights > 0 ? range + " · " + nights + "박 " + (nights + 1) + "일" : range;
        }
        Integer nights = TripRequestMapper.parseInt(draft.value(DraftField.NIGHTS), 1, 30);
        DateCandidates.Approx approx = DateCandidates.parseApprox(draft.value(DraftField.DATE_APPROX));
        List<String> parts = new ArrayList<>();
        if (approx != null) parts.add(DateCandidates.approxLabel(approx, today) + "쯤");
        if (nights != null) parts.add(nights + "박 " + (nights + 1) + "일");
        if (parts.isEmpty()) return null;
        parts.add("날짜 미정");
        return String.join(" · ", parts);
    }

    private static String md(LocalDate d) {
        return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일(" + DateCandidates.weekday(d) + ")";
    }

    private static String company(Draft draft) {
        return draft.fact(DraftField.COMPANION).map(f -> {
            if (f.raw() != null) return f.raw();
            String label = GeneratorSupport.COMPANION_LABELS.getOrDefault(f.value(), f.value());
            String size = draft.value(DraftField.PARTY_SIZE);
            return size != null ? label + " · " + size + "명" : label;
        }).orElseGet(() -> {
            String size = draft.value(DraftField.PARTY_SIZE);
            return size != null ? size + "명" : null;
        });
    }

    private static String transport(Draft draft) {
        String walking = draft.value(DraftField.WALKING);
        String mode = draft.fact(DraftField.TRANSPORT).map(f -> f.source() == FieldSource.TENTATIVE
                ? "도보를 생각 중 · 확정 전"
                : GeneratorSupport.TRANSPORT_LABELS.getOrDefault(f.value(), f.value())).orElse(null);
        if (mode == null) return walking;
        return walking != null ? mode + " · " + walking : mode;
    }

    private static String anchors(Draft draft) {
        List<String> parts = new ArrayList<>();
        String lodging = TripRequestMapper.userValue(draft, DraftField.LODGING);
        String state = TripRequestMapper.userValue(draft, DraftField.LODGING_STATE);
        if ("booked".equals(state) && lodging != null) parts.add("숙소 예약: " + lodging);
        else if ("wish".equals(state) && lodging != null) parts.add("숙소는 " + lodging + " 쪽");
        else if ("none".equals(state)) parts.add("숙소는 아직 없음");
        List<Item> visits = TripRequestMapper.userItems(draft, DraftField.MUST_VISIT);
        if (!visits.isEmpty()) parts.add("꼭 갈 곳: " + String.join(", ", visits.stream().map(Item::text).toList()));
        for (Item b : TripRequestMapper.userItems(draft, DraftField.BOOKED)) {
            List<String> when = new ArrayList<>();
            LocalDate d = TripRequestMapper.parseDate(b.date());
            if (d != null) when.add(d.getMonthValue() + "/" + d.getDayOfMonth());
            if (b.time() != null) when.add(b.time());
            parts.add("예약: " + b.text() + (when.isEmpty() ? "" : " " + String.join(" ", when)));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    static String extras(Draft draft) {
        List<String> parts = new ArrayList<>();
        Long budget = TripRequestMapper.parseLong(draft.value(DraftField.BUDGET));
        if (budget != null) {
            String scope = draft.value(DraftField.BUDGET_SCOPE);
            String prefix = "per_person".equals(scope) ? "1인 " : "excluding_lodging".equals(scope) ? "숙박 제외 " : "일행 전체 ";
            parts.add("예산 " + prefix + TripRequestMapper.won(budget));
        }
        String arrival = draft.value(DraftField.ARRIVAL_TIME);
        parts.add(arrival != null ? "첫날 " + arrival + " 도착" : draft.value(DraftField.ARRIVAL_NOTE));
        String departure = draft.value(DraftField.DEPARTURE_TIME);
        parts.add(departure != null ? "마지막 날 " + departure + " 출발" : draft.value(DraftField.DEPARTURE_NOTE));
        list(parts, "좋아하는 음식", draft.items(DraftField.FOOD_LIKES));
        list(parts, "피할 음식", draft.items(DraftField.FOOD_AVOID));
        list(parts, "피할 곳", draft.items(DraftField.AVOID_PLACES));
        list(parts, "피할 활동", draft.items(DraftField.AVOID_ACTIVITIES));
        list(parts, "그 밖에", draft.items(DraftField.NOTES));
        List<String> present = parts.stream().filter(p -> p != null && !p.isBlank()).toList();
        return present.isEmpty() ? null : String.join(" · ", present);
    }

    private static void list(List<String> parts, String label, List<Item> items) {
        if (!items.isEmpty()) parts.add(label + ": " + String.join(", ", items.stream().map(Item::text).toList()));
    }
}
