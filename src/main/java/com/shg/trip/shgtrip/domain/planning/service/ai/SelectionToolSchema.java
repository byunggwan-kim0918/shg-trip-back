package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * Call 1 (Sonnet): 날짜별 장소 선택 Tool Use 스키마 빌더.
 */
public final class SelectionToolSchema {

    private SelectionToolSchema() {}

    /**
     * 날짜별 장소 선택 Tool을 빌드한다.
     */
    public static Tool buildSelectionTool() {
        Map<String, Object> dayPlanSchema = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("additionalProperties", false),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("dayNumber", Map.of("type", "integer",
                                "description", "여행 일차 (1부터)")),
                        Map.entry("arrivalHubIndex", Map.of("type", "integer",
                                "description", "도착 허브 인덱스 (첫날만, nullable)")),
                        Map.entry("placeIndices", Map.of("type", "array",
                                "items", Map.of("type", "integer"),
                                "description", "방문 장소 인덱스 (순서대로)")),
                        Map.entry("accommodationIndex", Map.of("type", "integer",
                                "description", "숙소 인덱스 (마지막날은 null)")),
                        Map.entry("departureHubIndex", Map.of("type", "integer",
                                "description", "출발 허브 인덱스 (마지막날만, nullable)"))
                )),
                Map.entry("required", List.of("dayNumber", "placeIndices"))
        );

        Map<String, Object> pairSchema = Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "minItems", 2,
                "maxItems", 2,
                "description", "같은 날 + 인접 배치가 필요한 두 인덱스 [indexA, indexB]");

        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("concept", JsonValue.from(Map.of(
                        "type", "string",
                        "description", "이 여행 전체를 관통하는 컨셉을 한 문장으로 정의. days/pairs를 채우기 전에 가장 먼저 결정해야 함 — 이후 장소 선택은 이 concept에 부합하는 것만 골라야 함")))
                .putAdditionalProperty("days", JsonValue.from(Map.of(
                        "type", "array",
                        "items", dayPlanSchema,
                        "description", "날짜별 선택 (순서대로). concept에 부합하는 장소만 배치하고, 지역별로 뭉쳐서 동선이 튀지 않게 구성")))
                .putAdditionalProperty("pairs", JsonValue.from(Map.of(
                        "type", "array",
                        "items", pairSchema,
                        "description", "must_pair_with 관계 (예: 전망 명소+근처 카페). 백엔드가 같은 날·인접 배치를 보장하려 시도함(다만 페이스 상한 등 하드 제약이 우선될 수 있음)")))
                .putAdditionalProperty("spareIndices", JsonValue.from(Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "description", "대안 풀 인덱스")))
                .putAdditionalProperty("highlightIndices", JsonValue.from(Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "description", "이번 여행의 서사적 절정/핵심 경험 인덱스(day당 0~1개 권장). 백엔드가 day 내 순서를 정할 때 너무 일찍(그날 첫 스텝) 배치되지 않도록 가중치를 둠")))
                .putAdditionalProperty("restIndices", JsonValue.from(Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "description", "휴식/여유 성격의 인덱스(카페, 산책 등). 백엔드가 highlight 직후에 배치되도록 가중치를 둠(절정 다음 회복 비트)")))
                .build();

        Tool.InputSchema inputSchema = Tool.InputSchema.builder()
                .properties(properties)
                .required(List.of("concept", "days", "spareIndices"))
                .build();

        return Tool.builder()
                .name("select_places")
                .description("날짜별 방문 장소 선택 (시간/교통/비용 제외)")
                .inputSchema(inputSchema)
                .build();
    }
}
