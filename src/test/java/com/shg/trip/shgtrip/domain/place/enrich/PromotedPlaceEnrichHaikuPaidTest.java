package com.shg.trip.shgtrip.domain.place.enrich;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.PlaceEmbedder;
import com.shg.trip.shgtrip.domain.place.batch.PlaceEnrichment;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 실제 Haiku로 승격 장소 보강 한 차례의 응답 시간·토큰·해석 결과를 잰다. 유료 호출이라 SHG_PAID_HAIKU_TEST=true일 때만 돈다.
 * 호출은 장소 수(최대 5회)를 넘지 않고, 누적 비용이 상한을 넘으면 남은 장소를 부르지 않고 멈춘다.
 */
@Tag("paid")
@EnabledIfEnvironmentVariable(named = "SHG_PAID_HAIKU_TEST", matches = "true")
class PromotedPlaceEnrichHaikuPaidTest {

    private static final String MODEL = "claude-haiku-5-5";
    private static final int MAX_CALLS = 5;
    /** 5건 예상 비용($0.002)의 두 배. 넘으면 남은 호출을 멈춘다. */
    private static final double MAX_COST_USD = 0.004;
    // claude-haiku-5-5 표준 단가(USD / 백만 토큰, 요청 100K 토큰 이하). 캐시 쓰기 1.25배, 읽기 0.1배.
    private static final double INPUT = 0.10, OUTPUT = 0.50, CACHE_WRITE = 0.125, CACHE_READ = 0.01;

    private static Place place(String name, String nameKo, String address, String category, String region,
                               String country, Integer sitelinks) {
        return Place.builder().id((long) Math.abs(name.hashCode())).name(name).nameKo(nameKo).address(address)
                .category(category).region(region).country(country).wikidataSitelinks(sitelinks)
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).active(true).source("paid-test").build();
    }

    @Test
    @DisplayName("실제 Haiku 5건: 응답 시간·토큰·비용과 배치 규칙 해석 결과")
    void measuresRealHaiku() throws Exception {
        String key = System.getenv("ANTHROPIC_API_KEY");
        assertThat(key).as("ANTHROPIC_API_KEY").isNotBlank();
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(key).build();
        PromotedPlaceEnricher enricher = new PromotedPlaceEnricher(mock(PlaceEnrichRequests.class),
                mock(PlaceEnrichBudget.class), mock(PlaceRepository.class), mock(PlaceEmbedder.class), client,
                new ObjectMapper(), Runnable::run, true, MODEL);
        List<Place> places = List.of(
                place("大須商店街", null, "日本愛知県名古屋市中区大須2丁目大須商店街", "Retail > Shopping Mall", "Nagoya", "JP", null),
                place("矢場とん JR名古屋駅店", null, "中村区名駅1-1-4",
                        "Dining and Drinking > Restaurant > Asian Restaurant > Japanese Restaurant > Tonkatsu Restaurant",
                        "Nagoya", "JP", null),
                place("熱田神宮 (Atsuta-Jingū Shrine)", "아쓰타 신궁", "熱田区神宮1-1-1",
                        "Community and Government > Spiritual Center > Shrine", "Nagoya", "JP", 23),
                place("Wat Arun", null, "158 Thanon Wang Doem, Wat Arun, Bangkok Yai, Bangkok 10600",
                        "Landmarks and Outdoors > Temple", "Bangkok", "TH", 60),
                place("Café de Flore", null, "172 Bd Saint-Germain, 75006 Paris",
                        "Dining and Drinking > Cafe, Coffee, and Tea House", "Paris", "FR", 25));

        List<String> rows = new ArrayList<>();
        rows.add("| 장소 | 국가 | 응답(ms) | 모델 | input | output | 캐시읽음 | 캐시쓰기 | 비용($) | 해석 | name_ko | role | 소개 |");
        rows.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        double total = 0;
        int calls = 0;
        boolean stopped = false;
        for (Place p : places) {
            if (calls >= MAX_CALLS || total > MAX_COST_USD) {
                stopped = true;
                break;
            }
            long started = System.nanoTime();
            PromotedPlaceEnricher.Answer answer = enricher.ask(PlaceEnrichment.prompt(p));
            long ms = (System.nanoTime() - started) / 1_000_000;
            calls++;
            double cost = (answer.inputTokens() * INPUT + answer.outputTokens() * OUTPUT
                    + answer.cacheWriteTokens() * CACHE_WRITE + answer.cacheReadTokens() * CACHE_READ) / 1e6;
            total += cost;
            boolean applied = answer.text() != null && PlaceEnrichment.apply(new ObjectMapper(), p, answer.text());
            String intro = p.getDescription() == null ? "" : p.getDescription().replace("|", "/");
            rows.add(String.format("| %s | %s | %d | %s | %d | %d | %d | %d | %.5f | %s | %s | %s | %s |", p.getName(),
                    p.getCountry(), ms, answer.model(), answer.inputTokens(), answer.outputTokens(),
                    answer.cacheReadTokens(), answer.cacheWriteTokens(), cost, applied ? "통과" : "실패",
                    p.getNameKo(), p.getPlaceRole(), intro.length() > 60 ? intro.substring(0, 60) + "…" : intro));
        }
        rows.add("");
        rows.add(String.format("호출 %d회, 누적 비용 $%.5f%s", calls, total, stopped ? " (상한으로 중단)" : ""));
        Files.write(Path.of("build/paid-haiku.md"), rows);
        rows.forEach(System.out::println);
        assertThat(calls).isLessThanOrEqualTo(MAX_CALLS);
    }
}
