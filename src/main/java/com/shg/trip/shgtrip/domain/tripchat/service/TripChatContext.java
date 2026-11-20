package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.ChatState;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.EventOp;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import com.shg.trip.shgtrip.domain.tripchat.model.Turn;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Haiku에 보낼 매 턴 맥락. 여행자가 여러 턴에 걸쳐 말을 바꾸므로 대화 전체와, 조건마다 근거가 된 답 번호·원문을 함께 보낸다.
 * 지침은 시스템 프롬프트에 고정하고 매 턴 바뀌는 것만 여기 담아 프롬프트 캐시를 살린다.
 */
final class TripChatContext {

    /** 대화 턴 상한보다 작게 잡아 긴 대화에서도 입력 토큰을 묶는다. 앞부분은 조건 줄의 원문으로 남는다. */
    static final int MAX_CONTEXT_TURNS = 30;

    /** 지운 조건은 최근 것만 보낸다. 철회를 되돌릴 때 필요한 것은 가까운 몇 턴 안의 항목이다. */
    static final int MAX_REMOVED = 10;

    private TripChatContext() {}

    static String build(Draft draft, ChatState state, OpenQuestion question, LocalDate today, int abuseCount,
                        int abuseLimit) {
        List<Turn> turns = state.getTurns();
        StringBuilder sb = new StringBuilder();
        sb.append("<today>").append(today).append(" (").append(DateCandidates.weekday(today)).append(")</today>\n");
        sb.append("<question>").append(question.slot()).append(' ').append(kindLabel(question)).append(" \"")
                .append(TextNormalizer.untag(question.title())).append("\"</question>\n");

        sb.append("<conditions>\n");
        boolean any = false;
        for (Map.Entry<?, Draft.Fact> e : draft.values().entrySet()) {
            Draft.Fact f = e.getValue();
            line(sb, e.getKey(), f.value(), null, f.source().name(), f.raw(), turns);
            any = true;
        }
        for (Map.Entry<?, List<Item>> e : draft.lists().entrySet()) {
            for (Item i : e.getValue()) {
                String booking = i.date() == null && i.time() == null ? null
                        : ((i.date() == null ? "" : i.date()) + " " + (i.time() == null ? "" : i.time())).strip() + " 예약";
                line(sb, e.getKey(), i.text(), booking, i.source().name(), i.raw(), turns);
                any = true;
            }
        }
        if (!any) sb.append("없음\n");
        sb.append("항목 상태:");
        for (Slot slot : Slot.values()) {
            FieldStatus status = draft.slotStatus(slot);
            sb.append(' ').append(slot).append('=').append(status == null ? "미응답" : status.name().toLowerCase());
        }
        sb.append("\n</conditions>\n");

        sb.append("<removed>\n");
        List<String> removed = removedLines(state.getEvents(), turns);
        if (removed.isEmpty()) sb.append("없음\n");
        removed.forEach(l -> sb.append(l).append('\n'));
        sb.append("</removed>\n");

        sb.append("<conversation>\n");
        if (turns.isEmpty()) sb.append("없음\n");
        for (int n = Math.max(0, turns.size() - MAX_CONTEXT_TURNS); n < turns.size(); n++) {
            Turn t = turns.get(n);
            sb.append(n + 1).append(". 질문(").append(t.slot()).append("): ").append(TextNormalizer.untag(t.question()))
                    .append(" / 답: ").append(TextNormalizer.untag(t.answer())).append('\n');
        }
        sb.append("</conversation>\n");
        sb.append("<moderation>앞선 경고 ").append(abuseCount).append("회. 이번 답이 부적절하면 ")
                .append(abuseCount + 1).append("번째이고, ").append(abuseLimit).append("번째에 대화 입력이 제한된다</moderation>");
        return sb.toString();
    }

    /** 값은 remove에 그대로 옮겨 쓰이므로 예약 날짜·시각 같은 부가 정보는 괄호 안에 둔다. */
    private static void line(StringBuilder sb, Object field, String value, String extra, String source, String raw,
                             List<Turn> turns) {
        sb.append("- ").append(field).append(": ").append(TextNormalizer.untag(value)).append(" (");
        if (extra != null) sb.append(extra).append(", ");
        sb.append(source.toLowerCase());
        int answer = answerNumber(raw, turns);
        if (answer > 0) sb.append(", ").append(answer).append("번 답 \"").append(TextNormalizer.untag(raw)).append('"');
        sb.append(")\n");
    }

    /** 여행자 말로 지웠고 아직 다시 넣지 않은 목록 항목. 피할 것을 철회하면 그 때문에 지운 바람을 되살리는 데 쓴다. */
    static List<String> removedLines(List<DraftEvent> events, List<Turn> turns) {
        Map<String, String> addedRaw = new HashMap<>();
        Map<String, String> removed = new LinkedHashMap<>();
        for (DraftEvent e : events) {
            if (e.field() == null || !e.field().isList()) continue;
            // 항목을 통째로 고쳐 말했으면 그 전에 지운 것도 새 답으로 대신한 것이라 되살릴 대상이 아니다.
            if (e.op() == EventOp.CLEAR) {
                String prefix = e.field() + "|";
                addedRaw.keySet().removeIf(k -> k.startsWith(prefix));
                removed.keySet().removeIf(k -> k.startsWith(prefix));
                continue;
            }
            if (e.value() == null) continue;
            String key = e.field() + "|" + TextNormalizer.key(e.value());
            if (e.op() == EventOp.ADD) {
                addedRaw.put(key, e.raw());
                removed.remove(key);
            } else if (e.op() == EventOp.REMOVE && addedRaw.containsKey(key)) {
                removed.remove(key);
                removed.put(key, removedLine(e, addedRaw.get(key), turns));
            }
        }
        List<String> lines = new ArrayList<>(removed.values());
        return lines.subList(Math.max(0, lines.size() - MAX_REMOVED), lines.size());
    }

    private static String removedLine(DraftEvent e, String addedRaw, List<Turn> turns) {
        StringBuilder sb = new StringBuilder("- ").append(e.field()).append(": ").append(TextNormalizer.untag(e.value()))
                .append(" (");
        int added = answerNumber(addedRaw, turns);
        if (added > 0) sb.append(added).append("번 답 \"").append(TextNormalizer.untag(addedRaw)).append("\"에서 넣음, ");
        int removedAt = answerNumber(e.raw(), turns);
        sb.append(removedAt > 0 ? removedAt + "번 답에서 지움" : "지움");
        return sb.append(')').toString();
    }

    /** 원문 구절이 든 가장 최근 답의 번호(1부터). 구절은 해석 단계에서 그 답 안에 있는 것만 받는다. */
    static int answerNumber(String raw, List<Turn> turns) {
        if (raw == null) return 0;
        for (int n = turns.size() - 1; n >= 0; n--) {
            if (TextNormalizer.containsKey(turns.get(n).answer(), raw)) return n + 1;
        }
        return 0;
    }

    private static String kindLabel(OpenQuestion q) {
        return switch (q.kind()) {
            case BASE -> "기본";
            case FOLLOW_UP -> "되묻기";
            case EDIT -> "수정(여행자가 이 항목을 고쳐 답함)";
            case FIX -> "확인 화면 보완(생성에 꼭 필요한 정보)";
            case EXTRA -> "확인 화면 추가 조건";
        };
    }
}
