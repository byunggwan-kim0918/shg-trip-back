package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 도구 하나를 강제로 불러 분류를 받는 배치 요청과 그 응답 해석. */
final class ForcedToolCall {

    private ForcedToolCall() {}

    static Map<String, Object> params(String model, int maxTokens, String systemPrompt, Map<String, Object> tool,
                                      String userMessage) {
        return Map.of(
                "model", model,
                "max_tokens", maxTokens,
                // Haiku 5.5는 thinking이 기본으로 켜져 max_tokens를 잡아먹는다
                "thinking", Map.of("type", "disabled"),
                "system", List.of(Map.of(
                        "type", "text",
                        "text", systemPrompt,
                        "cache_control", Map.of("type", "ephemeral"))),
                "tools", List.of(tool),
                "tool_choice", Map.of("type", "tool", "name", tool.get("name")),
                "messages", List.of(Map.of("role", "user", "content", userMessage)));
    }

    /** 그 도구의 입력. 응답이 max_tokens에서 잘렸거나 도구 호출이 없으면 null이다. */
    static JsonNode input(JsonNode message, String toolName) {
        if (message == null || "max_tokens".equals(message.path("stop_reason").asText())) return null;
        for (JsonNode block : message.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && toolName.equals(block.path("name").asText())) {
                return block.get("input");
            }
        }
        return null;
    }
}
