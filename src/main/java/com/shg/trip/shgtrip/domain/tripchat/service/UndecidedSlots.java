package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.ChatState;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.EventOp;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 생성에 꼭 필요한 여행지·날짜를 모른다고 한 답의 처리. 트랜잭션과 모델 호출 순서는 {@link TripChatService}가 맡는다. */
final class UndecidedSlots {

    private UndecidedSlots() {}

    /** 여행지·날짜는 생성에 꼭 필요해 모른다는 답을 확인 화면까지 미루지 않는다. 이만큼 모른다고 하면 대신 정한다. */
    static final int ANSWERS_BEFORE_PICK = 3;
    static final String CITY_RECOMMEND_KEY = "FOLLOW_UP:CITY:";
    static final String DATES_SUGGEST_KEY = "FOLLOW_UP:DATES:";

    /**
     * 여행지·날짜를 모른다고 한 그 턴의 다음 질문. 여행지는 아는 것이 하나도 없으면 취향부터 묻고, 아니면 추천할 곳을
     * 고르는 질문을 연다(추천지는 반영 뒤 서비스가 {@link #withRecommendation}으로 채운다). 날짜는 가까운 날짜를 제안한다.
     *
     * @return 다음 질문. 모른다고 한 항목이 없거나 이제 대신 정할 차례면 null
     */
    static OpenQuestion followUp(ChatState st, Draft draft, OpenQuestion answered, LocalDate today) {
        Set<String> asked = st.getAskedKeys();
        if (cityUndecided(draft) && (answered.slot() == Slot.CITY || answered.slot() == Slot.MOOD)) {
            int recommended = countPrefixed(asked, CITY_RECOMMEND_KEY);
            if (recommended + 1 >= ANSWERS_BEFORE_PICK && answered.slot() == Slot.CITY) return null;
            boolean knowsNothing = !draft.resolved(Slot.DATES) && !draft.resolved(Slot.COMPANY)
                    && !draft.resolved(Slot.MOOD);
            if (knowsNothing && !asked.contains("BASE:" + Slot.MOOD)) {
                return QuestionBank.base(Slot.MOOD, draft, today, "그럼 어떤 여행을 좋아하는지 먼저 볼게요.");
            }
            return QuestionBank.followUp(Slot.CITY, CITY_RECOMMEND_KEY + (recommended + 1),
                    "말해 준 것에 맞는 곳을 골라 볼게요.", "이런 곳은 어때요?",
                    "마음에 드는 곳을 고르거나 가고 싶은 곳을 알려주세요.", "예: 부산", List.of());
        }
        if (datesUndecided(draft) && answered.slot() == Slot.DATES) {
            int suggested = countPrefixed(asked, DATES_SUGGEST_KEY);
            if (suggested + 1 >= ANSWERS_BEFORE_PICK) return null;
            TripRequestMapper.Blocker b = TripRequestMapper.dateBlocker(draft, null, null, today);
            return QuestionBank.followUp(Slot.DATES, DATES_SUGGEST_KEY + (suggested + 1),
                    "가까운 날짜로 골라 봤어요.", "이 날짜는 어때요?", "다른 날짜를 말해도 괜찮아요.",
                    b.placeholder(), b.examples());
        }
        return null;
    }

    /**
     * 모른다는 답이 이어지면 마지막으로 권한 여행지나 가장 가까운 날짜로 정한다. 끝없이 되물으면 대화를 끝낼 수 없다.
     *
     * @return 정했으면 다음 질문 위에 붙일 말, 아니면 null
     */
    static String pick(ChatState st, Draft draft, OpenQuestion answered, LocalDate today,
                       Supplier<List<String>> candidates) {
        if (cityUndecided(draft) && answered.slot() == Slot.CITY) {
            List<String> offered = answered.askKey() != null && answered.askKey().startsWith(CITY_RECOMMEND_KEY)
                    ? answered.examples() : List.of();
            String city = !offered.isEmpty() ? offered.get(0) : candidates.get().stream().findFirst().orElse(null);
            if (city == null) return null;
            addPicked(st, Slot.CITY, DraftField.DESTINATION, city);
            return city + "(으)로 정할게요. 확인 화면에서 바꿀 수 있어요.";
        }
        if (datesUndecided(draft) && answered.slot() == Slot.DATES) {
            Integer nights = TripRequestMapper.parseInt(draft.value(DraftField.NIGHTS), 1, 30);
            DateCandidates.Range r = DateCandidates.upcoming(nights, today).stream().findFirst().orElse(null);
            if (r == null) return null;
            addPicked(st, Slot.DATES, DraftField.START_DATE, r.start().toString());
            st.getEvents().add(new DraftEvent(st.nextSeq(), Slot.DATES, DraftField.END_DATE, EventOp.SET,
                    r.end().toString(), FieldStatus.KNOWN, FieldSource.INFERRED, null, null, null));
            return DateCandidates.phrase(r, today) + "(으)로 정할게요. 확인 화면에서 바꿀 수 있어요.";
        }
        return null;
    }

    private static void addPicked(ChatState st, Slot slot, DraftField field, String value) {
        st.getEvents().add(new DraftEvent(st.nextSeq(), slot, field, EventOp.SET, value, FieldStatus.KNOWN,
                FieldSource.INFERRED, null, null, null));
        st.getEvents().add(DraftEvent.slotOutcome(st.nextSeq(), slot, FieldStatus.DELEGATED, "추천에 맡김"));
    }

    private static boolean cityUndecided(Draft draft) {
        return draft.value(DraftField.DESTINATION) == null && draft.slotStatus(Slot.CITY) != null;
    }

    private static boolean datesUndecided(Draft draft) {
        TripRequestMapper.DateRange r = TripRequestMapper.dates(draft);
        return (r.start() == null || r.end() == null) && draft.slotStatus(Slot.DATES) != null
                && draft.slotStatus(Slot.DATES) != FieldStatus.KNOWN;
    }

    private static int countPrefixed(Set<String> keys, String prefix) {
        return (int) keys.stream().filter(k -> k.startsWith(prefix)).count();
    }

    /** 추천지를 아직 채우지 않은 여행지 추천 질문인지. */
    static boolean awaitsRecommendation(OpenQuestion q) {
        return q != null && q.askKey() != null && q.askKey().startsWith(CITY_RECOMMEND_KEY) && q.examples().isEmpty();
    }

    static OpenQuestion withRecommendation(OpenQuestion q, DestinationRecommender.Recommendation rec) {
        String title = rec.picks().isEmpty() ? q.title() : String.join(", ", rec.picks()) + " 어때요?";
        return new OpenQuestion(q.id(), q.slot(), q.kind(), rec.reply() != null ? rec.reply() : q.lead(), title,
                q.description(), q.placeholder(), rec.picks(), true, q.tool(), q.askKey(), q.prefill(),
                q.returnToReview());
    }

    /** 추천 모델에 보낼 여행자의 말. 비어 있는 항목은 아직 말하지 않은 것이다. */
    static Map<String, String> travelerOf(Draft draft) {
        Map<String, String> out = new LinkedHashMap<>();
        TripRequestMapper.DateRange r = TripRequestMapper.dates(draft);
        out.put("날짜", r.start() == null ? "" : r.start() + (r.end() == null ? "" : " ~ " + r.end()));
        out.put("대략 시기", TextNormalizer.cap(Objects.toString(draft.value(DraftField.DATE_APPROX), ""), 40));
        out.put("동행", TextNormalizer.cap(Objects.toString(draft.value(DraftField.COMPANION), ""), 40));
        out.put("취향", TextNormalizer.cap(String.join(", ", draft.items(DraftField.PRIORITIES).stream()
                .map(i -> i.text()).toList()) + " " + String.join(", ", draft.items(DraftField.THEMES).stream()
                .map(i -> i.text()).toList()), 200));
        out.put("이동", TextNormalizer.cap(Objects.toString(draft.value(DraftField.TRANSPORT), ""), 40));
        return out;
    }
}
