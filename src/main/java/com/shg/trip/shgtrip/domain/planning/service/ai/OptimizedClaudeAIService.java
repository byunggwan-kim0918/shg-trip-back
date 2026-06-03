package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichmentResult;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 최적화된 Claude AI 서비스.
 * enrichInput: Haiku 1회 호출로 입력 정규화 + 현실성 검증 + 검색 힌트 생성을 수행한다.
 * 실패 시 기존 enrichInput 방식으로 fallback.
 */
@Slf4j
@Service
public class OptimizedClaudeAIService {

    private static final int MAX_RETRIES = 2;

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final ObjectMapper objectMapper;
    private final ClaudeAIService fallbackService;
    private final String enrichPromptTemplate;

    public OptimizedClaudeAIService(AnthropicClient anthropicClient,
                                     AnthropicProperties anthropicProperties,
                                     ObjectMapper objectMapper,
                                     ClaudeAIService fallbackService) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.objectMapper = objectMapper;
        this.fallbackService = fallbackService;
        this.enrichPromptTemplate = loadPromptTemplateSafe("prompts/enrich-input.txt");
    }

    /**
     * Haiku 1회 호출로 입력 정규화, 현실성 검증, 검색 힌트를 생성한다.
     * 비현실적 입력 시 에러 코드 + 수정 제안을 반환하여 파이프라인을 중단한다.
     * API 실패 시 최대 3회 재시도 후 기존 enrichInput fallback.
     */
    public EnrichmentResult enrichInput(ItineraryGenerateRequest input) {
        String prompt = buildEnrichPrompt(input);

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.haiku())
                    .maxTokens(1024)
                    .addUserMessage(prompt)
                    .build();

            Message message = executeWithRetry(
                    () -> anthropicClient.messages().create(params), MAX_RETRIES);

            String responseText = message.content().stream()
                    .filter(block -> block.isText())
                    .map(block -> block.asText().text())
                    .collect(Collectors.joining());

            return parseEnrichResponse(responseText, input);

        } catch (Exception e) {
            log.warn("enrichInput 실패, 기존 enrichInput으로 fallback: {}", e.getMessage());
            return fallbackToLegacyEnrich(input);
        }
    }

    /**
     * Haiku JSON 응답을 파싱하여 EnrichmentResult를 생성한다.
     * package-private for testing.
     */
    @SuppressWarnings("unchecked")
    EnrichmentResult parseEnrichResponse(String responseText, ItineraryGenerateRequest input) {
        try {
            // JSON 추출: AI가 설명을 앞뒤로 붙이는 경우 대비
            int jsonStart = responseText.indexOf('{');
            int jsonEnd = responseText.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn("enrichInput: JSON not found in response, using fallback");
                return fallbackToLegacyEnrich(input);
            }

            String jsonStr = responseText.substring(jsonStart, jsonEnd + 1);
            Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);

            boolean valid = Boolean.TRUE.equals(parsed.get("valid"));

            if (!valid) {
                String errorCode = (String) parsed.get("errorCode");
                String errorMessage = (String) parsed.get("errorMessage");
                log.info("enrichInput: 비현실적 입력 거부 - code={}, message={}", errorCode, errorMessage);
                return EnrichmentResult.error(
                        errorCode != null ? errorCode : "INVALID_INPUT",
                        errorMessage != null ? errorMessage : "입력을 수정해주세요."
                );
            }

            // 성공: VectorEnrichedInput 구성
            String normalizedDestination = getStringOrDefault(parsed, "normalizedDestination", input.destination());
            String country = getStringOrDefault(parsed, "country", "");
            List<String> regions = getListOrDefault(parsed, "regions");
            List<String> searchTags = getListOrDefault(parsed, "searchTags");
            String budgetRange = getStringOrDefault(parsed, "budgetRange", "MEDIUM");
            String seasonContext = getStringOrDefault(parsed, "seasonContext", "");
            String enrichedContext = getStringOrDefault(parsed, "enrichedContext", "");

            Map<String, List<String>> regionAllocation = null;
            Object regionAllocObj = parsed.get("regionAllocation");
            if (regionAllocObj instanceof Map) {
                regionAllocation = (Map<String, List<String>>) regionAllocObj;
            }

            VectorEnrichedInput vectorEnrichedInput = new VectorEnrichedInput(
                    input.destination(),
                    input.themes(),
                    input.categories(),
                    input.pace() != null ? input.pace() : "normal",
                    input.budget(),
                    input.startDate(),
                    input.endDate(),
                    input.description(),
                    input.selectedPlaceIds(),
                    normalizedDestination,
                    country,
                    regions,
                    searchTags,
                    regionAllocation,
                    budgetRange,
                    seasonContext,
                    enrichedContext
            );

            log.debug("enrichInput 성공: destination='{}' → normalized='{}', country='{}', regions={}",
                    input.destination(), normalizedDestination, country, regions);
            return EnrichmentResult.success(vectorEnrichedInput);

        } catch (Exception e) {
            log.warn("enrichInput JSON 파싱 실패: {}", e.getMessage());
            return fallbackToLegacyEnrich(input);
        }
    }

    /**
     * 기존 enrichInput 방식으로 fallback하여 EnrichmentResult를 구성한다.
     */
    private EnrichmentResult fallbackToLegacyEnrich(ItineraryGenerateRequest input) {
        try {
            var legacyResult = fallbackService.enrichInput(input);

            VectorEnrichedInput vectorEnrichedInput = new VectorEnrichedInput(
                    legacyResult.destination(),
                    legacyResult.themes(),
                    legacyResult.categories(),
                    legacyResult.pace(),
                    legacyResult.budget(),
                    legacyResult.startDate(),
                    legacyResult.endDate(),
                    legacyResult.description(),
                    legacyResult.selectedPlaceIds(),
                    legacyResult.destination(),   // normalizedDestination = 원본
                    "",                            // country 미확인
                    List.of(),                     // regions 미확인
                    List.of(),                     // searchTags 미생성
                    null,                          // regionAllocation 미생성
                    estimateBudgetRange(legacyResult.budget(), legacyResult.startDate(), legacyResult.endDate()),
                    "",                            // seasonContext 미생성
                    legacyResult.enrichedContext()
            );

            return EnrichmentResult.success(vectorEnrichedInput);
        } catch (Exception fallbackEx) {
            log.error("enrichInput fallback도 실패: {}", fallbackEx.getMessage());
            throw fallbackEx;
        }
    }

    // ── Private helpers ──

    private String buildEnrichPrompt(ItineraryGenerateRequest input) {
        String formattedBudget = String.format("%,.0f", input.budget());
        return enrichPromptTemplate
                .replace("{destination}", input.destination())
                .replace("{themes}", String.join(", ", input.themes()))
                .replace("{categories}", String.join(", ", input.categories()))
                .replace("{budget}", formattedBudget)
                .replace("{startDate}", input.startDate().toString())
                .replace("{endDate}", input.endDate().toString())
                .replace("{pace}", input.pace() != null ? input.pace() : "normal")
                .replace("{description}", input.description() != null ? input.description() : "없음");
    }

    /**
     * 프롬프트 템플릿을 클래스패스에서 로드한다.
     * 파일이 없으면 인라인 기본 프롬프트를 반환한다.
     */
    private String loadPromptTemplateSafe(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("프롬프트 템플릿 파일 미발견, 인라인 프롬프트 사용: {}", path);
            return getInlineEnrichPrompt();
        }
    }

    /**
     * prompts/enrich-input.txt가 없을 때 사용할 인라인 기본 프롬프트.
     */
    private String getInlineEnrichPrompt() {
        return """
                당신은 여행 입력 분석 전문가입니다. 사용자 입력을 정규화하고 검증합니다.

                ## 입력
                - 여행지: {destination}
                - 테마: {themes}
                - 카테고리: {categories}
                - 예산: {budget}원
                - 기간: {startDate} ~ {endDate}
                - 페이스: {pace}
                - 설명: {description}

                ## 작업
                1. 여행지 정규화:
                   - normalizedDestination: 오타/약어/외래어만 교정. 하위 구역은 그대로 유지. ("해운대" → "해운대", "홍대" → "홍대")
                   - country: ISO 3166-1 alpha-2 코드 (KR, JP, US 등)
                   - regions: DB 매칭용 영어 상위 도시명. 하위 구역은 상위 도시로 매핑. (해운대 → Busan, 홍대 → Seoul, 신주쿠 → Tokyo)
                2. 현실성 검증:
                   - 예산/기간 조합 (1일 최소 비용 기준 검증)
                   - 테마 상충 여부
                3. 검색 힌트 생성:
                   - searchTags: 벡터 검색용 태그 5~10개. 하위 구역 입력 시 해당 구역명과 주변 명소 키워드를 앞쪽에 배치.
                   - budgetRange: LOW(<50만/일), MEDIUM(50~150만/일), HIGH(150~300만/일), LUXURY(300만+/일)
                   - seasonContext: 해당 월 시즌 정보
                4. 지역 배분 (5일+ 여행 시만):
                   - regionAllocation: {"1-2": ["시부야","하라주쿠"], "3-4": ["아사쿠사","우에노"]}

                ## 응답 (반드시 JSON만 출력하세요)
                {
                  "valid": true/false,
                  "errorCode": "UNREALISTIC_BUDGET" | "CONFLICTING_THEMES" | null,
                  "errorMessage": "수정 제안 텍스트" | null,
                  "normalizedDestination": "해운대",
                  "country": "KR",
                  "regions": ["Busan"],
                  "searchTags": ["해운대 해수욕장", "마린시티", "동백섬", "해운대 맛집", "센텀시티"],
                  "budgetRange": "MEDIUM",
                  "seasonContext": "7월 여름 성수기",
                  "regionAllocation": null,
                  "enrichedContext": "기존 형식의 텍스트 컨텍스트"
                }
                """;
    }

    /**
     * 예산과 기간으로 budgetRange를 추정한다 (fallback용).
     */
    private String estimateBudgetRange(BigDecimal budget, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (budget == null || startDate == null || endDate == null) {
            return "MEDIUM";
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days <= 0) days = 1;
        BigDecimal dailyBudget = budget.divide(BigDecimal.valueOf(days), java.math.RoundingMode.HALF_UP);
        BigDecimal threshold500k = new BigDecimal("500000");
        BigDecimal threshold150 = new BigDecimal("1500000");
        BigDecimal threshold300 = new BigDecimal("3000000");

        if (dailyBudget.compareTo(threshold500k) < 0) return "LOW";
        if (dailyBudget.compareTo(threshold150) < 0) return "MEDIUM";
        if (dailyBudget.compareTo(threshold300) < 0) return "HIGH";
        return "LUXURY";
    }

    /**
     * 재시도 로직. 최대 maxRetries회까지 재시도하며 exponential backoff 적용.
     */
    private <T> T executeWithRetry(Supplier<T> action, int maxRetries) {
        int attempt = 0;
        Exception lastException = null;
        while (attempt <= maxRetries) {
            try {
                return action.get();
            } catch (Exception e) {
                lastException = e;
                attempt++;
                if (attempt > maxRetries) {
                    break;
                }
                long waitMs = 1000L * attempt;
                log.warn("Anthropic API 호출 실패 (시도 {}/{}), {}ms 후 재시도: {}",
                        attempt, maxRetries, waitMs, e.getMessage());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("재시도 중 인터럽트 발생", ie);
                }
            }
        }
        throw new RuntimeException("Anthropic API 최대 재시도 초과", lastException);
    }

    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value instanceof String s ? s : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getListOrDefault(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
