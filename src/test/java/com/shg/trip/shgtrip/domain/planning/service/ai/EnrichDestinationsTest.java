package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichDestinationsTest {

    private final Destinations destinations = Destinations.fromClasspath();

    @Test
    @DisplayName("프롬프트 목록은 나라별 한 줄에 표준명(한국어 이름; 관문)을 나열한다")
    void promptLines() {
        String lines = EnrichDestinations.promptLines(destinations);

        assertThat(lines.lines()).hasSize(4).allSatisfy(l -> assertThat(l).startsWith("    - "));
        assertThat(lines).contains("    - 일본: ", "    - 중국: ", "    - 베트남: ", "    - 대만: ");
        assertThat(lines).contains("Tokyo(도쿄; 관문 Haneda Airport / Narita International Airport)");
        assertThat(lines).contains("Jiufen(지우펀; 상위 Taipei)", "Hoi An(호이안; 상위 Da Nang)", "Matsumoto(마쓰모토)");
        destinations.all().stream().filter(d -> d.gateways().isEmpty() && d.parent() != null).forEach(d ->
                assertThat(destinations.gatewayRegion(d.name())).as(d.name()).isEqualTo(d.parent()));
    }

    @Test
    @DisplayName("enrich 프롬프트의 예시 여행지·허브는 목록에 있고, 자리표시자는 목록으로 바뀐다")
    void promptTemplateMatchesDestinations() throws Exception {
        String template = new ClassPathResource("prompts/enrich-input.txt").getContentAsString(StandardCharsets.UTF_8);
        assertThat(template).contains(EnrichDestinations.PLACEHOLDER);

        String prompt = template.replace(EnrichDestinations.PLACEHOLDER, EnrichDestinations.promptLines(destinations));

        assertThat(prompt).doesNotContain(EnrichDestinations.PLACEHOLDER);
        for (String name : List.of("Jiufen", "Hakone", "Badaling", "Shanghai", "Taipei")) {
            assertThat(destinations.find(name)).as(name).isPresent();
        }
        assertThat(destinations.find("Tokyo").orElseThrow().gateways())
                .anySatisfy(g -> assertThat(g.name()).isEqualTo("Narita International Airport"));
    }

    @Test
    @DisplayName("regions는 표준명·한국어 이름·별칭을 표준명으로 바꾸고 겹치면 하나만 둔다. 목록에 없는 이름은 그대로다")
    void canonicalRegions() {
        assertThat(EnrichDestinations.canonicalRegions(
                Arrays.asList("Saigon", "호찌민", " Busan ", "Asakusa", "tokyo", null, " "), destinations))
                .containsExactly("Ho Chi Minh City", "Busan", "Tokyo");
        assertThat(EnrichDestinations.canonicalRegions(null, destinations)).isNull();
    }

    @Test
    @DisplayName("일차별 배분은 표준명·한국어 이름만 바꾸고 별칭(하위 구역)은 힌트로 남긴다")
    void canonicalAllocation() {
        Map<String, List<String>> allocation = EnrichDestinations.canonicalAllocation(Map.of(
                "day1", List.of("도쿄", "Asakusa"),
                "day2", List.of("jiufen")), destinations);

        assertThat(allocation.get("day1")).containsExactly("Tokyo", "Asakusa");
        assertThat(allocation.get("day2")).containsExactly("Jiufen");
        assertThat(EnrichDestinations.canonicalAllocation(Map.of("day3", "하코네"), destinations).get("day3"))
                .containsExactly("Hakone");
        assertThat(EnrichDestinations.canonicalAllocation(null, destinations)).isNull();
    }
}
