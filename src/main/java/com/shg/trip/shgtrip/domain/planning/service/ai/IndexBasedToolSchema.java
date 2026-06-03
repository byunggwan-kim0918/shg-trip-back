package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * 인덱스 기반 일정 생성용 Tool Use 스키마 빌더.
 * 후보 장소 인덱스(placeIndex) + 대안 인덱스(alternativeIndices) 중심으로
 * 기존 ClaudeAIService의 Tool 스키마 대비 출력 토큰을 대폭 절감한다.
 *
 */
public final class IndexBasedToolSchema {

    private IndexBasedToolSchema() {
        // utility class
    }

    /**
     * 인덱스 기반 generate_itinerary Tool을 빌드한다.
     * 장소 전체 정보(name, address 등) 대신 인덱스 번호만 사용하는 간소화된 스키마.
     */
    public static Tool buildIndexBasedItineraryTool() {
        Map<String, Object> stepSchema = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("stepOrder", Map.of("type", "integer",
                                "description", "전체 일정에서의 순서 (1부터 연속 증가)")),
                        Map.entry("dayNumber", Map.of("type", "integer",
                                "description", "여행 일차 (1부터 시작)")),
                        Map.entry("startTime", Map.of("type", "string",
                                "description", "HH:mm 형식 (예: 09:00)")),
                        Map.entry("endTime", Map.of("type", "string",
                                "description", "HH:mm 형식 (예: 11:00)")),
                        Map.entry("placeIndex", Map.of("type", "integer",
                                "description", "후보 장소 목록의 인덱스 번호")),
                        Map.entry("alternativeIndices", Map.of("type", "array",
                                "items", Map.of("type", "integer"),
                                "minItems", 3, "maxItems", 5,
                                "description", "대안 장소 인덱스 번호 목록 (3~5개)")),
                        Map.entry("transportationMode", Map.of("type", "string",
                                "enum", List.of("WALK", "CAR", "BUS", "TRAIN", "SUBWAY", "TAXI", "BIKE", "FLIGHT"),
                                "description", "이전 장소에서 이 장소까지의 교통수단")),
                        Map.entry("transportationDuration", Map.of("type", "integer",
                                "description", "이동 시간(분)")),
                        Map.entry("transportationDistance", Map.of("type", "number",
                                "description", "이동 거리(km)")),
                        Map.entry("transportationCost", Map.of("type", "number",
                                "description", "이동 비용(원)")),
                        Map.entry("notes", Map.of("type", "string",
                                "description", "이 장소에서의 추천 활동이나 팁")),
                        Map.entry("estimatedCost", Map.of("type", "number",
                                "description", "해당 단계 예상 비용(원) - 입장료, 식비 등 교통비 제외"))
                )),
                Map.entry("required", List.of(
                        "stepOrder", "dayNumber", "startTime", "endTime", "placeIndex", "alternativeIndices"))
        );

        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("title", JsonValue.from(Map.of(
                        "type", "string", "description", "일정 제목")))
                .putAdditionalProperty("destination", JsonValue.from(Map.of(
                        "type", "string", "description", "여행지")))
                .putAdditionalProperty("estimatedCost", JsonValue.from(Map.of(
                        "type", "number", "description", "총 예상 비용(원)")))
                .putAdditionalProperty("tags", JsonValue.from(Map.of(
                        "type", "array", "items", Map.of("type", "string"),
                        "description", "일정을 설명하는 태그 3~5개")))
                .putAdditionalProperty("steps", JsonValue.from(Map.of(
                        "type", "array", "items", stepSchema,
                        "description", "일정 단계 목록 (stepOrder 1부터 연속 증가)")))
                .build();

        Tool.InputSchema inputSchema = Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(properties)
                .required(List.of("title", "destination", "estimatedCost", "tags", "steps"))
                .build();

        return Tool.builder()
                .name("generate_itinerary")
                .description("인덱스 기반 여행 일정을 생성합니다. 후보 장소 목록의 인덱스 번호를 사용하여 일정을 구성합니다.")
                .inputSchema(inputSchema)
                .build();
    }
}
