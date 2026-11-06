package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.WizardOptionIds;
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
 * {@code today}는 생성자에서 캐싱하면 날짜가 고정되므로 매 요청 주입하고, 응답은 필드별로 방어 추출해 한 필드 오류가 나머지를 죽이지 않게 한다.
 * sanitize는 마법사 제약(enum, @FutureOrPresent, @ValidDateRange)을 서버에서 재확인해 프리필 후 제출 시 400을 막는다.
 */
@Slf4j
@Service
public class SentenceParseService {

    private static final int MAX_RETRIES = 1;            // 동기(요청 스레드) 호출이라 재시도 최소화
    private static final int MAX_TOKENS = 512;
    private static final int MAX_TRIP_DAYS = 10;         // ItineraryGenerateRequest @ValidDateRange(maxDays=10)
    private static final int MAX_DESTINATION_LEN = 50;     // ItineraryGenerateRequest @Size(max=50)
    private static final int MAX_PARTY_LEN = 40;
    private static final int MAX_PARTY_SIZE = 20;                       // ItineraryGenerateRequest @Max
    private static final Set<String> VALID_COMPANIONS = Set.of("solo", "couple", "friends", "family", "parents");
    private static final java.util.regex.Pattern TIME = java.util.regex.Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final BigDecimal MAX_BUDGET = new BigDecimal("100000000"); // 마법사/@DecimalMax 1억원

    private static final Set<String> VALID_THEMES = WizardOptionIds.THEMES;
    private static final Set<String> VALID_CATEGORIES = WizardOptionIds.CATEGORIES;
    private static final Set<String> VALID_PACE = WizardOptionIds.PACES;
    private static final Set<String> VALID_TRANSPORT = WizardOptionIds.TRANSPORTS;

    /**
     * 과거("작년 1월")나 10일 초과 기간("한 달 살기") 표현이 있으면 날짜를 통째로 버린다.
     * 모델이 "작년 1월"을 내년으로 옮기거나 시작일만 내서 응답 날짜만으로는 걸러낼 수 없다.
     */
    private static final java.util.regex.Pattern OUT_OF_RANGE_DATE_EXPRESSION = java.util.regex.Pattern.compile(
            "작년|지난\\s?해|재작년|지난\\s?달|지난\\s?주|어제|그저께"
                    // "한 달 뒤"·"2주 후"는 출발 시점이라 기간을 뜻하는 꼬리가 붙을 때만 막는다.
                    + "|([한두세네]\\s?달|\\d+\\s?개월|보름|(?<!\\d)([2-9]|\\d{2,})\\s?주일?)\\s?(살기|동안|간|짜리|여행|일정|체류|머물)");

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
     * 콘텐츠 실패(JSON 깨짐·여행 무관 문장)는 {@link SentenceParseResponse#empty()}를 반환한다.
     * 재시도를 소진한 Anthropic 인프라 실패만 {@link BusinessException}(AI_SERVICE_ERROR)으로 던진다.
     */
    public SentenceParseResponse parse(String sentence) {
        LocalDate today = LocalDate.now();
        String prompt = buildPrompt(sentence, today);

        Message message;
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(anthropicProperties.haiku())
                    .thinking(ModelRequestPolicy.HAIKU_THINKING)
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

        SentenceParseResponse parsed = parseResponse(responseText, today);
        return hasOutOfRangeDate(sentence) ? withoutDates(parsed) : parsed;
    }

    static boolean hasOutOfRangeDate(String sentence) {
        return sentence != null && OUT_OF_RANGE_DATE_EXPRESSION.matcher(sentence).find();
    }

    private static SentenceParseResponse withoutDates(SentenceParseResponse r) {
        return new SentenceParseResponse(r.destination(), null, null, r.party(), r.themes(), r.categories(),
                r.pace(), r.transportPref(), r.budget(), r.companion(), r.partySize(), r.arrivalTime(), r.departureTime());
    }

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

    SentenceParseResponse sanitize(Map<String, Object> map, LocalDate today) {
        String destination = capLength(blankToNull(getString(map, "destination")), MAX_DESTINATION_LEN);
        String party = capLength(blankToNull(getString(map, "party")), MAX_PARTY_LEN);

        List<String> themes = filterWhitelist(getStringList(map, "themes"), VALID_THEMES);
        List<String> categories = filterWhitelist(getStringList(map, "categories"), VALID_CATEGORIES);
        String pace = inWhitelist(getString(map, "pace"), VALID_PACE);
        String transportPref = inWhitelist(getString(map, "transportPref"), VALID_TRANSPORT);

        LocalDate startDate = parseDate(getString(map, "startDate"));
        LocalDate endDate = parseDate(getString(map, "endDate"));
        if (startDate != null && startDate.isBefore(today)) {
            // 시작일이 과거면 날짜 쌍을 통째로 버리고 사용자가 마법사에서 직접 고르게 한다.
            startDate = null;
            endDate = null;
        }
        if (startDate != null && endDate != null) {
            if (!endDate.isAfter(startDate)) {
                // @ValidDateRange가 endDate>startDate를 강제해 당일치기·역순은 버린다.
                endDate = null;
            } else if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_TRIP_DAYS) {
                // 10일 초과는 마법사에서 고를 수 없는 기간이라 시작일만 남기면 엉뚱한 일정이 프리필된다
                startDate = null;
                endDate = null;
            }
        }
        if (endDate != null && startDate == null) {
            endDate = null;
        }

        BigDecimal budget = getBigDecimal(map, "budget");
        if (budget != null && (budget.signum() <= 0 || budget.compareTo(MAX_BUDGET) > 0)) {
            budget = null;
        }

        String companion = inWhitelist(getString(map, "companion"), VALID_COMPANIONS);
        Integer partySize = getInteger(map, "partySize");
        if (partySize != null && (partySize < 1 || partySize > MAX_PARTY_SIZE)) partySize = null;
        String arrivalTime = validTime(getString(map, "arrivalTime"));
        String departureTime = validTime(getString(map, "departureTime"));

        return new SentenceParseResponse(destination, startDate, endDate, party, themes, categories, pace,
                transportPref, budget, companion, partySize, arrivalTime, departureTime);
    }

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

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String str) {
            try {
                return Integer.valueOf(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** "9:00"처럼 한 자리 시도 받아 "09:00"으로 맞춘다. */
    private String validTime(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.matches("\\d:\\d{2}")) v = "0" + v;
        return TIME.matcher(v).matches() ? v : null;
    }

    /** Number면 그대로, 숫자 문자열이면 파싱하고, "50만원" 등은 null. */
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

    /** 배열이면 각 원소, 단일 문자열이면 1원소, 그 외는 빈 리스트. */
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
