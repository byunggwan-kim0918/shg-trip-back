package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.shg.trip.shgtrip.domain.planning.service.DaySelector;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 날짜별 확정에서 하루의 끼니·관광을 모델이 고른다. 날 선택기와 견주는 평가({@code --eval.day-selector})에서만 쓴다.
 * 운영에 붙이려면 호출별 타임아웃과 날짜마다 취소 확인이 먼저 있어야 한다.
 */
@Slf4j
@Service
@Profile("eval")
public class DaySelectionCallGenerator {

    private static final String TOOL_NAME = "choose_day";
    private static final int REPLY_TOKENS = 300;

    /** @param request 사용자가 쓴 요청 문장(보강된 설명). 없으면 null */
    public record TripContext(String destination, List<String> themes, String companion, String request,
                              String pace, String transport) {}

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final String systemPrompt;

    public DaySelectionCallGenerator(AnthropicClient anthropicClient, AnthropicProperties anthropicProperties) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        try {
            this.systemPrompt = new ClassPathResource("prompts/select-day.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 템플릿 로드 실패: prompts/select-day.txt", e);
        }
    }

    /** @param sonnet false면 Haiku로 고른다 */
    public DaySelector selectorFor(TripContext trip, boolean sonnet) {
        return options -> choose(options, trip, sonnet);
    }

    Set<Integer> choose(DaySelector.DayOptions options, TripContext trip, boolean sonnet) {
        if (options.lunch().isEmpty() && options.dinner().isEmpty() && options.sights().isEmpty()) return Set.of();
        long started = System.nanoTime();
        Message message = anthropicClient.messages().create(params(userMessage(options, trip), sonnet));
        Map<?, ?> pick = message.content().stream().filter(ContentBlock::isToolUse).findFirst()
                .map(b -> b.asToolUse()._input().convert(Map.class)).orElse(null);
        log.info("날짜별 선택 호출 day={} 모델={} {}ms inputTokens={} outputTokens={} 캐시읽음/쓰기={}/{}",
                options.day(), sonnet ? "sonnet" : "haiku", (System.nanoTime() - started) / 1_000_000,
                message.usage().inputTokens(), message.usage().outputTokens(),
                message.usage().cacheReadInputTokens().orElse(0L),
                message.usage().cacheCreationInputTokens().orElse(0L));
        if (pick == null) {
            log.warn("날짜별 선택 응답에 도구 호출 없음 day={} stop={}", options.day(), message.stopReason().orElse(null));
            return Set.of();
        }
        return picked(pick);
    }

    // 고르지 않은 칸에 "null"·빈 문자열을 넣는 응답이 있어 숫자로 읽히는 값만 받는다.
    static Set<Integer> picked(Map<?, ?> pick) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String key : List.of("lunch", "dinner", "sights")) {
            Object value = pick.get(key);
            for (Object v : value instanceof List<?> list ? list : java.util.Collections.singletonList(value)) {
                Integer index = v instanceof Number n ? Integer.valueOf(n.intValue()) : parse(v);
                if (index != null) out.add(index);
            }
        }
        return out;
    }

    private static Integer parse(Object v) {
        if (!(v instanceof String text)) return null;
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MessageCreateParams params(String userMessage, boolean sonnet) {
        TextBlockParam system = TextBlockParam.builder().text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build()).build();
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .systemOfTextBlockParams(List.of(system))
                .addUserMessage(userMessage)
                .addTool(tool());
        if (sonnet) {
            return b.model(anthropicProperties.sonnet())
                    .maxTokens(ModelRequestPolicy.sonnetMaxTokens(REPLY_TOKENS, anthropicProperties.maxOutputTokens()))
                    .toolChoice(ModelRequestPolicy.SONNET_TOOL_CHOICE)
                    .outputConfig(ModelRequestPolicy.SONNET_OUTPUT)
                    .build();
        }
        return b.model(anthropicProperties.haiku())
                .thinking(ModelRequestPolicy.HAIKU_THINKING)
                .maxTokens(REPLY_TOKENS)
                .toolToolChoice(TOOL_NAME)
                .build();
    }

    static Tool tool() {
        Map<String, Object> index = Map.of("type", "integer");
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("lunch", JsonValue.from(Map.of("type", "integer",
                        "description", "점심 후보 번호. 점심 후보가 없으면 생략")))
                .putAdditionalProperty("dinner", JsonValue.from(Map.of("type", "integer",
                        "description", "저녁 후보 번호. 저녁 후보가 없으면 생략")))
                .putAdditionalProperty("sights", JsonValue.from(Map.of("type", "array", "items", index,
                        "description", "관광 후보 번호")))
                .build();
        return Tool.builder().name(TOOL_NAME).description("하루의 점심·저녁·관광 고르기")
                .inputSchema(Tool.InputSchema.builder().properties(properties).required(List.of("sights")).build())
                .build();
    }

    static String userMessage(DaySelector.DayOptions o, TripContext trip) {
        StringBuilder sb = new StringBuilder("## 여행 정보\n");
        sb.append("- 여행지: ").append(trip.destination()).append('\n');
        if (trip.themes() != null && !trip.themes().isEmpty()) {
            sb.append("- 테마: ").append(String.join(", ", trip.themes())).append('\n');
        }
        if (trip.companion() != null) sb.append("- 동행: ").append(trip.companion()).append('\n');
        if (trip.request() != null && !trip.request().isBlank()) sb.append("- 요청: ").append(trip.request()).append('\n');
        sb.append("- 페이스: ").append(trip.pace()).append(", 이동: ").append(trip.transport()).append('\n');
        sb.append("\n## ").append(o.day()).append("일차 (전체 ").append(o.dayCount()).append("일")
                .append(o.lastDay() ? ", 여행이 끝나는 날" : "").append(")\n");
        sb.append("- 하루 관광 수: ").append(o.minSights()).append("~").append(o.maxSights()).append("곳\n");
        sb.append("- 기준 장소: ").append(o.base().name()).append('\n');
        List<String> fixed = new ArrayList<>();
        o.fixed().forEach(f -> fixed.add(f.name()));
        sb.append("- 이미 놓인 장소: ").append(fixed.isEmpty() ? "없음" : String.join(", ", fixed)).append('\n');
        if (!o.earlier().isEmpty()) sb.append("- 앞날: ").append(String.join(" / ", o.earlier())).append('\n');
        appendOptions(sb, "점심 후보", o.lunch());
        appendOptions(sb, "저녁 후보", o.dinner());
        appendOptions(sb, "관광 후보", o.sights());
        return sb.toString();
    }

    private static void appendOptions(StringBuilder sb, String title, List<DaySelector.Option> options) {
        if (options.isEmpty()) return;
        sb.append("\n## ").append(title).append(" (번호 | 이름 | 분류 | 기준 장소에서 km | 머무는 분)\n");
        for (DaySelector.Option o : options) {
            sb.append(o.index()).append(" | ").append(o.name()).append(" | ").append(leaf(o.category()));
            if (o.kind() != null) sb.append('(').append(o.kind()).append(')');
            sb.append(" | ").append(o.km() == null ? "?" : String.format("%.1f", o.km()))
                    .append(" | ").append(o.minutes());
            if ("EVENING".equals(o.timing())) sb.append(" | 저녁에 가는 곳");
            if (o.sitelinks() != null && o.sitelinks() >= 10) sb.append(" | 널리 알려짐");
            if (o.recommended()) sb.append(" | 추천");
            if (o.requested()) sb.append(" | 꼭 넣을 곳");
            sb.append('\n');
        }
    }

    private static String leaf(String category) {
        if (category == null) return "";
        int arrow = category.lastIndexOf('>');
        return arrow >= 0 ? category.substring(arrow + 1).trim() : category;
    }
}
