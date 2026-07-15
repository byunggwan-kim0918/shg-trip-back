package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.planning.dto.SentenceParseResponse;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SentenceParseService의 파싱·sanitize 로직 단위 테스트.
 * Anthropic 호출 없이 package-private parseResponse/sanitize만 검증한다(parseEnrichResponse 관례 미러).
 */
@ExtendWith(MockitoExtension.class)
class SentenceParseServiceTest {

    @Mock private AnthropicClient anthropicClient;

    private SentenceParseService service;
    private final LocalDate today = LocalDate.of(2026, 7, 13);

    @BeforeEach
    void setUp() {
        AnthropicProperties properties =
                new AnthropicProperties("claude-haiku-4-5-20250610", "claude-sonnet-4-20250514", 64000);
        service = new SentenceParseService(anthropicClient, properties, new ObjectMapper());
    }

    @Test
    @DisplayName("정상 JSON을 필드로 파싱한다")
    void parsesValidJson() {
        String json = """
                {
                  "destination": "제주",
                  "startDate": "2026-07-25",
                  "endDate": "2026-07-27",
                  "party": "가족",
                  "themes": ["healing", "food"],
                  "categories": ["cafe", "attraction"],
                  "pace": "relaxed",
                  "transportPref": null,
                  "budget": 500000
                }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.destination()).isEqualTo("제주");
        assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 7, 25));
        assertThat(r.endDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(r.party()).isEqualTo("가족");
        assertThat(r.themes()).containsExactly("healing", "food");
        assertThat(r.categories()).containsExactly("cafe", "attraction");
        assertThat(r.pace()).isEqualTo("relaxed");
        assertThat(r.transportPref()).isNull();
        assertThat(r.budget()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("화이트리스트 밖 themes/categories/pace/transportPref는 걸러낸다")
    void filtersUnknownEnums() {
        String json = """
                {
                  "destination": "부산",
                  "themes": ["food", "made_up_theme", "쇼핑"],
                  "categories": ["cafe", "not_a_category"],
                  "pace": "super_fast",
                  "transportPref": "teleport"
                }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.themes()).containsExactly("food");
        assertThat(r.categories()).containsExactly("cafe");
        assertThat(r.pace()).isNull();
        assertThat(r.transportPref()).isNull();
    }

    @Test
    @DisplayName("시작일이 과거면 날짜 쌍을 통째로 버린다")
    void dropsPastDatePair() {
        String json = """
                { "destination": "경주", "startDate": "2026-07-01", "endDate": "2026-07-03" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isNull();
        assertThat(r.endDate()).isNull();
        assertThat(r.destination()).isEqualTo("경주");
    }

    @Test
    @DisplayName("오늘 날짜는 유효(경계 포함)")
    void keepsTodayAsStart() {
        String json = """
                { "startDate": "2026-07-13", "endDate": "2026-07-14" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isEqualTo(today);
        assertThat(r.endDate()).isEqualTo(LocalDate.of(2026, 7, 14));
    }

    @Test
    @DisplayName("당일치기(종료일==시작일)는 종료일을 버린다 (@ValidDateRange endDate>startDate 보호)")
    void dropsSameDayEndDate() {
        String json = """
                { "startDate": "2026-08-10", "endDate": "2026-08-10" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(r.endDate()).isNull();
    }

    @Test
    @DisplayName("여행 기간이 10일을 초과하면 종료일을 버린다 (@ValidDateRange maxDays 보호)")
    void dropsOverMaxDays() {
        String json = """
                { "startDate": "2026-08-01", "endDate": "2026-09-01" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(r.endDate()).isNull();
    }

    @Test
    @DisplayName("정확히 10일(경계)은 유지한다")
    void keepsExactlyMaxDays() {
        String json = """
                { "startDate": "2026-08-01", "endDate": "2026-08-10" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(r.endDate()).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    @DisplayName("종료일이 시작일보다 이르면 종료일만 버린다")
    void dropsReversedEndDate() {
        String json = """
                { "startDate": "2026-08-10", "endDate": "2026-08-05" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(r.endDate()).isNull();
    }

    @Test
    @DisplayName("시작일 없이 종료일만 있으면 버린다")
    void dropsOrphanEndDate() {
        String json = """
                { "endDate": "2026-08-05" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isNull();
        assertThat(r.endDate()).isNull();
    }

    @Test
    @DisplayName("예산이 0 이하이거나 1억 초과면 버린다")
    void clampsInvalidBudget() {
        assertThat(service.parseResponse("{ \"budget\": 0 }", today).budget()).isNull();
        assertThat(service.parseResponse("{ \"budget\": -100 }", today).budget()).isNull();
        assertThat(service.parseResponse("{ \"budget\": 200000000 }", today).budget()).isNull();
        assertThat(service.parseResponse("{ \"budget\": 1000000 }", today).budget())
                .isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("잘못된 날짜 문자열은 null로 처리하되 나머지는 살린다")
    void handlesMalformedDate() {
        String json = """
                { "destination": "여수", "startDate": "언젠가", "themes": ["ocean"] }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.startDate()).isNull();
        assertThat(r.destination()).isEqualTo("여수");
        assertThat(r.themes()).containsExactly("ocean");
    }

    @Test
    @DisplayName("JSON 앞뒤 잡텍스트가 있어도 brace-slice로 추출한다")
    void slicesJsonFromNoise() {
        String noisy = "네, 분석 결과입니다:\n{ \"destination\": \"강릉\" }\n감사합니다.";
        SentenceParseResponse r = service.parseResponse(noisy, today);
        assertThat(r.destination()).isEqualTo("강릉");
    }

    @Test
    @DisplayName("JSON이 없으면 empty()를 반환한다")
    void emptyWhenNoJson() {
        SentenceParseResponse r = service.parseResponse("죄송하지만 이해하지 못했습니다.", today);

        assertThat(r.destination()).isNull();
        assertThat(r.themes()).isEmpty();
        assertThat(r.categories()).isEmpty();
        assertThat(r.budget()).isNull();
    }

    @Test
    @DisplayName("깨진 JSON은 empty()로 graceful degrade")
    void emptyWhenBrokenJson() {
        SentenceParseResponse r = service.parseResponse("{ \"destination\": \"제주\", ", today);
        assertThat(r.destination()).isNull();
        assertThat(r.themes()).isEmpty();
    }

    @Test
    @DisplayName("budget이 숫자 문자열이면 파싱하고, '50만원' 같은 비숫자면 null")
    void handlesBudgetAsString() {
        assertThat(service.parseResponse("{ \"budget\": \"500000\" }", today).budget())
                .isEqualByComparingTo("500000");
        assertThat(service.parseResponse("{ \"budget\": \"50만원\" }", today).budget()).isNull();
    }

    @Test
    @DisplayName("themes가 배열 아닌 단일 문자열이어도 1원소로 처리")
    void handlesThemesAsSingleString() {
        SentenceParseResponse r = service.parseResponse("{ \"themes\": \"food\" }", today);
        assertThat(r.themes()).containsExactly("food");
    }

    @Test
    @DisplayName("한 필드 타입 오류가 나머지 필드를 죽이지 않는다 (Map 방어 추출)")
    void badFieldDoesNotKillOthers() {
        String json = """
                { "destination": "제주", "budget": "공짜", "themes": ["food"], "startDate": "2026-08-01", "endDate": "2026-08-03" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);

        assertThat(r.destination()).isEqualTo("제주");
        assertThat(r.budget()).isNull();       // 비숫자 → 개별 폐기
        assertThat(r.themes()).containsExactly("food");
        assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(r.endDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    @Test
    @DisplayName("지나치게 긴 destination은 길이를 캡한다")
    void capsLongDestination() {
        String longName = "가".repeat(200);
        SentenceParseResponse r = service.parseResponse("{ \"destination\": \"" + longName + "\" }", today);
        assertThat(r.destination()).hasSize(80);
    }

    @Test
    @DisplayName("공백 destination/party는 null로 정규화")
    void blankToNull() {
        String json = """
                { "destination": "   ", "party": "" }
                """;
        SentenceParseResponse r = service.parseResponse(json, today);
        assertThat(r.destination()).isNull();
        assertThat(r.party()).isNull();
    }
}
