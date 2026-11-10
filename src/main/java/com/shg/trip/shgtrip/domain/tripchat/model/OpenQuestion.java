package com.shg.trip.shgtrip.domain.tripchat.model;

import java.util.List;

/**
 * @param tool    카드 하단 버튼. UNKNOWN("아직 모르겠어요")·NONE("정해둔 건 없어요")·null
 * @param askKey  같은 질문을 두 번 세지 않고 되묻기를 한 번만 하게 하는 키
 */
public record OpenQuestion(
        String id,
        Slot slot,
        QuestionKind kind,
        String lead,
        String title,
        String description,
        String placeholder,
        List<String> examples,
        boolean examplesOpen,
        String tool,
        String askKey,
        String prefill,
        boolean returnToReview
) {
    public static final String TOOL_UNKNOWN = "UNKNOWN";
    public static final String TOOL_NONE = "NONE";

    public OpenQuestion withLead(String newLead) {
        return new OpenQuestion(id, slot, kind, newLead, title, description, placeholder, examples, examplesOpen,
                tool, askKey, prefill, returnToReview);
    }
}
