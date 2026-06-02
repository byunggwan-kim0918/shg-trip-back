package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.SoftEvaluationResult;
import com.shg.trip.shgtrip.domain.planning.dto.ValidationResult;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import com.shg.trip.shgtrip.global.config.PlanningProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Claude LLM 기반 AI 서비스 구현체. prod 프로파일에서만 활성화.
 */
@Slf4j
@Service
public class ClaudeAIService implements AIService {

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final PlanningProperties planningProperties;
    private final ObjectMapper objectMapper;
    private final String enrichInputPromptTemplate;
    private final String generateItineraryPromptTemplate;
    private final String enhanceItineraryPromptTemplate;
    private final String regenerateItineraryPromptTemplate;
    private final String validateSoftPromptTemplate;

    public ClaudeAIService(AnthropicClient anthropicClient,
                           AnthropicProperties anthropicProperties,
                           PlanningProperties planningProperties,
                           ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.planningProperties = planningProperties;
        this.objectMapper = objectMapper;
        this.enrichInputPromptTemplate = loadPromptTemplate("prompts/enrich-input.txt");
        this.generateItineraryPromptTemplate = loadPromptTemplate("prompts/generate-itinerary.txt");
        this.enhanceItineraryPromptTemplate = loadPromptTemplate("prompts/enhance-itinerary.txt");
        this.regenerateItineraryPromptTemplate = loadPromptTemplate("prompts/regenerate-itinerary.txt");
        this.validateSoftPromptTemplate = loadPromptTemplate("prompts/validate-soft.txt");
    }

    @Override
    public EnrichedInput enrichInput(ItineraryGenerateRequest input) {
        String prompt = buildEnrichPrompt(input);

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.haiku())
                    .maxTokens(1024)
                    .addUserMessage(prompt)
                    .build();

            Message message = executeWithRetry(
                    () -> anthropicClient.messages().create(params), 2);

            String enrichedContext = message.content().stream()
                    .filter(block -> block.isText())
                    .map(block -> block.asText().text())
                    .collect(Collectors.joining());

            log.debug("Enriched context for destination '{}': {}", input.destination(), enrichedContext);

            return new EnrichedInput(
                    input.destination(),
                    input.themes(),
                    input.categories(),
                    input.pace() != null ? input.pace() : "normal",
                    input.budget(),
                    input.startDate(),
                    input.endDate(),
                    input.description(),
                    enrichedContext,
                    input.selectedPlaceIds()
            );
        } catch (Exception e) {
            log.error("Failed to enrich input for destination '{}': {}", input.destination(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "입력 보강 중 AI 서비스 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @Override
    public ItineraryData generateItinerary(EnrichedInput enrichedInput, List<Place> selectedPlaces) {
        String prompt = buildGeneratePrompt(enrichedInput, selectedPlaces);
        Tool tool = buildItineraryTool();
        long days = java.time.temporal.ChronoUnit.DAYS.between(enrichedInput.startDate(), enrichedInput.endDate()) + 1;
        int maxTokens = calculateMaxTokens(days);
        log.info("generateItinerary: days={}, maxTokens={}", days, maxTokens);

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.sonnet())
                    .maxTokens(maxTokens)
                    .addUserMessage(prompt)
                    .addTool(tool)
                    .toolToolChoice("generate_itinerary")
                    .build();

            Message message = executeWithRetry(
                    () -> anthropicClient.messages().create(params), 2);

            // 디버깅: AI 응답 raw 내용 + 사용 토큰 확인
            log.info("generateItinerary response: stopReason={}, inputTokens={}, outputTokens={}",
                    message.stopReason(),
                    message.usage().inputTokens(),
                    message.usage().outputTokens());
            message.content().forEach(block -> {
                if (block.isToolUse()) {
                    try {
                        String rawJson = objectMapper.writeValueAsString(block.asToolUse()._input());
                        log.debug("Tool use raw JSON (first 500 chars): {}",
                                rawJson.length() > 500 ? rawJson.substring(0, 500) + "..." : rawJson);
                    } catch (Exception e) {
                        log.debug("Could not serialize tool use input");
                    }
                } else if (block.isText()) {
                    log.debug("Text block: {}", block.asText().text());
                }
            });

            ItineraryData result = message.content().stream()
                    .filter(block -> block.isToolUse())
                    .findFirst()
                    .map(block -> block.asToolUse()._input().convert(ItineraryData.class))
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                            "AI 응답에서 일정 데이터를 추출할 수 없습니다."));

            // max_tokens 도달로 JSON이 잘린 경우 — stopReason이 max_tokens이면 데이터 불완전
            boolean truncated = message.stopReason().isPresent()
                    && String.valueOf(message.stopReason().get()).contains("max_tokens");
            if (truncated) {
                int actualSteps = result.steps() != null ? result.steps().size() : 0;
                log.error("AI output truncated by max_tokens ({}). Generated {} steps for {} days.",
                        maxTokens, actualSteps, days);
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 응답이 토큰 한도로 잘렸습니다. 일정 데이터가 불완전합니다.");
            }

            log.debug("Generated itinerary for '{}': title='{}', steps={}, stopReason={}",
                    enrichedInput.destination(), result.title(),
                    result.steps() != null ? result.steps().size() : 0,
                    message.stopReason());

            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate itinerary for '{}': {}", enrichedInput.destination(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "일정 생성 중 AI 서비스 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @Override
    public ItineraryData enhanceItinerary(ItineraryData itinerary, ValidationResult feedback, EnrichedInput input) {
        String prompt = buildEnhancePrompt(itinerary, feedback, input);
        Tool tool = buildItineraryTool();
        // enhance는 기존 일정 전체를 다시 출력해야 하므로 generate보다 넉넉하게
        long estimatedDays = itinerary.steps() != null && !itinerary.steps().isEmpty()
                ? itinerary.steps().stream().mapToInt(s -> s.dayNumber()).max().orElse(3)
                : 3;
        int maxTokens = Math.min(calculateMaxTokens(estimatedDays) + 4096, anthropicProperties.maxOutputTokens());
        log.info("enhanceItinerary: estimatedDays={}, maxTokens={}", estimatedDays, maxTokens);

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.sonnet())
                    .maxTokens(maxTokens)
                    .addUserMessage(prompt)
                    .addTool(tool)
                    .toolToolChoice("generate_itinerary")
                    .build();

            Message message = executeWithRetry(
                    () -> anthropicClient.messages().create(params), 2);

            log.info("enhanceItinerary response: stopReason={}, inputTokens={}, outputTokens={}",
                    message.stopReason(),
                    message.usage().inputTokens(),
                    message.usage().outputTokens());

            return message.content().stream()
                    .filter(block -> block.isToolUse())
                    .findFirst()
                    .map(block -> block.asToolUse()._input().convert(ItineraryData.class))
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                            "AI 응답에서 보강된 일정 데이터를 추출할 수 없습니다."));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to enhance itinerary: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "일정 보강 중 AI 서비스 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @Override
    public ItineraryData regenerateItinerary(EnrichedInput enrichedInput, String lastFailureReason, List<Place> selectedPlaces) {
        String prompt = buildRegeneratePrompt(enrichedInput, lastFailureReason, selectedPlaces);
        Tool tool = buildItineraryTool();
        long days = java.time.temporal.ChronoUnit.DAYS.between(enrichedInput.startDate(), enrichedInput.endDate()) + 1;

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.sonnet())
                    .maxTokens(calculateMaxTokens(days))
                    .addUserMessage(prompt)
                    .addTool(tool)
                    .toolToolChoice("generate_itinerary")
                    .build();

            Message message = executeWithRetry(
                    () -> anthropicClient.messages().create(params), 2);

            return message.content().stream()
                    .filter(block -> block.isToolUse())
                    .findFirst()
                    .map(block -> block.asToolUse()._input().convert(ItineraryData.class))
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                            "AI 응답에서 재생성된 일정 데이터를 추출할 수 없습니다."));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to regenerate itinerary for '{}': {}", enrichedInput.destination(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "일정 재생성 중 AI 서비스 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * Haiku 4.5로 일정 품질을 평가합니다.
     * 문맥 일관성(30점), 동선 효율성(40점), 정보 완전성(30점) 기준으로 0~100 점수 반환.
     * AI 호출 실패 시 기본값(75점)으로 fallback 처리.
     */
    @Override
    public SoftEvaluationResult evaluateSoftQuality(ItineraryData data, EnrichedInput input) {
        // alternatives는 평가 불필요 — 제거하여 입력 토큰 절감
        String itineraryJson;
        try {
            List<Map<String, Object>> lightSteps = data.steps() == null ? List.of() :
                    data.steps().stream().map(s -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("dayNumber", s.dayNumber());
                        m.put("stepOrder", s.stepOrder());
                        m.put("startTime", s.startTime());
                        m.put("endTime", s.endTime());
                        m.put("place", s.place());
                        m.put("transportationMode", s.transportationMode());
                        m.put("transportationDuration", s.transportationDuration());
                        m.put("estimatedCost", s.estimatedCost());
                        m.put("notes", s.notes());
                        return m;
                    }).toList();

            Map<String, Object> lightData = new java.util.LinkedHashMap<>();
            lightData.put("title", data.title());
            lightData.put("destination", data.destination());
            lightData.put("estimatedCost", data.estimatedCost());
            lightData.put("tags", data.tags());
            lightData.put("steps", lightSteps);
            itineraryJson = objectMapper.writeValueAsString(lightData);
        } catch (Exception e) {
            itineraryJson = data.toString();
        }

        String prompt = validateSoftPromptTemplate
                .replace("{destination}", input.destination())
                .replace("{startDate}", input.startDate().toString())
                .replace("{endDate}", input.endDate().toString())
                .replace("{themes}", String.join(", ", input.themes()))
                .replace("{budget}", input.budget().toPlainString())
                .replace("{itinerary}", itineraryJson);

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.haiku())
                    .maxTokens(512)
                    .addUserMessage(prompt)
                    .build();

            Message message = executeWithRetry(
                    () -> anthropicClient.messages().create(params), 2);

            String responseText = message.content().stream()
                    .filter(block -> block.isText())
                    .map(block -> block.asText().text())
                    .collect(Collectors.joining());

            // JSON 추출: AI가 설명을 앞뒤로 붙이는 경우 대비
            int jsonStart = responseText.indexOf('{');
            int jsonEnd = responseText.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = responseText.substring(jsonStart, jsonEnd + 1);
                Map<?, ?> parsed = objectMapper.readValue(jsonStr, Map.class);

                int score = ((Number) parsed.get("score")).intValue();
                score = Math.max(0, Math.min(100, score));

                @SuppressWarnings("unchecked")
                List<String> issues = parsed.get("issues") instanceof List
                        ? (List<String>) parsed.get("issues")
                        : List.of();

                log.debug("Soft quality score={}, issues={}", score, issues);
                return new SoftEvaluationResult(score, issues);
            }

            log.warn("Soft validation: JSON not found in AI response, using fallback");
        } catch (Exception e) {
            log.warn("Soft quality evaluation failed: {}", e.getMessage());
        }

        // fallback 점수는 softThreshold - 1로 설정 (ItineraryValidationService catch 블록과 일관성 유지)
        int fallbackScore = Math.max(0, planningProperties.validation().softThreshold() - 1);
        return new SoftEvaluationResult(fallbackScore, List.of("AI 품질 평가 실패, 기본값 적용"));
    }

    // ── Private helpers ──

    /**
     * 여행 일수에 따라 maxTokens를 동적으로 계산.
     * step당 place(~150토큰) + alternatives 3~5개(~600토큰) + 교통/메타(~150토큰) ≈ 900토큰
     * 하루 5~7step × 900 = 4,500~6,300토큰 → 안전하게 하루 6,000토큰 + 최상위 메타데이터 버퍼 2048.
     * 최소 16384, 최대는 anthropic.models.max-output-tokens 설정값.
     */
    private int calculateMaxTokens(long days) {
        int limit = anthropicProperties.maxOutputTokens();
        int estimated = (int) (days * 6000) + 2048;
        return Math.max(16384, Math.min(estimated, limit));
    }

    private String buildEnrichPrompt(ItineraryGenerateRequest input) {
        String formattedBudget = String.format("%,.0f", input.budget());
        return enrichInputPromptTemplate
                .replace("{destination}", input.destination())
                .replace("{themes}", String.join(", ", input.themes()))
                .replace("{categories}", String.join(", ", input.categories()))
                .replace("{budget}", formattedBudget)
                .replace("{startDate}", input.startDate().toString())
                .replace("{endDate}", input.endDate().toString())
                .replace("{description}", input.description() != null ? input.description() : "없음");
    }

    private String buildGeneratePrompt(EnrichedInput input, List<Place> selectedPlaces) {
        String selectedPlacesSection = "";
        String manualModeRule = "";

        if (selectedPlaces != null && !selectedPlaces.isEmpty()) {
            StringBuilder sb = new StringBuilder("\n## 사용자 선택 장소 (좌표 참고하여 가까운 장소끼리 같은 날에 배치하세요)\n");
            for (int i = 0; i < selectedPlaces.size(); i++) {
                Place p = selectedPlaces.get(i);
                String lat = p.getLatitude() != null ? p.getLatitude().toPlainString() : "N/A";
                String lng = p.getLongitude() != null ? p.getLongitude().toPlainString() : "N/A";
                sb.append(String.format("%d. %s (%s, %s) - %s [위도: %s, 경도: %s]\n",
                        i + 1, p.getName(), p.getCategory(), p.getRegion(), p.getAddress(), lat, lng));
            }
            selectedPlacesSection = sb.toString();
            manualModeRule = "20. 사용자가 선택한 장소를 반드시 일정에 포함하세요. 좌표가 가까운 장소끼리 같은 날에 묶고, 빈 시간대에는 해당 지역 근처 장소를 추천하세요.";
        }

        return generateItineraryPromptTemplate
                .replace("{destination}", input.destination())
                .replace("{themes}", String.join(", ", input.themes()))
                .replace("{categories}", String.join(", ", input.categories()))
                .replace("{budget}", input.budget().toPlainString())
                .replace("{startDate}", input.startDate().toString())
                .replace("{endDate}", input.endDate().toString())
                .replace("{description}", input.description() != null ? input.description() : "없음")
                .replace("{enrichedContext}", input.enrichedContext() != null ? input.enrichedContext() : "")
                .replace("{selectedPlacesSection}", selectedPlacesSection)
                .replace("{manualModeRule}", manualModeRule)
                .replace("{paceRule}", buildPaceRule(input.pace()));
    }

    private String buildPaceRule(String pace) {
        return switch (pace != null ? pace : "normal") {
            case "tight" -> "하루 일정은 5~7개 단계로 빈틈없이 구성하세요. 이동 시간을 최소화하고 장소 간 간격을 짧게 잡으세요.";
            case "relaxed" -> "하루 일정은 2~3개 단계로 여유롭게 구성하세요. 각 장소에서 충분히 머물 수 있도록 시간을 넉넉히 배분하고, 카페나 휴식 시간을 포함하세요.";
            default -> "하루 일정은 4~5개 단계로 적당한 여유를 두고 구성하세요.";
        };
    }

    private String buildEnhancePrompt(ItineraryData itinerary, ValidationResult feedback, EnrichedInput input) {
        String existingItinerary;
        try {
            // alternatives 제거 — enhance 프롬프트에 불필요하며 입력 토큰 절감
            List<Map<String, Object>> lightSteps = itinerary.steps() == null ? List.of() :
                    itinerary.steps().stream().map(s -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("stepOrder", s.stepOrder());
                        m.put("dayNumber", s.dayNumber());
                        m.put("startTime", s.startTime());
                        m.put("endTime", s.endTime());
                        m.put("place", s.place());
                        m.put("transportationMode", s.transportationMode());
                        m.put("transportationDuration", s.transportationDuration());
                        m.put("transportationDistance", s.transportationDistance());
                        m.put("transportationCost", s.transportationCost());
                        m.put("estimatedCost", s.estimatedCost());
                        m.put("notes", s.notes());
                        return m;
                    }).toList();

            Map<String, Object> lightData = new java.util.LinkedHashMap<>();
            lightData.put("title", itinerary.title());
            lightData.put("destination", itinerary.destination());
            lightData.put("estimatedCost", itinerary.estimatedCost());
            lightData.put("tags", itinerary.tags());
            lightData.put("steps", lightSteps);
            existingItinerary = objectMapper.writeValueAsString(lightData);
        } catch (Exception e) {
            existingItinerary = itinerary.toString();
        }

        return enhanceItineraryPromptTemplate
                .replace("{destination}", input.destination())
                .replace("{themes}", String.join(", ", input.themes()))
                .replace("{categories}", String.join(", ", input.categories()))
                .replace("{budget}", input.budget().toPlainString())
                .replace("{startDate}", input.startDate().toString())
                .replace("{endDate}", input.endDate().toString())
                .replace("{existingItinerary}", existingItinerary)
                .replace("{score}", String.valueOf(feedback.score()))
                .replace("{errors}", feedback.errors() != null ? String.join(", ", feedback.errors()) : "없음")
                .replace("{warnings}", feedback.warnings() != null ? String.join(", ", feedback.warnings()) : "없음")
                .replace("{feedback}", feedback.feedback() != null ? feedback.feedback() : "없음")
                .replace("{paceRule}", buildPaceRule(input.pace()));
    }

    private String buildRegeneratePrompt(EnrichedInput input, String lastFailureReason, List<Place> selectedPlaces) {
        String failureReason = (lastFailureReason != null && !lastFailureReason.isBlank())
                ? lastFailureReason
                : "이전 생성 결과가 3회 보강 후에도 품질 기준을 충족하지 못했습니다.";

        String selectedPlacesSection = "";
        String manualModeRule = "";

        if (selectedPlaces != null && !selectedPlaces.isEmpty()) {
            StringBuilder sb = new StringBuilder("\n## 사용자 선택 장소 (좌표 참고하여 가까운 장소끼리 같은 날에 배치하세요)\n");
            for (int i = 0; i < selectedPlaces.size(); i++) {
                Place p = selectedPlaces.get(i);
                String lat = p.getLatitude() != null ? p.getLatitude().toPlainString() : "N/A";
                String lng = p.getLongitude() != null ? p.getLongitude().toPlainString() : "N/A";
                sb.append(String.format("%d. %s (%s, %s) - %s [위도: %s, 경도: %s]\n",
                        i + 1, p.getName(), p.getCategory(), p.getRegion(), p.getAddress(), lat, lng));
            }
            selectedPlacesSection = sb.toString();
            manualModeRule = "20. 사용자가 선택한 장소를 반드시 일정에 포함하세요. 좌표가 가까운 장소끼리 같은 날에 묶고, 빈 시간대에는 해당 지역 근처 장소를 추천하세요.";
        }

        return regenerateItineraryPromptTemplate
                .replace("{destination}", input.destination())
                .replace("{themes}", String.join(", ", input.themes()))
                .replace("{categories}", String.join(", ", input.categories()))
                .replace("{budget}", input.budget().toPlainString())
                .replace("{startDate}", input.startDate().toString())
                .replace("{endDate}", input.endDate().toString())
                .replace("{description}", input.description() != null ? input.description() : "없음")
                .replace("{enrichedContext}", input.enrichedContext() != null ? input.enrichedContext() : "")
                .replace("{selectedPlacesSection}", selectedPlacesSection)
                .replace("{manualModeRule}", manualModeRule)
                .replace("{failureReason}", failureReason)
                .replace("{paceRule}", buildPaceRule(input.pace()));
    }

    /**
     * Tool Use 스키마 정의: generate_itinerary 도구.
     * ItineraryData 구조에 맞는 JSON Schema를 구성합니다.
     */
    private Tool buildItineraryTool() {
        Map<String, Object> placeSchema = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("name", Map.of("type", "string", "description", "장소 정식 명칭 (Google Maps에서 검색 가능한 공식 이름)")),
                        Map.entry("address", Map.of("type", "string", "description", "장소 전체 주소 (도로명 또는 지번)")),
                        Map.entry("category", Map.of("type", "string", "description", "장소 카테고리 (맛집, 관광, 카페, 숙소, 쇼핑, 액티비티 중 하나)")),
                        Map.entry("region", Map.of("type", "string", "description", "지역명 (예: 강남구, 하라주쿠)")),
                        Map.entry("country", Map.of("type", "string", "description", "국가명 (예: 대한민국, 일본)"))
                )),
                Map.entry("required", List.of("name", "address", "category"))
        );

        Map<String, Object> alternativeSchema = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("name", Map.of("type", "string", "description", "장소 정식 명칭 (Google Maps에서 검색 가능한 공식 이름)")),
                        Map.entry("address", Map.of("type", "string", "description", "장소 전체 주소 (도로명 또는 지번)")),
                        Map.entry("category", Map.of("type", "string", "description", "장소 카테고리 (맛집, 관광, 카페, 숙소, 쇼핑, 액티비티 중 하나)")),
                        Map.entry("region", Map.of("type", "string", "description", "지역명 (예: 강남구, 하라주쿠)")),
                        Map.entry("country", Map.of("type", "string", "description", "국가명 (예: 대한민국, 일본)")),
                        Map.entry("notes", Map.of("type", "string", "description", "이 대안 장소에서의 추천 활동이나 팁")),
                        Map.entry("estimatedCost", Map.of("type", "number", "description", "이 대안 장소의 예상 비용(원) - 입장료, 식비 등 교통비 제외"))
                )),
                Map.entry("required", List.of("name", "address", "category"))
        );

        Map<String, Object> stepSchema = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("stepOrder", Map.of("type", "integer", "description", "전체 일정에서의 순서 (1부터 연속 증가)")),
                        Map.entry("dayNumber", Map.of("type", "integer", "description", "여행 일차 (1부터 시작)")),
                        Map.entry("startTime", Map.of("type", "string", "description", "HH:mm 형식 (예: 09:00)")),
                        Map.entry("endTime", Map.of("type", "string", "description", "HH:mm 형식 (예: 11:00)")),
                        Map.entry("place", placeSchema),
                        Map.entry("alternatives", Map.of("type", "array", "items", alternativeSchema,
                                "minItems", 3, "maxItems", 5,
                                "description", "메인 장소와 동일 카테고리의 대안 장소 3~5개. 각 대안마다 notes(추천 활동/팁)와 estimatedCost(예상 비용)를 반드시 포함하세요.")),
                        Map.entry("transportationMode", Map.of("type", "string",
                                "enum", List.of("WALK", "CAR", "BUS", "TRAIN", "SUBWAY", "TAXI", "BIKE", "FLIGHT"),
                                "description", "이전 장소에서 이 장소까지의 교통수단 (각 날 첫 단계는 생략 가능)")),
                        Map.entry("transportationDuration", Map.of("type", "integer", "description", "이동 시간(분)")),
                        Map.entry("transportationDistance", Map.of("type", "number", "description", "이동 거리(km)")),
                        Map.entry("transportationCost", Map.of("type", "number", "description", "이동 비용(원)")),
                        Map.entry("notes", Map.of("type", "string", "description", "이 장소에서의 추천 활동이나 팁 (예: '한옥마을 산책 후 전통 찻집 방문 추천')")),
                        Map.entry("estimatedCost", Map.of("type", "number", "description", "해당 단계 예상 비용(원) - 입장료, 식비 등 교통비 제외"))
                )),
                Map.entry("required", List.of("stepOrder", "dayNumber", "startTime", "endTime", "place", "alternatives"))
        );

        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("title", JsonValue.from(Map.of("type", "string", "description", "일정 제목")))
                .putAdditionalProperty("destination", JsonValue.from(Map.of("type", "string")))
                .putAdditionalProperty("estimatedCost", JsonValue.from(Map.of("type", "number", "description", "총 예상 비용(원)")))
                .putAdditionalProperty("tags", JsonValue.from(Map.of("type", "array", "items", Map.of("type", "string"),
                        "description", "일정을 설명하는 태그 3~5개 (예: ['맛집탐방', '역사문화', '서울'])")))
                .putAdditionalProperty("steps", JsonValue.from(Map.of("type", "array", "items", stepSchema,
                        "description", "일정 단계 목록 (stepOrder 1부터 연속 증가)")))
                .build();

        Tool.InputSchema inputSchema = Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(properties)
                .required(List.of("title", "destination", "estimatedCost", "tags", "steps"))
                .build();

        return Tool.builder()
                .name("generate_itinerary")
                .description("여행 일정을 생성합니다. 각 단계별 장소, 대안, 교통 정보를 포함한 구조화된 일정을 반환합니다.")
                .inputSchema(inputSchema)
                .build();
    }

    private String loadPromptTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 템플릿 로드 실패: " + path, e);
        }
    }

    /**
     * 재시도 가능한 에러(529 overloaded, 500 등)에 대해 최대 maxRetries회 재시도.
     * Exponential backoff 적용 (1초, 2초).
     */
    private <T> T executeWithRetry(Supplier<T> action, int maxRetries) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (com.anthropic.errors.InternalServerException e) {
                attempt++;
                if (attempt > maxRetries) {
                    throw e;
                }
                long waitMs = 1000L * attempt;
                log.warn("Anthropic API 일시 오류 (시도 {}/{}), {}ms 후 재시도: {}",
                        attempt, maxRetries, waitMs, e.getMessage());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
