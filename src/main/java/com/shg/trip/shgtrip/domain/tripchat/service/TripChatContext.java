package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.ChatState;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import com.shg.trip.shgtrip.domain.tripchat.model.Turn;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Haiku에 보낼 맥락. 전체 대화 대신 정리된 조건과 최근 두 턴만 보낸다. */
final class TripChatContext {

    private static final int RECENT_TURNS = 2;

    private TripChatContext() {}

    static String build(Draft draft, ChatState state, OpenQuestion question, LocalDate today) {
        StringBuilder sb = new StringBuilder("<context>\n");
        sb.append("오늘: ").append(today).append(" (").append(DateCandidates.weekday(today)).append(")\n");
        sb.append("질문 항목: ").append(question.slot()).append('\n');
        sb.append("질문 종류: ").append(kindLabel(question)).append('\n');
        sb.append("질문: ").append(question.title()).append('\n');
        sb.append("정리된 조건:\n");
        boolean any = false;
        for (Map.Entry<?, Draft.Fact> e : draft.values().entrySet()) {
            Draft.Fact f = e.getValue();
            sb.append("- ").append(e.getKey()).append(": ").append(f.value())
                    .append(" (").append(f.source().name().toLowerCase()).append(")\n");
            any = true;
        }
        for (Map.Entry<?, List<Item>> e : draft.lists().entrySet()) {
            for (Item i : e.getValue()) {
                sb.append("- ").append(e.getKey()).append(": ").append(i.text());
                if (i.date() != null || i.time() != null) {
                    sb.append(" [").append(i.date() == null ? "" : i.date()).append(' ')
                            .append(i.time() == null ? "" : i.time()).append(']');
                }
                sb.append(" (").append(i.source().name().toLowerCase()).append(")\n");
                any = true;
            }
        }
        if (!any) sb.append("- 없음\n");
        sb.append("항목 상태:");
        for (Slot slot : Slot.values()) {
            FieldStatus status = draft.slotStatus(slot);
            sb.append(' ').append(slot).append('=').append(status == null ? "미응답" : status.name().toLowerCase());
        }
        sb.append('\n');
        List<Turn> turns = state.getTurns();
        if (!turns.isEmpty()) {
            sb.append("최근 대화:\n");
            for (Turn t : turns.subList(Math.max(0, turns.size() - RECENT_TURNS), turns.size())) {
                sb.append("- 질문: ").append(t.question()).append(" / 답: ").append(t.answer()).append('\n');
            }
        }
        return sb.append("</context>").toString();
    }

    private static String kindLabel(OpenQuestion q) {
        return switch (q.kind()) {
            case BASE -> "기본 질문";
            case FOLLOW_UP -> "되묻기";
            case EDIT -> "수정 (사용자가 이 항목을 고쳐 답함)";
            case FIX -> "확인 화면 보완 (생성에 꼭 필요한 정보)";
            case EXTRA -> "확인 화면 추가 조건";
        };
    }
}
