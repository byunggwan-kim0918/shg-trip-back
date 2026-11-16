package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 구조(day/순서/시간/교통/대안)는 RouteOptimizer가 이미 확정했으므로 Haiku는 story 텍스트만 생성한다.
 */
public record AssemblyItineraryOutput(
    String title,
    List<String> tags,
    List<StoryStep> steps
) {
    public record StoryStep(
        int stepOrder,
        String story
    ) {}

    /** 모델이 키를 {@code "stepOrder 4"}처럼 번호까지 붙여 보낸다. 그대로 레코드로 바꾸면 스텝 하나 때문에 이야기 전체가 빠진다. */
    private static final Pattern NUMBERED_STEP_KEY = Pattern.compile("stepOrder\\D*(\\d{1,4})");

    /** 도구 입력에서 읽을 수 있는 스텝만 담는다. 번호나 이야기를 읽을 수 없는 스텝은 뺀다. */
    public static AssemblyItineraryOutput fromToolInput(JsonNode input) {
        String title = input.path("title").isTextual() ? input.path("title").asText() : null;
        List<String> tags = new ArrayList<>();
        input.path("tags").forEach(t -> {
            if (t.isTextual()) tags.add(t.asText());
        });
        List<StoryStep> steps = new ArrayList<>();
        for (JsonNode node : input.path("steps")) {
            Integer order = stepOrderOf(node);
            JsonNode story = node.path("story");
            if (order != null && story.isTextual() && !story.asText().isBlank()) {
                steps.add(new StoryStep(order, story.asText()));
            }
        }
        return new AssemblyItineraryOutput(title, tags, steps);
    }

    private static Integer stepOrderOf(JsonNode node) {
        JsonNode order = node.path("stepOrder");
        if (order.canConvertToInt()) return order.asInt();
        String text = order.isTextual() ? order.asText().strip() : "";
        if (text.matches("\\d{1,4}")) return Integer.parseInt(text);
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            Matcher m = NUMBERED_STEP_KEY.matcher(field.getKey());
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return null;
    }
}
