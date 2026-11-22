package com.shg.trip.shgtrip.domain.tripchat.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.planning.service.ai.ModelRequestPolicy;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 여행지를 정하지 못한 여행자에게 권할 곳을 고른다. 고를 수 있는 곳은 코드가 정하고, 그중 어느 곳이 맞는지는 Haiku가
 * 여행자가 말한 것 전체를 보고 판단한다.
 */
@Slf4j
@Component
public class DestinationRecommender {

    static final int MAX_PICKS = 3;
    private static final long MAX_TOKENS = 600;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AnthropicClient client;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public DestinationRecommender(AnthropicClient anthropicClient, AnthropicProperties properties,
                                  ObjectMapper objectMapper) {
        this.client = anthropicClient.withOptions(o -> o.maxRetries(0));
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.systemPrompt = load("prompts/trip-chat-recommend.txt");
    }

    /**
     * @param picks 목록에 있는 여행지 이름
     * @param reply 질문 위 한 줄. 모델 답을 쓰지 못했으면 null
     * @param asked 모델을 불렀는지. 부르지 못하고 데이터 많은 순으로 고른 경우 false다
     */
    public record Recommendation(List<String> picks, String reply, boolean asked) {}

    /** @param candidates 추천해도 되는 곳, 데이터가 많은 순 */
    public Recommendation recommend(Map<String, String> traveler, List<String> candidates) {
        List<String> fallback = candidates.stream().limit(MAX_PICKS).toList();
        if (candidates.isEmpty()) return new Recommendation(List.of(), null, false);
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(properties.haiku())
                    .thinking(ModelRequestPolicy.HAIKU_THINKING)
                    .maxTokens(MAX_TOKENS)
                    .system(systemPrompt)
                    .outputConfig(OutputConfig.builder().format(format(candidates)).build())
                    .addUserMessage("<traveler>" + objectMapper.writeValueAsString(traveler) + "</traveler>\n"
                            + "<destinations>" + objectMapper.writeValueAsString(candidates) + "</destinations>")
                    .build();
            Message message = client.messages().create(params, RequestOptions.builder().timeout(TIMEOUT).build());
            StopReason stop = message.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop) || StopReason.MAX_TOKENS.equals(stop)) {
                return new Recommendation(fallback, null, true);
            }
            JsonNode root = objectMapper.readTree(message.content().stream().filter(b -> b.isText())
                    .map(b -> b.asText().text()).collect(Collectors.joining()));
            List<String> picks = new ArrayList<>();
            // 스키마가 목록 밖 이름을 막지만 서버가 다시 거른다.
            root.path("picks").forEach(p -> {
                if (candidates.contains(p.asText()) && !picks.contains(p.asText()) && picks.size() < MAX_PICKS) {
                    picks.add(p.asText());
                }
            });
            String reply = NextStepPolicy.fit(root.path("reply").asText(null), NextStepPolicy.MAX_REPLY);
            log.info("여행지 추천 in={} out={} picks={}", message.usage().inputTokens(), message.usage().outputTokens(),
                    picks);
            return picks.isEmpty() ? new Recommendation(fallback, null, true) : new Recommendation(picks, reply, true);
        } catch (IOException | RuntimeException e) {
            log.warn("여행지 추천 실패, 데이터 많은 순으로 대신함: {}", e.getMessage());
            return new Recommendation(fallback, null, false);
        }
    }

    private static JsonOutputFormat format(List<String> candidates) {
        Map<String, Object> pick = new LinkedHashMap<>();
        pick.put("type", "string");
        pick.put("enum", candidates);
        Map<String, Object> picks = new LinkedHashMap<>();
        picks.put("type", "array");
        picks.put("items", pick);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("picks", picks);
        properties.put("reply", reply);
        JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
        b.putAdditionalProperty("type", JsonValue.from("object"));
        b.putAdditionalProperty("properties", JsonValue.from(properties));
        b.putAdditionalProperty("required", JsonValue.from(List.of("picks", "reply")));
        b.putAdditionalProperty("additionalProperties", JsonValue.from(false));
        return JsonOutputFormat.builder().schema(b.build()).build();
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path + " 로드 실패", e);
        }
    }
}
