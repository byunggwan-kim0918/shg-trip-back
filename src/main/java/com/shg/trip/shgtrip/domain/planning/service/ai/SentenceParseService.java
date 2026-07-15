package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.planning.dto.SentenceParseResponse;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 자연어 여행 문장 → 구조화 필드 파싱 (Haiku 1회, 경량).
 * NewTripShell의 실시간 "이해했어요" 패널과 마법사 프리필에 쓰인다.
 *
 * <p>설계는 {@link OptimizedClaudeAIService}를 미러한다: 프롬프트 템플릿은 생성자에서 1회 로드하되
 * {@code today}는 매 요청 주입한다(생성자 캐싱 시 날짜 고정 버그). AI 응답은 {@code Map}으로 받아
 * 필드별로 방어적으로 추출하므로 한 필드의 타입 오류가 나머지 필드를 죽이지 않는다.
 * sanitize는 마법사가 강제하는 제약(enum 화이트리스트, @FutureOrPresent, @ValidDateRange의
 * endDate&gt;startDate·maxDays)을 서버에서 재확인해 프리필→제출 시 400을 예방한다.
 */
@Slf4j
@Service
public class SentenceParseService {

    private static final int MAX_RETRIES = 1;            // 동기(요청 스레드) 호출이라 재시도 최소화
    private static final int MAX_TOKENS = 512;
    private static final int MAX_TRIP_DAYS = 10;         // ItineraryGenerateRequest @ValidDateRange(maxDays=10)
    private static final int MAX_DESTINATION_LEN = 80;
    private static final int MAX_PARTY_LEN = 40;
    private static final BigDecimal MAX_BUDGET = new BigDecimal("100000000"); // 마법사/@DecimalMax 1억원

    // 프론트 고정 enum ID와 1:1 (wizardOptions.ts / itinerary.ts). 목록 밖 값은 버린다.
    private static final Set<String> VALID_THEMES = Set.of(
            "healing", "activity", "food", "culture", "shopping", "nature", "adventure", "romance",
            "family", "budget", "luxury", "photo", "walking", "ocean", "mountain", "nightview",
            "local", "art", "festival", "pet");
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "attraction", "restaurant", "cafe", "accommodation", "experience", "shopping", "nightlife",
            "nature", "museum", "theme_park", "spa", "market", "beach", "temple", "street_food",
            "viewpoint", "trail");
    private static final Set<String> VALID_PACE = Set.of("tight", "normal", "relaxed");
    private static final Set<String> VALID_TRANSPORT = Set.of("walk", "car", "any");

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;

    public SentenceParseService(AnthropicClient anthropicClient,
                                AnthropicProperties anthropicProperties,
                                ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.objectMapper = objectMapper;
        this.promptTemplate = loadPromptTemplate("prompts/parse-sentence.txt");
    }

    /**
     * 문장을 파싱한다.
     * 콘텐츠 실패(JSON 깨짐·여행 무관 문장)는 {@link SentenceParseResponse#empty()}로 graceful degradation.
     * Anthropic 인프라 실패(재시도 소진)만 {@link BusinessException}(AI_SERVICE_ERROR)으로 던진다.
     */
    public SentenceParseResponse parse(String sentence) {
        LocalDate today = LocalDate.now();
        String prompt = buildPrompt(sentence, today);

        Message message;
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.haiku())
                    .maxTokens(MAX_TOKENS)
                    .addUserMessage(prompt)
                    .build();
            message = executeWithRetry(() -> anthropicClient.messages().create(params), MAX_RETRIES);
        } catch (Exception e) {
            log.warn("문장 파싱 Anthropic 호출 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "문장 분석에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }

        String responseText = message.content().stream()
                .filter(block -> block.isText())
                .map(block -> block.asText().text())
                .collect(Collectors.joining());

        return parseResponse(responseText, today);
    }

    /**
     * Haiku JSON 응답을 파싱·sanitize하여 안전한 응답으로 만든다.
     * 필드별 방어 추출 — 한 필드가 잘못돼도 나머지는 살린다. package-private for testing.
     */
    SentenceParseResponse parseResponse(String responseText, LocalDate today) {
        try {
            int start = responseText.indexOf('{');
            int end = responseText.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.info("문장 파싱: JSON 미발견 → empty");
                return SentenceParseResponse.empty();
            }
            String json = responseText.substring(start, end + 1);
            Map<String, Object> parsed = objectMapper.readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return sanitize(parsed, today);
        } catch (Exception e) {
            log.info("문장 파싱 JSON 파싱 실패 → empty: {}", e.getMessage());
            return SentenceParseResponse.empty();
        }
    }

    /**
     * AI 원시 Map을 검증한다. 마법사 enum·날짜·기간 제약을 서버에서 재확인해 프리필 안전성을 보장한다.
     * package-private for testing.
     */
    SentenceParseResponse sanitize(Map<String, Object> map, LocalDate today) {
        String destination = capLength(blankToNull(getString(map, "destination")), MAX_DESTINATION_LEN);
        String party = capLength(blankToNull(getString(map, "party")), MAX_PARTY_LEN);

        List<String> themes = filterWhitelist(getStringList(map, "themes"), VALID_THEMES);
        List<String> categories = filterWhitelist(getStringList(map, "categories"), VALID_CATEGORIES);
        String pace = inWhitelist(getString(map, "pace"), VALID_PACE);
        String transportPref = inWhitelist(getString(map, "transportPref"), VALID_TRANSPORT);

        // 날짜: @FutureOrPresent / endDate>startDate / maxDays 를 모두 재확인
        LocalDate startDate = parseDate(getString(map, "startDate"));
        LocalDate endDate = parseDate(getString(map, "endDate"));
        if (startDate != null && startDate.isBefore(today)) {
            // 시작일이 과거면 날짜 쌍을 통째로 버린다(사용자가 마법사에서 직접 선택)
            startDate = null;
            endDate = null;
        }
        if (startDate != null && endDate != null) {
            if (!endDate.isAfter(startDate)) {
                // 당일치기(같은 날)·역순 — @ValidDateRange가 endDate>startDate를 강제하므로 폐기
                endDate = null;
            } else if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_TRIP_DAYS) {
                // 최대 여행 기간(10일) 초과 — endDate만 버려 사용자가 다시 고르게
                endDate = null;
            }
        }
        if (endDate != null && startDate == null) {
            // 시작일 없이 종료일만 있으면 무의미 → 버림
            endDate = null;
        }

        BigDecimal budget = getBigDecimal(map, "budget");
        if (budget != null && (budget.signum() <= 0 || budget.compareTo(MAX_BUDGET) > 0)) {
            budget = null;
        }

        return new SentenceParseResponse(
                destination, startDate, endDate, party, themes, categories, pace, transportPref, budget);
    }

    // ── helpers ──

    private String buildPrompt(String sentence, LocalDate today) {
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        String todayStr = today + " (" + weekday + ")";
        return promptTemplate
                .replace("{today}", todayStr)
                .replace("{sentence}", sentence);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : null;
    }

    /** budget을 방어적으로 추출 — Number이면 그대로, 숫자 문자열이면 파싱, "50만원" 등은 null. */
    private BigDecimal getBigDecimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** themes/categories를 방어적으로 추출 — 배열이면 각 원소, 단일 문자열이면 1원소, 그 외 빈 리스트. */
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().filter(v -> v != null).map(Object::toString).collect(Collectors.toList());
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String capLength(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String inWhitelist(String value, Set<String> whitelist) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return whitelist.contains(v) ? v : null;
    }

    private List<String> filterWhitelist(List<String> values, Set<String> whitelist) {
        if (values == null) return List.of();
        return values.stream()
                .filter(v -> v != null)
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .filter(whitelist::contains)
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
    }

    private String loadPromptTemplate(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("문장 파싱 프롬프트 템플릿 로드 실패: {}", path);
            throw new IllegalStateException("prompts/parse-sentence.txt 로드 실패", e);
        }
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
                if (attempt > maxRetries) break;
                long waitMs = 500L * attempt;
                log.warn("문장 파싱 API 호출 실패 (시도 {}/{}), {}ms 후 재시도: {}",
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
}
