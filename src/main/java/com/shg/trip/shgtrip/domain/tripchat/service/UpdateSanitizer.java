package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.WizardOptionIds;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.EventOp;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 모델이 낸 갱신을 서버 규칙으로 걸러 이벤트로 바꾼다. 모델 JSON은 스키마를 지켜도 값은 믿지 않는다. */
public final class UpdateSanitizer {

    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):[0-5]\\d");
    private static final Set<String> TRANSPORTS = Set.of("car", "walk", "walk_transit", "transit", "taxi", "mixed", "any");
    private static final Set<String> LODGING_STATES = Set.of("booked", "wish", "none");
    private static final Set<String> BUDGET_SCOPES = Set.of("total", "per_person", "excluding_lodging");
    private static final int MAX_ITEMS_PER_ANSWER = 8;
    private static final int MAX_RAW = 200;

    /**
     * 과거("작년 1월")나 10일을 크게 넘는 기간("한 달 살기") 표현에서 나온 날짜는 버린다.
     * 모델이 "작년 1월"을 내년으로 옮기면 사용자가 말하지 않은 날짜가 된다.
     */
    private static final Pattern OUT_OF_RANGE_DATE_EXPRESSION = Pattern.compile(
            "작년|지난\\s?해|재작년|지난\\s?달|지난\\s?주|어제|그저께"
                    + "|([한두세네]\\s?달|\\d+\\s?개월|보름|(?<!\\d)([2-9]|\\d{2,})\\s?주일?)\\s?(살기|동안|간|짜리|여행|일정|체류|머물)");

    private UpdateSanitizer() {}

    public static List<DraftEvent> toEvents(Interpretation interpretation, Slot currentSlot, String answer,
                                            LongSupplier seq) {
        List<DraftEvent> events = new ArrayList<>();
        Matcher outOfRange = OUT_OF_RANGE_DATE_EXPRESSION.matcher(answer);
        String outOfRangePhrase = outOfRange.find() ? outOfRange.group() : null;
        Map<DraftField, Integer> perField = new EnumMap<>(DraftField.class);

        for (Interpretation.Update u : interpretation.safeUpdates()) {
            DraftField field = enumOrNull(DraftField.class, u.field());
            EventOp op = enumOrNull(EventOp.class, u.op());
            if (field == null || op == null) continue;
            if (field.isList() ? op == EventOp.SET : (op == EventOp.ADD || op == EventOp.REMOVE)) continue;
            String raw = rawOrNull(u.raw(), answer);
            if (outOfRangePhrase != null && isDateField(field)
                    && (raw == null || TextNormalizer.containsKey(raw, outOfRangePhrase))) continue;

            FieldStatus status = parseStatus(u.status());
            if (status != FieldStatus.KNOWN) {
                events.add(DraftEvent.slotOutcome(seq.getAsLong(), field.slot(), status, raw));
                continue;
            }
            // 지우기는 사용자가 그렇게 말했을 때만 받는다. 모델 추론으로 사용자 값이 사라지면 안 된다.
            boolean saidByUser = raw != null && !"inferred".equals(lower(u.source()));
            if ((op == EventOp.CLEAR || op == EventOp.REMOVE) && !saidByUser) continue;
            if (op == EventOp.CLEAR) {
                events.add(DraftEvent.clearField(seq.getAsLong(), field));
                continue;
            }
            String value = normalizeValue(field, u.value());
            if (value == null) continue;
            if (field.isList() && perField.merge(field, 1, Integer::sum) > MAX_ITEMS_PER_ANSWER) continue;

            FieldSource source = source(u.source(), raw, field);
            String date = field == DraftField.BOOKED ? isoDate(u.date()) : null;
            String time = field == DraftField.BOOKED ? normalizeTime(u.time()) : null;
            events.add(new DraftEvent(seq.getAsLong(), field.slot(), field, op, value, FieldStatus.KNOWN, source,
                    source.fromUser() ? raw : null, date, time));
        }

        FieldStatus outcome = switch (lower(interpretation.slotOutcome())) {
            case "undecided" -> FieldStatus.UNDECIDED;
            case "any" -> FieldStatus.ANY;
            case "delegated" -> FieldStatus.DELEGATED;
            case "none" -> FieldStatus.NONE;
            default -> null;
        };
        if (outcome != null && currentSlot != Slot.EXTRAS) {
            events.add(DraftEvent.slotOutcome(seq.getAsLong(), currentSlot, outcome, TextNormalizer.cap(answer, MAX_RAW)));
        }
        return events;
    }

    /** 원문 조각이 실제 답에 없으면 사용자 발언이 아니라 모델 추론으로 본다. 테마·카테고리는 분류어라 늘 추론이다. */
    private static FieldSource source(String declared, String raw, DraftField field) {
        if (field == DraftField.THEMES || field == DraftField.CATEGORIES) return FieldSource.INFERRED;
        String s = lower(declared);
        if (!s.equals("user") && !s.equals("tentative")) return FieldSource.INFERRED;
        if (raw == null) return FieldSource.INFERRED;
        return s.equals("tentative") ? FieldSource.TENTATIVE : FieldSource.USER;
    }

    private static String rawOrNull(String raw, String answer) {
        String r = TextNormalizer.cap(raw, MAX_RAW);
        if (r == null) return null;
        return TextNormalizer.containsKey(answer, r) ? r : null;
    }

    static String normalizeValue(DraftField field, String value) {
        String v = value == null ? null : value.strip();
        if (v == null || v.isEmpty()) return null;
        return switch (field) {
            case DESTINATION, LODGING -> TextNormalizer.cap(v, TripRequestMapper.MAX_DESTINATION);
            case START_DATE, END_DATE -> isoDate(v);
            case DATE_APPROX -> DateCandidates.parseApprox(v) != null ? v : null;
            case NIGHTS -> intIn(v, 1, 30);
            case COMPANION -> inSet(v, GeneratorSupport.COMPANION_LABELS.keySet());
            case PARTY_SIZE -> intIn(v, 1, 20);
            case PRIORITIES -> TextNormalizer.cap(v, 80);
            case THEMES -> inSet(v, WizardOptionIds.THEMES);
            case CATEGORIES -> inSet(v, WizardOptionIds.CATEGORIES);
            case PACE -> inSet(v, WizardOptionIds.PACES);
            case TRANSPORT -> inSet(v, TRANSPORTS);
            case LODGING_STATE -> inSet(v, LODGING_STATES);
            case BUDGET_SCOPE -> inSet(v, BUDGET_SCOPES);
            case BUDGET -> {
                String digits = v.replaceAll("[,\\s원]", "");
                Long amount = TripRequestMapper.parseLong(digits);
                yield amount != null && amount > 0 && amount <= TripRequestMapper.MAX_BUDGET ? String.valueOf(amount) : null;
            }
            case ARRIVAL_TIME, DEPARTURE_TIME -> normalizeTime(v);
            case WALKING, ARRIVAL_NOTE, DEPARTURE_NOTE -> TextNormalizer.cap(v, 60);
            case MUST_VISIT, BOOKED -> TextNormalizer.cap(v, TripRequestMapper.MAX_DESTINATION);
            case FOOD_LIKES, FOOD_AVOID, AVOID_PLACES, AVOID_ACTIVITIES, NOTES -> TextNormalizer.cap(v, 60);
        };
    }

    private static boolean isDateField(DraftField f) {
        return f == DraftField.START_DATE || f == DraftField.END_DATE || f == DraftField.DATE_APPROX;
    }

    private static FieldStatus parseStatus(String status) {
        return switch (lower(status)) {
            case "undecided" -> FieldStatus.UNDECIDED;
            case "any" -> FieldStatus.ANY;
            case "delegated" -> FieldStatus.DELEGATED;
            case "none" -> FieldStatus.NONE;
            default -> FieldStatus.KNOWN;
        };
    }

    static String isoDate(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDate.parse(v.strip()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizeTime(String v) {
        if (v == null) return null;
        String t = v.strip();
        if (!TIME.matcher(t).matches()) return null;
        return t.length() == 4 ? "0" + t : t;
    }

    private static String intIn(String v, int min, int max) {
        Integer n = TripRequestMapper.parseInt(v.replaceAll("[^0-9]", ""), min, max);
        return n == null ? null : String.valueOf(n);
    }

    private static String inSet(String v, Set<String> allowed) {
        String l = v.toLowerCase(Locale.ROOT);
        return allowed.contains(l) ? l : null;
    }

    private static String lower(String v) {
        return v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        if (name == null) return null;
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
