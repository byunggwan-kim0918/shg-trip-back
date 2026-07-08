package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * Call 2 (Haiku): 확정된 일정 위에 concept을 반영한 story만 생성하는 Tool Use 스키마 빌더.
 * day·순서·시간·교통·대안은 백엔드가 이미 확정했으므로 이 스키마에는 구조 필드가 없다.
 */
public final class AssemblyToolSchema {

    private AssemblyToolSchema() {}

    public static Tool buildAssemblyTool() {
        Map<String, Object> stepSchema = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("additionalProperties", false),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("stepOrder", Map.of("type", "integer",
                                "description", "확정된 stepOrder (입력에 주어진 값과 정확히 일치해야 함)")),
                        Map.entry("story", Map.of("type", "string",
                                "description", "이 장소에 대한, concept을 관통하는 가이드북 문장 (순서/시간 변경 절대 불가)"))
                )),
                Map.entry("required", List.of("stepOrder", "story"))
        );

        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("title", JsonValue.from(Map.of(
                        "type", "string", "description", "concept을 반영한 일정 제목")))
                .putAdditionalProperty("tags", JsonValue.from(Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "여행 테마 태그")))
                .putAdditionalProperty("steps", JsonValue.from(Map.of(
                        "type", "array",
                        "items", stepSchema,
                        "description", "stepOrder별 story 텍스트 목록")))
                .build();

        Tool.InputSchema inputSchema = Tool.InputSchema.builder()
                .properties(properties)
                .required(List.of("title", "tags", "steps"))
                .build();

        return Tool.builder()
                .name("assemble_itinerary")
                .description("확정된 일정 위에 concept을 반영한 story 텍스트만 생성 (구조 변경 불가)")
                .inputSchema(inputSchema)
                .build();
    }
}
