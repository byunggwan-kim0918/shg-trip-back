package com.shg.trip.shgtrip.domain.tripchat.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.planning.service.ai.ModelRequestPolicy;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 대화 답 하나를 Haiku로 해석한다. 출력은 JSON 스키마로 강제하고 값 검사는 호출부(UpdateSanitizer·NextStepPolicy)가 한다.
 * 요청 스레드에서 사용자가 기다리는 호출이라 SDK 재시도를 끄고 한 번만 다시 시도한다.
 */
@Slf4j
@Component
public class TripChatInterpreter {

    private static final long MAX_TOKENS = 1200;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 500;

    private static final JsonOutputFormat FORMAT = JsonOutputFormat.builder().schema(schema()).build();

    private final AnthropicClient client;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public TripChatInterpreter(AnthropicClient anthropicClient, AnthropicProperties properties,
                               ObjectMapper objectMapper) {
        this.client = anthropicClient.withOptions(o -> o.maxRetries(0));
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.systemPrompt = load("prompts/trip-chat-turn.txt");
    }

    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long millis) {}

    public record Result(Interpretation interpretation, Usage usage) {}

    public static class InterpretException extends RuntimeException {
        public InterpretException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Result interpret(String context, String answer) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.haiku())
                .thinking(ModelRequestPolicy.HAIKU_THINKING)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder().format(FORMAT).build())
                .addUserMessage(context + "\n<answer>\n" + answer + "\n</answer>")
                .build();
        RequestOptions options = RequestOptions.builder().timeout(TIMEOUT).build();

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.currentTimeMillis();
            try {
                Message message = client.messages().create(params, options);
                Interpretation interpretation = parse(message);
                Usage usage = new Usage(message.usage().inputTokens(), message.usage().outputTokens(),
                        message.usage().cacheReadInputTokens().orElse(0L), System.currentTimeMillis() - started);
                return new Result(interpretation, usage);
            } catch (RuntimeException e) {
                last = e;
                log.warn("대화 답 해석 실패 (시도 {}/{}): {}", attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) sleep(RETRY_BACKOFF_MS * attempt);
            }
        }
        throw new InterpretException("대화 답 해석 실패", last);
    }

    Interpretation parse(Message message) {
        StopReason stop = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop) || StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException("응답 중단: " + stop);
        }
        String json = message.content().stream()
                .filter(b -> b.isText())
                .map(b -> b.asText().text())
                .collect(Collectors.joining());
        return parseJson(json);
    }

    Interpretation parseJson(String json) {
        try {
            Interpretation it = objectMapper.readValue(json, Interpretation.class);
            if (it == null) throw new IllegalStateException("빈 응답");
            return it;
        } catch (IOException e) {
            throw new IllegalStateException("응답 JSON 파싱 실패", e);
        }
    }

    /**
     * 구조화 출력 스키마. 필드 이름은 DraftField에서 만들어 서버 모델과 어긋나지 않는다.
     * 구조화 출력이 지원하지 않는 길이·개수 키워드는 쓰지 않고 서버가 다시 검사한다.
     */
    static JsonOutputFormat.Schema schema() {
        Map<String, Object> update = object(Map.of(
                "field", enumOf(Arrays.stream(DraftField.values()).map(Enum::name).toList()),
                "op", enumOf(List.of("set", "add", "remove", "clear")),
                "value", type("string"),
                "status", enumOf(List.of("known", "undecided", "any", "delegated", "none")),
                "source", enumOf(List.of("user", "tentative", "inferred")),
                "raw", type("string"),
                "date", type("string"),
                "time", type("string")));
        Map<String, Object> followUp = object(Map.of(
                "needed", type("boolean"),
                "reason", enumOf(List.of("none", "tentative", "vague", "booking_time_missing", "conflict")),
                "lead", type("string"),
                "title", type("string"),
                "desc", type("string"),
                "examples", array(type("string")),
                "placeholder", type("string")));
        Map<String, Object> root = object(Map.of(
                "updates", array(update),
                "slotOutcome", enumOf(List.of("answered", "tentative", "undecided", "any", "delegated", "none", "unclear")),
                "followUp", followUp,
                "offTopic", type("boolean")));

        JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
        root.forEach((k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)));
        return b.build();
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> sorted = new LinkedHashMap<>();
        properties.keySet().stream().sorted().forEach(k -> sorted.put(k, properties.get(k)));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", sorted);
        o.put("required", List.copyOf(sorted.keySet()));
        o.put("additionalProperties", false);
        return o;
    }

    private static Map<String, Object> enumOf(List<String> values) {
        Map<String, Object> m = type("string");
        m.put("enum", values);
        return m;
    }

    private static Map<String, Object> type(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> m = type("array");
        m.put("items", items);
        return m;
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path + " 로드 실패", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
