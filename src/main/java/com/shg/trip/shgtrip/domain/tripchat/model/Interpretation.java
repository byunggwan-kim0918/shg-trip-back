package com.shg.trip.shgtrip.domain.tripchat.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Haiku 해석 결과. 서버가 UpdateSanitizer·NextStepPolicy로 검사한 뒤에만 상태에 반영한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Interpretation(
        List<Update> updates,
        String slotOutcome,
        FollowUp followUp,
        boolean offTopic
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(String field, String op, String value, String status, String source, String raw,
                         String date, String time) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FollowUp(boolean needed, String reason, String lead, String title, String desc,
                           List<String> examples, String placeholder) {}

    public List<Update> safeUpdates() {
        return updates == null ? List.of() : updates;
    }
}
