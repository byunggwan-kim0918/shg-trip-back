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
 * 예약 시간 누락·생성 불가 날짜는 그와 별개로 한 번씩, 조건 충돌은 부딪히는 내용마다 한 번씩 묻는다.
 */
public final class NextStepPolicy {

    /** 폭주 방지용 상한. 보통은 질문 6개 안팎에서 확인 화면으로 간다. */
    static final int MAX_QUESTIONS = 10;
    /** 반응 문장은 질문 위 한 줄이다. 프롬프트의 50자보다 조금 넉넉히 받고 넘으면 코드 문구로 대신한다. */
    static final int MAX_REPLY = 70;
    /**
     * 다른 답에 일부가 나와도 한 번은 직접 묻는 항목. 하고 싶은 것·정해둔 것은 여러 가지라 지나가며 한 말 하나로 건너뛰면
     * 여행자가 나머지를 말할 기회가 없다.
     */
    private static final Set<Slot> OPEN_SLOTS = Set.of(Slot.MOOD, Slot.ANCHORS);
    private static final Set<String> CRITICAL_REASONS = Set.of("booking_time_missing", "conflict");
    static final String CONFLICT_KEY_PREFIX = "CRITICAL:conflict:";

    private NextStepPolicy() {}

    /** @param question null이면 확인 화면으로 간다 */
    public record Decision(OpenQuestion question) {
        public boolean review() {
            return question == null;
        }
    }

    public static Decision decide(Draft draft, Set<String> askedKeys, OpenQuestion answered, Interpretation it,
                                  String answerKind, boolean datesTouched, LocalDate today) {
        boolean onReview = answered.kind() == QuestionKind.FIX || answered.kind() == QuestionKind.EXTRA;
        if (it != null && !it.isAnswer() && !onReview) {
            String reply = reply(it);
            return new Decision(answered.withLead(reply != null ? reply : QuestionBank.OFF_TOPIC_LEAD));
        }
        boolean text = "text".equals(answerKind);
        boolean budgetLeft = counted(askedKeys) < MAX_QUESTIONS;
        // 확인 화면에서 덧붙인 말도 앞의 말과 부딪히면 되묻는다. 조용히 한쪽을 고르면 여행자가 알아챌 길이 없다.
        if (onReview) {
            OpenQuestion critical = text && budgetLeft ? modelFollowUp(draft, askedKeys, answered, it, today) : null;
            return new Decision(critical);
        }

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

        String lead = leadAfter(answered, draft, answerKind, it);
        for (Slot slot : Slot.ASK_ORDER) {
            if (OPEN_SLOTS.contains(slot) ? alreadyAsked(slot, draft, askedKeys, answered) : draft.resolved(slot)) {
                continue;
            }
            if (!budgetLeft) return new Decision(null);
            return new Decision(QuestionBank.base(slot, draft, today, lead));
        }
        return new Decision(null);
    }

    /** 모른다는 답과 지킬 수 없는 조건의 되묻기는 빼고 센다. 그 되묻기가 상한을 채우면 뒤쪽 기본 질문을 묻지 못한다. */
    static int counted(Set<String> askedKeys) {
        return (int) askedKeys.stream().filter(k -> !k.startsWith(UndecidedSlots.CITY_RECOMMEND_KEY)
                && !k.startsWith(UndecidedSlots.DATES_SUGGEST_KEY)
                && !k.startsWith(TripFeasibilityQuestions.PREFIX)).count();
    }

    // 여행자가 바람을 모두 취소해도 이미 물은 항목을 처음 문구로 다시 묻지 않는다.
    private static boolean alreadyAsked(Slot slot, Draft draft, Set<String> askedKeys, OpenQuestion answered) {
        return askedKeys.contains("BASE:" + slot) || draft.outcomes().containsKey(slot)
                || answered.slot() == slot && answered.kind() == QuestionKind.EDIT;
    }

    /** 모델이 쓴 반응 문장. 길이 검사를 통과할 때만 쓰고, 아니면 코드 문구로 대신한다. */
    static String reply(Interpretation it) {
        return it == null ? null : fit(it.reply(), MAX_REPLY);
    }

    private static String leadAfter(OpenQuestion answered, Draft draft, String answerKind, Interpretation it) {
        String reply = reply(it);
        if (reply != null && "text".equals(answerKind)) return reply;
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
        Interpretation.FollowUp f = it == null ? null : it.followUp();
        String outcome = it == null || it.slotOutcome() == null ? "" : it.slotOutcome().toLowerCase(Locale.ROOT);
        boolean unclear = outcome.equals("unclear");
        boolean wanted = f != null && f.needed();
        if (!wanted && !unclear) return null;

        String reason = wanted && f.reason() != null ? f.reason().toLowerCase(Locale.ROOT) : "vague";
        boolean critical = CRITICAL_REASONS.contains(reason);
        boolean onReview = answered.kind() == QuestionKind.FIX || answered.kind() == QuestionKind.EXTRA;
        if ((slot == Slot.EXTRAS || onReview) && !critical) return null;
        // 충돌 되묻기의 답이 다시 부딪히면 문구만 바꿔 계속 묻게 되므로 메모로 넘긴다.
        boolean conflict = reason.equals("conflict");
        if (conflict && answered.askKey() != null && answered.askKey().startsWith(CONFLICT_KEY_PREFIX)) return null;
        // 한 항목에서도 부딪히는 내용이 다르면 따로 묻는다. 묻지 못한 충돌은 모델이 기록하지 않아 답이 사라진다.
        String key = critical ? "CRITICAL:" + reason + ":" + slot + ":" + titleKey(f) : "FOLLOW_UP:" + slot;
        if (askedKeys.contains(key)) return null;
        if (!critical) {
            if (answered.kind() == QuestionKind.FOLLOW_UP) return null;
            FieldStatus status = draft.slotStatus(slot);
            if (status == FieldStatus.UNDECIDED || status == FieldStatus.ANY
                    || status == FieldStatus.DELEGATED || status == FieldStatus.NONE) return null;
        }
        OpenQuestion fromModel = wanted ? fromModel(slot, key, f, reply(it)) : null;
        if (fromModel != null) return fromModel;
        // 정해진 문구는 무엇이 부딪혔는지 말하지 못해 충돌은 묻지 않고 메모로 넘긴다.
        return conflict ? null : QuestionBank.followUpTemplate(slot, key, today);
    }

    private static String titleKey(Interpretation.FollowUp f) {
        return f == null || f.title() == null ? "" : TextNormalizer.key(f.title());
    }

    /** 모델이 부딪힌다고 본 답을 이번 결정이 되묻지 못했는지. 그런 답은 기록된 것이 없어 메모로라도 남겨야 한다. */
    /** 모델이 이번 답에서 부딪힘이나 예약 시각을 되묻자고 했는지. 이 되묻기가 코드의 다른 되묻기보다 먼저다. */
    static boolean modelAsksCritical(Interpretation it) {
        return it != null && it.isAnswer() && it.followUp() != null && it.followUp().needed()
                && CRITICAL_REASONS.contains(it.followUp().reason());
    }

    static boolean conflictUnasked(Interpretation it, Decision d) {
        if (it == null || !it.isAnswer()) return false;
        Interpretation.FollowUp f = it.followUp();
        if (f == null || !f.needed() || !"conflict".equalsIgnoreCase(f.reason())) return false;
        OpenQuestion q = d.question();
        return q == null || q.askKey() == null || !q.askKey().startsWith(CONFLICT_KEY_PREFIX);
    }

    /** 모델 문구는 길이·개수 검사를 통과할 때만 쓴다. */
    static OpenQuestion fromModel(Slot slot, String key, Interpretation.FollowUp f, String lead) {
        String title = fit(f.title(), 60);
        if (title == null) return null;
        List<String> examples = f.examples() == null ? List.of() : f.examples().stream()
                .map(e -> fit(e, 40)).filter(e -> e != null).distinct().limit(3).toList();
        String placeholder = fit(f.placeholder(), 40);
        return QuestionBank.followUp(slot, key, lead != null ? lead : QuestionBank.FOLLOW_UP_LEAD, title,
                fit(f.desc(), 80), placeholder != null ? placeholder : "편하게 답해주세요", examples);
    }

    static String fit(String text, int max) {
        if (text == null) return null;
        String t = text.strip().replaceAll("\\s+", " ");
        return t.isEmpty() || t.length() > max ? null : t;
    }
}
