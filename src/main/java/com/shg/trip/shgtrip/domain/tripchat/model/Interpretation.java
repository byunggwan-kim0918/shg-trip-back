package com.shg.trip.shgtrip.domain.tripchat.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Haiku 해석 결과. 서버가 UpdateSanitizer·NextStepPolicy로 검사한 뒤에만 상태에 반영한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Interpretation(
        List<Update> updates,
        String slotOutcome,
        FollowUp followUp,
        String kind,
        String reply
) {
    public static final String ANSWER = "answer";
    public static final String ABUSIVE = "abusive";
    /** 모델이 거절로 멈춘 답. 여행 답이 아닌 것으로 다룬다. */
    public static final String REFUSED = "refused";
    public static final List<String> KINDS = List.of(ANSWER, ABUSIVE, "off_topic", "nonsense", "injection");

    public static Interpretation refused() {
        return new Interpretation(List.of(), "unclear", null, REFUSED, null);
    }

    /** 여행 조건으로 읽을 답인지. 종류를 빠뜨린 응답은 답으로 본다. */
    public boolean isAnswer() {
        return kind == null || ANSWER.equals(kind);
    }

    public boolean isAbusive() {
        return ABUSIVE.equals(kind);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(String field, String op, String value, String status, String source, String raw,
                         String date, String time) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FollowUp(boolean needed, String reason, String title, String desc, List<String> examples,
                           String placeholder) {}

    public List<Update> safeUpdates() {
        return updates == null ? List.of() : updates;
    }
}
