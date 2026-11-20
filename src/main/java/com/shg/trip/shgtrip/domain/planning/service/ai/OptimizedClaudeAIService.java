package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichmentResult;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OptimizedClaudeAIService {

    private static final int MAX_RETRIES = 2;
    private static final int READ_ATTEMPTS = 2;
    /** 동행·섬·검색쿼리까지 담은 enrich JSON은 1024 토큰이면 잘린다. */
    private static final int ENRICH_MAX_TOKENS = 2048;

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final ObjectMapper objectMapper;
    private final Destinations destinations;
    private final String enrichPromptTemplate;

    public OptimizedClaudeAIService(AnthropicClient anthropicClient,
                                     AnthropicProperties anthropicProperties,
                                     ObjectMapper objectMapper,
                                     Destinations destinations) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.objectMapper = objectMapper;
        this.destinations = destinations;
        this.enrichPromptTemplate = loadPromptTemplateSafe("prompts/enrich-input.txt")
                .replace(EnrichDestinations.PLACEHOLDER, EnrichDestinations.promptLines(destinations));
    }

    /**
     * 비현실적 입력이면 에러 코드와 수정 제안을 반환해 파이프라인을 중단한다.
     * API 실패는 최대 3회 재시도하고, 응답을 읽지 못하면 한 번 다시 묻는다. 그래도 안 되면
     * {@link ErrorCode#AI_SERVICE_ERROR}로 끝낸다.
     */
    public EnrichmentResult enrichInput(ItineraryGenerateRequest input) {
        String prompt = buildEnrichPrompt(input);
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.haiku())
                    .thinking(ModelRequestPolicy.HAIKU_THINKING)
                    .maxTokens(ENRICH_MAX_TOKENS)
                    .addUserMessage(prompt)
                    .build();
            for (int attempt = 1; attempt <= READ_ATTEMPTS; attempt++) {
                Message message = executeWithRetry(
                        () -> anthropicClient.messages().create(params), MAX_RETRIES);
                if (message.stopReason().isPresent()
                        && message.stopReason().get().equals(com.anthropic.models.messages.StopReason.MAX_TOKENS)) {
                    log.warn("enrichInput 응답이 max_tokens({})에서 잘림", ENRICH_MAX_TOKENS);
                }
                String responseText = message.content().stream()
                        .filter(block -> block.isText())
                        .map(block -> block.asText().text())
                        .collect(Collectors.joining());
                EnrichmentResult result = readEnrichResponse(responseText, input);
                if (result != null) return result;
            }
        } catch (Exception e) {
            log.warn("enrichInput 실패: {}", e.getMessage());
        }
        throw enrichFailed();
    }

    /** @return 읽은 결과. JSON이 없거나 읽지 못하면 null */
    @SuppressWarnings("unchecked")
    EnrichmentResult readEnrichResponse(String responseText, ItineraryGenerateRequest input) {
        try {
            // AI가 JSON 앞뒤에 설명을 붙이는 경우가 있다.
            int jsonStart = responseText.indexOf('{');
            int jsonEnd = responseText.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn("enrichInput: JSON not found in response");
                return null;
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

            String normalizedDestination = getStringOrDefault(parsed, "normalizedDestination", input.destination());
            String country = getStringOrDefault(parsed, "country", "");
            List<String> regions = EnrichDestinations.canonicalRegions(getListOrDefault(parsed, "regions"), destinations);
            List<String> mentioned = EnrichDestinations.mentionedDestinations(regions,
                    input.destination() + " " + (input.description() != null ? input.description() : ""), destinations);
            if (!mentioned.isEmpty()) {
                log.info("요청 문장에 나온 근교 여행지 추가: {}", mentioned);
                regions = java.util.stream.Stream.concat(regions.stream(), mentioned.stream()).toList();
            }
            List<String> searchTags = getListOrDefault(parsed, "searchTags");
            String budgetRange = getStringOrDefault(parsed, "budgetRange", "MEDIUM");
            String seasonContext = getStringOrDefault(parsed, "seasonContext", "");
            String enrichedContext = getStringOrDefault(parsed, "enrichedContext", "");

            Map<String, List<String>> regionAllocation = null;
            Object regionAllocObj = parsed.get("regionAllocation");
            if (regionAllocObj instanceof Map) {
                regionAllocation = EnrichDestinations.canonicalAllocation((Map<?, ?>) regionAllocObj, destinations);
                regionAllocation = EnrichDestinations.withAllocated(regionAllocation, mentioned, destinations);
            }

            TransportationHub transportationHub = null;
            Object hubObj = parsed.get("transportationHub");
            if (hubObj instanceof Map) {
                Map<String, Object> hubMap = (Map<String, Object>) hubObj;
                transportationHub = new TransportationHub(
                        (String) hubMap.get("arrivalHub"),
                        (String) hubMap.get("departureHub"),
                        (String) hubMap.get("hubType")
                );
            }

            Map<String, String> categorySearchQueries = null;
            Object queriesObj = parsed.get("categorySearchQueries");
            if (queriesObj instanceof Map) {
                categorySearchQueries = (Map<String, String>) queriesObj;
            }

            VectorEnrichedInput.CompanionType companionType =
                    VectorEnrichedInput.CompanionType.parse(parsed.get("companionType"));
            Integer partySize = parsed.get("partySize") instanceof Number n && n.intValue() > 0
                    ? n.intValue() : null;
            List<String> ferryOnlyAreas = getListOrDefault(parsed, "ferryOnlyAreas");

            VectorEnrichedInput vectorEnrichedInput = new VectorEnrichedInput(
                    input.destination(),
                    input.themes(),
                    input.categories(),
                    input.pace() != null ? input.pace() : "normal",
                    input.transportPref() != null ? input.transportPref() : "any",
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
                    enrichedContext,
                    transportationHub,
                    categorySearchQueries,
                    companionType,
                    partySize,
                    ferryOnlyAreas
            );

            log.info("enrichInput 성공: destination='{}' → normalized='{}', country='{}', regions={}, "
                            + "companion={}, partySize={}, ferryOnlyAreas={}",
                    input.destination(), normalizedDestination, country, regions,
                    companionType, partySize, ferryOnlyAreas);
            return EnrichmentResult.success(vectorEnrichedInput);

        } catch (Exception e) {
            log.warn("enrichInput JSON 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 나라·지역 없이는 여행지 장소를 찾을 수 없어 보강 실패는 생성 실패다. */
    private static BusinessException enrichFailed() {
        return new BusinessException(ErrorCode.AI_SERVICE_ERROR, "여행 조건을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private String buildEnrichPrompt(ItineraryGenerateRequest input) {
        // 예산은 선택 입력이라 null이면 "%,.0f"가 "null"을 찍어 프롬프트에 "예산: null"이 들어간다.
        String formattedBudget = input.budget() != null
                ? String.format("%,.0f원", input.budget())
                : "선택 없음";
        return enrichPromptTemplate
                .replace("{destination}", input.destination())
                .replace("{themes}", String.join(", ", input.themes()))
                .replace("{categories}", String.join(", ", input.categories()))
                .replace("{budget}", formattedBudget)
                .replace("{startDate}", input.startDate().toString())
                .replace("{endDate}", input.endDate().toString())
                .replace("{pace}", input.pace() != null ? input.pace() : "normal")
                .replace("{transportPref}", input.transportPref() != null ? input.transportPref() : "any")
                .replace("{description}", input.description() != null ? input.description() : "없음");
    }

    /**
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

    private String getInlineEnrichPrompt() {
        return """
                당신은 여행 입력 분석 전문가입니다. 사용자 입력을 정규화하고 검증합니다.

                ## 입력
                - 여행지: {destination}
                - 테마: {themes}
                - 카테고리: {categories}
                - 예산: {budget}
                - 기간: {startDate} ~ {endDate}
                - 페이스: {pace}
                - 이동수단 선호: {transportPref} (walk=도보/버스 우선, car=자동차 우선, any=상관없음)
                - 설명: {description}

                ## 작업
                1. 여행지 정규화:
                   - normalizedDestination: 오타/약어/외래어만 교정. 하위 구역은 그대로 유지. ("해운대" → "해운대", "홍대" → "홍대")
                   - country: ISO 3166-1 alpha-2 코드 (KR, JP, US 등)
                   - regions: DB 매칭용 영어 상위 도시명. 하위 구역은 상위 도시로 매핑. (해운대 → Busan, 홍대 → Seoul, 신주쿠 → Tokyo)
                2. 현실성 검증 (명백히 비현실적일 때만 valid=false, 애매하면 통과):
                   - 예산/기간 조합 (1일 최소 비용 기준) → UNREALISTIC_BUDGET
                   - 테마 상충 여부 → CONFLICTING_THEMES
                   - 실존하지 않는 여행지(오타 교정으로도 특정 불가) → INVALID_DESTINATION
                   - 여행 범위 대비 기간이 명백히 비현실적 → INVALID_DATE_RANGE
                   - errorMessage는 "문제 요약 + 권장 조치(구체 수치)" 형식
                3. 검색 힌트 생성:
                   - searchTags: 벡터 검색용 태그 5~10개. 하위 구역 입력 시 해당 구역명과 주변 명소 키워드를 앞쪽에 배치.
                   - budgetRange: LOW(<50만/일), MEDIUM(50~150만/일), HIGH(150~300만/일), LUXURY(300만+/일)
                   - seasonContext: 해당 월 시즌 정보
                4. 지역 배분 (5일+ 여행 또는 서로 먼 여러 도시 여행, 문장의 도시별 일수를 따름):
                   - regionAllocation: {"1-2": ["시부야","하라주쿠"], "3-4": ["아사쿠사","우에노"]}

                ## 응답 (반드시 JSON만 출력하세요)
                {
                  "valid": true/false,
                  "errorCode": "UNREALISTIC_BUDGET" | "CONFLICTING_THEMES" | "INVALID_DESTINATION" | "INVALID_DATE_RANGE" | null,
                  "errorMessage": "문제 요약 + 권장 조치(구체 수치)" | null,
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
