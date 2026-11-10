package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.QuestionKind;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 답 다음에 무엇을 할지 코드가 정한다. 모델은 되묻기가 필요한지만 제안한다.
 * 항목은 정해진 순서로 묻고 이미 답한 항목은 건너뛴다. 되묻기는 항목당 한 번이고,
 * 예약 시간 누락·조건 충돌·생성 불가 날짜는 그와 별개로 한 번씩만 묻는다.
 */
public final class NextStepPolicy {

    /** 폭주 방지용 상한. 보통은 질문 6개 안팎에서 확인 화면으로 간다. */
    static final int MAX_QUESTIONS = 10;
    private static final Set<String> CRITICAL_REASONS = Set.of("booking_time_missing", "conflict");

    private NextStepPolicy() {}

    /** @param question null이면 확인 화면으로 간다 */
    public record Decision(OpenQuestion question) {
        public boolean review() {
            return question == null;
        }
    }

    public static Decision decide(Draft draft, Set<String> askedKeys, OpenQuestion answered, Interpretation it,
                                  String answerKind, boolean datesTouched, LocalDate today) {
        if (it != null && it.offTopic() && answered.kind() != QuestionKind.FIX && answered.kind() != QuestionKind.EXTRA) {
            return new Decision(answered.withLead(QuestionBank.OFF_TOPIC_LEAD));
        }
        if (answered.kind() == QuestionKind.FIX || answered.kind() == QuestionKind.EXTRA) {
            return new Decision(null);
        }
        boolean text = "text".equals(answerKind);
        boolean budgetLeft = askedKeys.size() < MAX_QUESTIONS;

        if (datesTouched && text && budgetLeft) {
            OpenQuestion dateFollowUp = dateFollowUp(draft, askedKeys, today);
            if (dateFollowUp != null) return new Decision(dateFollowUp);
        }
        if (text && budgetLeft) {
            OpenQuestion conflict = bookingConflict(draft, askedKeys, today);
            if (conflict != null) return new Decision(conflict);
            OpenQuestion modelFollowUp = modelFollowUp(draft, askedKeys, answered, it, today);
            if (modelFollowUp != null) return new Decision(modelFollowUp);
        }

        if (answered.kind() == QuestionKind.EDIT && answered.returnToReview()) return new Decision(null);

        String lead = leadAfter(answered, draft, answerKind);
        for (Slot slot : Slot.ASK_ORDER) {
            if (draft.resolved(slot)) continue;
            if (!budgetLeft) return new Decision(null);
            return new Decision(QuestionBank.base(slot, draft, today, lead));
        }
        return new Decision(null);
    }

    private static String leadAfter(OpenQuestion answered, Draft draft, String answerKind) {
        if (answered.kind() == QuestionKind.FOLLOW_UP) {
            return "unknown".equals(answerKind) ? QuestionBank.UNDECIDED_LEAD : QuestionBank.FOLLOW_UP_DONE_LEAD;
        }
        return QuestionBank.ack(answered.slot(), draft);
    }

    /** 지난 날짜·당일치기·10일 초과는 생성할 수 없어 이유를 말하고 묻는다. 대략 날짜는 실제 날짜 후보를 예시로 준다. */
    private static OpenQuestion dateFollowUp(Draft draft, Set<String> askedKeys, LocalDate today) {
        if (draft.slotStatus(Slot.DATES) != FieldStatus.KNOWN) return null;
        TripRequestMapper.DateRange range = TripRequestMapper.dates(draft);
        TripRequestMapper.Blocker b = TripRequestMapper.dateBlocker(draft, range.start(), range.end(), today);
        if (b == null) return null;
        if (b.key().equals("dates_missing")) {
            String key = "FOLLOW_UP:" + Slot.DATES;
            if (askedKeys.contains(key)) return null;
            DateCandidates.Approx approx = DateCandidates.parseApprox(draft.value(DraftField.DATE_APPROX));
            String lead = approx != null
                    ? DateCandidates.approxLabel(approx, today) + "쯤으로 생각하고 있군요."
                    : "여행 기간을 기억해둘게요.";
            String title = approx != null ? "며칠쯤 떠나면 좋을까요?" : "언제쯤 떠날 생각인가요?";
            return QuestionBank.followUp(Slot.DATES, key, lead, title,
                    "날짜가 아직 없으면 미정으로 두어도 괜찮아요.", b.placeholder(), b.examples());
        }
        String key = "CRITICAL:" + b.key();
        if (askedKeys.contains(key)) return null;
        String lead = switch (b.key()) {
            case "dates_past" -> "지난 날짜로 이해했어요.";
            case "dates_day_trip" -> "당일치기로 이해했어요.";
            default -> "꽤 긴 여행이군요.";
        };
        return QuestionBank.followUp(Slot.DATES, key, lead, b.title(), b.description(), b.placeholder(), b.examples());
    }

    /** 여행 날짜를 바꿔 예약 날짜가 기간 밖으로 나가면 어느 쪽이 맞는지 묻는다. 그대로 덮어쓰지 않는다. */
    private static OpenQuestion bookingConflict(Draft draft, Set<String> askedKeys, LocalDate today) {
        TripRequestMapper.DateRange range = TripRequestMapper.dates(draft);
        LocalDate start = range.start();
        LocalDate end = range.end();
        if (start == null || end == null) return null;
        for (Item item : TripRequestMapper.userItems(draft, DraftField.BOOKED)) {
            LocalDate d = TripRequestMapper.parseDate(item.date());
            if (d == null || (!d.isBefore(start) && !d.isAfter(end))) continue;
            String key = "CRITICAL:booked:" + TextNormalizer.key(item.text());
            if (askedKeys.contains(key)) continue;
            return QuestionBank.followUp(Slot.ANCHORS, key, "예약 날짜가 여행 기간 밖이에요.",
                    item.text() + " 예약은 어떻게 할까요?", "여행 날짜와 예약 날짜 중 맞는 쪽을 알려주세요.",
                    "예: 여행 날짜를 예약에 맞출게", List.of("여행 날짜를 예약에 맞출게", "그 예약은 빼줘"));
        }
        return null;
    }

    private static OpenQuestion modelFollowUp(Draft draft, Set<String> askedKeys, OpenQuestion answered,
                                              Interpretation it, LocalDate today) {
        Slot slot = answered.slot();
        if (slot == Slot.EXTRAS) return null;
        Interpretation.FollowUp f = it == null ? null : it.followUp();
        String outcome = it == null || it.slotOutcome() == null ? "" : it.slotOutcome().toLowerCase(Locale.ROOT);
        boolean unclear = outcome.equals("unclear");
        boolean wanted = f != null && f.needed();
        if (!wanted && !unclear) return null;

        String reason = wanted && f.reason() != null ? f.reason().toLowerCase(Locale.ROOT) : "vague";
        boolean critical = CRITICAL_REASONS.contains(reason);
        String key = critical ? "CRITICAL:" + reason + ":" + slot : "FOLLOW_UP:" + slot;
        if (askedKeys.contains(key)) return null;
        if (!critical) {
            if (answered.kind() == QuestionKind.FOLLOW_UP) return null;
            FieldStatus status = draft.slotStatus(slot);
            if (status == FieldStatus.UNDECIDED || status == FieldStatus.ANY
                    || status == FieldStatus.DELEGATED || status == FieldStatus.NONE) return null;
        }
        OpenQuestion fromModel = wanted ? fromModel(slot, key, f) : null;
        return fromModel != null ? fromModel : QuestionBank.followUpTemplate(slot, key, today);
    }

    /** 모델 문구는 길이·개수 검사를 통과할 때만 쓴다. */
    static OpenQuestion fromModel(Slot slot, String key, Interpretation.FollowUp f) {
        String title = fit(f.title(), 60);
        if (title == null) return null;
        String lead = fit(f.lead(), 40);
        List<String> examples = f.examples() == null ? List.of() : f.examples().stream()
                .map(e -> fit(e, 40)).filter(e -> e != null).distinct().limit(3).toList();
        String placeholder = fit(f.placeholder(), 40);
        return QuestionBank.followUp(slot, key, lead != null ? lead : QuestionBank.FOLLOW_UP_LEAD, title,
                fit(f.desc(), 80), placeholder != null ? placeholder : "편하게 답해주세요", examples);
    }

    private static String fit(String text, int max) {
        if (text == null) return null;
        String t = text.strip().replaceAll("\\s+", " ");
        return t.isEmpty() || t.length() > max ? null : t;
    }
}
