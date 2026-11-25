package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.shg.trip.shgtrip.domain.planning.dto.DraftEdit;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Sonnet이 장소 이름으로 일정 초안을 쓴다. 시각·이동·숙소 선택은 코드가 한다. */
@Slf4j
@Service
public class DraftCallGenerator {

    private static final String TOOL_NAME = "submit_draft";
    /** 하루 초안 출력 토큰. 항목 7개 안팎과 예비 4개, 이유 문장에 여유를 둔다. 빠듯하면 긴 여행의 뒷날이 잘린다. */
    private static final int TOKENS_PER_DAY = 1200;
    private static final int BASE_TOKENS = 500;

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final String systemPrompt;

    public DraftCallGenerator(AnthropicClient anthropicClient, AnthropicProperties anthropicProperties) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.systemPrompt = load("prompts/draft-itinerary.txt");
    }

    /** 결과와 사용 토큰. */
    public record Drafted(DraftPlan plan, long inputTokens, long outputTokens) {
    }

    /** 수정 목록과 사용 토큰. */
    public record Edited(List<DraftEdit> edits, long inputTokens, long outputTokens) {
    }

    private static final String EDITS_TOOL = "submit_edits";
    private static final int EDIT_TOKENS = 2500;
    private static final String EDIT_INSTRUCTION = """
            위 일정은 코드가 영업시간·이동·끼니 시각을 맞춰 다 지은 것이다. 아래 확인된 문제가 하루의 취지를 깨뜨린 곳만 \
            submit_edits로 고친다. 문제가 없는 날은 건드리지 않는다.
            - 사용자가 요구한 장소·음식·시각은 빼거나 다른 날로 옮기지 않는다. 빠졌으면 넣는다.
            - 네가 추천한 곳은 같은 경험의 더 맞는 곳으로 바꿔도 된다(replace).
            - target은 일정에 적힌 장소 이름을 그대로 옮겨 쓴다.
            - 빈 시간은 그 권역의 장소를 넣어 채운다(add, target은 바로 앞 장소). 여유롭게 여행이면 빈 시간을 남겨도 된다.
            - 끼니를 바꾸거나 넣을 때 name은 음식 종류, place_named는 false다. 코드가 그 음식을 내는 식당을 찾는다.
            - 관광지·야경 name은 지도에서 검색되는 정식 이름 하나다.
            - 다른 날이 더 맞는 곳은 옮긴다(move).
            - 숙소가 동선에서 멀거나 맞지 않으면 replace로 바꾼다. target은 숙소 이름, name은 새 숙소 이름이나 묵을 동네다.
            - replace·add에는 kind를 꼭 쓴다.
            시각은 코드가 다시 맞추니 적지 않는다. 고칠 곳은 중요한 것부터 최대 12개.""";

    /**
     * 다 지은 일정과 문제 목록을 보고 고칠 곳만 낸다. 초안을 다시 쓰게 하면 코드가 일정을 처음부터 다시 지으며 채우기가
     * 모델이 뺀 장소를 되살린다.
     */
    public Edited edits(String originalPrompt, String timeline, List<String> problems) {
        String message = originalPrompt + "\n[지은 일정]\n" + timeline
                + "\n[확인된 문제]\n- " + String.join("\n- ", problems)
                + "\n\n" + EDIT_INSTRUCTION;
        TextBlockParam system = TextBlockParam.builder().text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build()).build();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(anthropicProperties.sonnet())
                .maxTokens(ModelRequestPolicy.sonnetMaxTokens(EDIT_TOKENS, anthropicProperties.maxOutputTokens()))
                .systemOfTextBlockParams(List.of(system))
                .addUserMessage(message)
                .addTool(editTool())
                .toolChoice(ModelRequestPolicy.SONNET_TOOL_CHOICE)
                .outputConfig(ModelRequestPolicy.SONNET_OUTPUT)
                .build();
        Message reply = anthropicClient.messages().create(params);
        List<DraftEdit> edits = parseEdits(reply);
        if (edits == null) throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "일정 수정 목록을 받지 못했습니다.");
        long cacheRead = reply.usage().cacheReadInputTokens().orElse(0L);
        log.info("수정 목록 완료: {}건, inputTokens={}, outputTokens={}, 캐시읽음={}", edits.size(),
                reply.usage().inputTokens(), reply.usage().outputTokens(), cacheRead);
        return new Edited(edits, reply.usage().inputTokens(), reply.usage().outputTokens());
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record EditList(List<DraftEdit> edits) {
    }

    private static List<DraftEdit> parseEdits(Message reply) {
        try {
            com.fasterxml.jackson.databind.JsonNode input = reply.content().stream()
                    .filter(ContentBlock::isToolUse).findFirst()
                    .map(b -> b.asToolUse()._input().convert(com.fasterxml.jackson.databind.JsonNode.class))
                    .orElse(null);
            if (!(input instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) return null;
            com.fasterxml.jackson.databind.JsonNode v = obj.get("edits");
            if (v != null && v.isTextual()) obj.set("edits", JSON.readTree(v.asText()));
            return JSON.treeToValue(obj, EditList.class).edits();
        } catch (Exception e) {
            log.warn("수정 목록 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    static Tool editTool() {
        Map<String, Object> edit = Map.of(
                "type", "object",
                "properties", Map.of(
                        "op", Map.of("type", "string", "enum", List.of("remove", "replace", "add", "move")),
                        "day", Map.of("type", "integer"),
                        "target", Map.of("type", List.of("string", "null"),
                                "description", "고칠 장소. add면 새 장소를 넣을 바로 앞 장소"),
                        "name", Map.of("type", List.of("string", "null"),
                                "description", "새 장소 이름, 또는 음식 종류·카페 성격"),
                        "kind", Map.of("type", List.of("string", "null"),
                                "enum", java.util.Arrays.asList("sight", "experience", "night", "market", "shop",
                                        "cafe", "meal", null)),
                        "slot", Map.of("type", List.of("string", "null"),
                                "enum", java.util.Arrays.asList("morning", "lunch", "afternoon", "dinner", "evening",
                                        null)),
                        "to_day", Map.of("type", List.of("integer", "null")),
                        "place_named", Map.of("type", "boolean"),
                        "reason", Map.of("type", "string")),
                "required", List.of("op", "day", "kind", "reason"));
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("edits", JsonValue.from(Map.of("type", "array", "items", edit)))
                .build();
        return Tool.builder().name(EDITS_TOOL).description("다 지은 일정의 고칠 곳")
                .inputSchema(Tool.InputSchema.builder().properties(properties).required(List.of("edits")).build())
                .build();
    }

    /** 형식이 맞지 않거나({@link #wellFormed}) 도구 호출이 없으면 한 번 다시 부른다. */
    public Drafted draft(String userMessage, int days) {
        long maxTokens = ModelRequestPolicy.sonnetMaxTokens((long) days * TOKENS_PER_DAY + BASE_TOKENS,
                anthropicProperties.maxOutputTokens());
        for (int attempt = 1; attempt <= 2; attempt++) {
            Message message = call(userMessage, maxTokens);
            DraftPlan plan = withinDays(withoutMalformedItems(parse(message)), days);
            if (wellFormed(plan, days)) {
                long cacheRead = message.usage().cacheReadInputTokens().orElse(0L);
                log.info("초안 완료: days={}, inputTokens={}, outputTokens={}, 캐시읽음={}", days,
                        message.usage().inputTokens(), message.usage().outputTokens(), cacheRead);
                return new Drafted(plan, message.usage().inputTokens(), message.usage().outputTokens());
            }
            log.warn("초안 형식 불일치(시도 {}): 기대 {}일, 받은 날짜 {}, 요구 {}개", attempt, days,
                    plan == null || plan.days() == null ? null
                            : plan.days().stream().map(d -> d == null ? null : d.day()).toList(),
                    plan == null || plan.wants() == null ? 0 : plan.wants().size());
        }
        throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "일정 초안을 만들지 못했습니다.");
    }

    private static final java.util.Set<String> SLOTS = java.util.Set.of("morning", "lunch", "afternoon", "dinner",
            "evening");
    private static final java.util.Set<String> ITEM_KINDS = java.util.Set.of("sight", "experience", "night", "market",
            "shop", "cafe", "meal");
    private static final java.util.Set<String> WANT_KINDS = java.util.Set.of("place", "food", "activity", "city");

    /**
     * 날짜 번호가 어긋나거나 요구에 id·말이 없으면 그날이나 요구가 일정에서 조용히 빠진다. 문장으로만 말한 요구는 다른
     * 곳에서 되살릴 수 없어 다시 부른다.
     */
    static boolean wellFormed(DraftPlan plan, int days) {
        if (plan == null || plan.days() == null || plan.days().size() != days) return false;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (DraftPlan.Day d : plan.days()) {
            if (d == null || d.day() < 1 || d.day() > days || !seen.add(d.day())) return false;
        }
        for (DraftPlan.Want w : plan.wants() == null ? List.<DraftPlan.Want>of() : plan.wants()) {
            if (w == null || blank(w.id()) || blank(w.text()) || !oneOf(WANT_KINDS, w.kind())) return false;
        }
        return true;
    }

    /**
     * 자동 도구 호출은 스키마의 필수 칸을 강제하지 않아 항목의 시간대·종류가 비어 올 수 있다. 그런 항목은 놓을 칸이 없어
     * 빼고 초안은 쓴다. 요구에 딸린 항목이 빠지면 뼈대 단계가 그 요구를 다시 넣는다.
     */
    static DraftPlan withoutMalformedItems(DraftPlan plan) {
        if (plan == null || plan.days() == null) return plan;
        int dropped = 0;
        List<DraftPlan.Day> days = new java.util.ArrayList<>();
        for (DraftPlan.Day d : plan.days()) {
            if (d == null) {
                days.add(null);
                continue;
            }
            List<DraftPlan.Item> items = wellFormedItems(d.items());
            List<DraftPlan.Item> spares = wellFormedItems(d.spares());
            dropped += size(d.items()) - items.size() + size(d.spares()) - spares.size();
            days.add(new DraftPlan.Day(d.day(), d.area(), d.lodgingArea(), items, spares));
        }
        if (dropped > 0) log.warn("초안 항목 {}개를 형식 불일치로 뺌", dropped);
        return new DraftPlan(plan.concept(), plan.wants(), days);
    }

    /** 여행 날 수보다 많은 날을 쓴 초안은 남는 날만 버린다. 그 날에만 있던 요구는 뼈대 단계가 다시 넣는다. */
    static DraftPlan withinDays(DraftPlan plan, int days) {
        if (plan == null || plan.days() == null || plan.days().size() <= days) return plan;
        List<DraftPlan.Day> kept = plan.days().stream().filter(d -> d == null || d.day() <= days).toList();
        log.warn("초안이 {}일을 넘겨 {}일치를 냄, 남는 날을 뺌", days, plan.days().size());
        return new DraftPlan(plan.concept(), plan.wants(), kept);
    }

    private static List<DraftPlan.Item> wellFormedItems(List<DraftPlan.Item> items) {
        return (items == null ? List.<DraftPlan.Item>of() : items).stream()
                .filter(i -> i != null && !blank(i.name()) && oneOf(SLOTS, i.slot()) && oneOf(ITEM_KINDS, i.kind()))
                .toList();
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /** 불변 Set은 null을 물으면 예외를 던진다. */
    private static boolean oneOf(java.util.Set<String> allowed, String value) {
        return value != null && allowed.contains(value);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private Message call(String userMessage, long maxTokens) {
        TextBlockParam system = TextBlockParam.builder().text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build()).build();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(anthropicProperties.sonnet())
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(system))
                .addUserMessage(userMessage)
                .addTool(tool())
                .toolChoice(ModelRequestPolicy.SONNET_TOOL_CHOICE)
                .outputConfig(ModelRequestPolicy.SONNET_OUTPUT)
                .build();
        return anthropicClient.messages().create(params);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 자동 도구 호출에서 배열 필드를 JSON 문자열로 감싸 보내는 경우가 있어 풀어서 읽는다. 수정 목록도 같다. */
    private static DraftPlan parse(Message message) {
        try {
            com.fasterxml.jackson.databind.JsonNode input = message.content().stream()
                    .filter(ContentBlock::isToolUse).findFirst()
                    .map(b -> b.asToolUse()._input().convert(com.fasterxml.jackson.databind.JsonNode.class))
                    .orElse(null);
            if (!(input instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) return null;
            for (String field : List.of("wants", "days")) {
                com.fasterxml.jackson.databind.JsonNode v = obj.get(field);
                if (v != null && v.isTextual()) obj.set(field, JSON.readTree(v.asText()));
            }
            return JSON.treeToValue(obj, DraftPlan.class);
        } catch (Exception e) {
            log.warn("초안 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    static Tool tool() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> strOrNull = Map.of("type", List.of("string", "null"));
        Map<String, Object> localNames = Map.of("type", "array", "items", str, "maxItems", 3,
                "description", "해외 장소의 현지어 표기 0~3개(현지 지도·간판의 정식 이름). 국내이거나 확실하지 않으면 빈 목록");
        Map<String, Object> want = Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", str,
                        "kind", Map.of("type", "string", "enum", List.of("place", "food", "activity", "city")),
                        "text", str,
                        "place", strOrNull,
                        "food", strOrNull,
                        "time", Map.of("type", List.of("string", "null"), "enum",
                                java.util.Arrays.asList("sunrise", "morning", "afternoon", "evening", "night", null)),
                        "satisfy", Map.of("type", "array", "items", str),
                        "local_names", localNames),
                "required", List.of("id", "kind", "text", "satisfy"));
        Map<String, Object> item = Map.of(
                "type", "object",
                "properties", Map.of(
                        "slot", Map.of("type", "string",
                                "enum", List.of("morning", "lunch", "afternoon", "dinner", "evening")),
                        "kind", Map.of("type", "string",
                                "enum", List.of("sight", "experience", "night", "market", "shop", "cafe", "meal")),
                        "name", Map.of("type", "string", "description", "정식 장소 이름, 또는 음식 종류·카페 성격"),
                        "area", Map.of("type", "string", "description", "동네"),
                        "place_named", Map.of("type", "boolean"),
                        "want", Map.of("type", List.of("string", "null"), "description", "채우는 요구 id. 추천이면 null"),
                        "experience", Map.of("type", List.of("string", "null"), "description", "추천한 경험"),
                        "reason", Map.of("type", List.of("string", "null"),
                                "description", "여행자에게 들려줄 이유 한 문장. 장소 이름·시각 없이"),
                        "local_names", localNames),
                "required", List.of("slot", "kind", "name", "area", "place_named"));
        Map<String, Object> day = Map.of(
                "type", "object",
                "properties", Map.of(
                        "day", Map.of("type", "integer"),
                        "area", Map.of("type", "string"),
                        "lodging_area", Map.of("type", List.of("string", "null"),
                                "description", "그날 밤 숙소 권역. 마지막 날은 null"),
                        "items", Map.of("type", "array", "items", item),
                        "spares", Map.of("type", "array", "items", item,
                                "description", "그날 권역에서 같은 경험을 할 수 있는 다른 관광지 3~4곳")),
                "required", List.of("day", "area", "items", "spares"));
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("concept", JsonValue.from(Map.of("type", "string",
                        "description", "여행 전체를 한 문장으로")))
                .putAdditionalProperty("wants", JsonValue.from(Map.of("type", "array", "items", want,
                        "description", "사용자가 직접 요구한 것. 없으면 빈 목록")))
                .putAdditionalProperty("days", JsonValue.from(Map.of("type", "array", "items", day)))
                .build();
        return Tool.builder().name(TOOL_NAME).description("날짜별 일정 초안 제출")
                .inputSchema(Tool.InputSchema.builder().properties(properties)
                        .required(List.of("concept", "wants", "days")).build())
                .build();
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 템플릿 로드 실패: " + path, e);
        }
    }
}
