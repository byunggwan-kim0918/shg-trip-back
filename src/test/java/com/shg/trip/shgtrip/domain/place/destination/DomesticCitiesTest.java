package com.shg.trip.shgtrip.domain.place.destination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomesticCitiesTest {

    @Test
    @DisplayName("국내 도시 이름은 보강 프롬프트의 한국 도시 목록과 같다")
    void namesMatchEnrichPrompt() throws IOException {
        String prompt;
        try (InputStream in = getClass().getResourceAsStream("/prompts/enrich-input.txt")) {
            prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String line = prompt.lines().filter(l -> l.trim().startsWith("- 한국 도시:")).findFirst().orElseThrow();
        List<String> listed = Arrays.stream(line.substring(line.indexOf(':') + 1).split(","))
                .map(String::trim).toList();

        assertThat(DomesticCities.all()).extracting(Destination::name).containsExactlyInAnyOrderElementsOf(listed);
    }

    @Test
    @DisplayName("해외 여행지와 이름이 겹치지 않아 도시 판정이 해외 목록을 먼저 봐도 국내 도시를 잃지 않는다")
    void noOverlapWithOverseas() {
        Destinations overseas = Destinations.fromClasspath();

        assertThat(DomesticCities.all()).allSatisfy(d -> {
            assertThat(overseas.resolve(d.name())).as(d.name()).isEmpty();
            assertThat(overseas.resolve(d.nameKo())).as(d.nameKo()).isEmpty();
        });
    }

    @Test
    @DisplayName("관문 이름은 도시 이름이 들어 있으면 그 도시, 아니면 아는 공항·역 위치로 찾는다")
    void hubPositions() {
        Destination busan = DomesticCities.find("Busan").orElseThrow();
        Destination jeju = DomesticCities.find("Jeju").orElseThrow();
        Destination daegu = DomesticCities.find("Daegu").orElseThrow();
        assertThat(DomesticCities.hubPosition("Busan Station")).hasValueSatisfying(
                at -> assertThat(at).containsExactly(busan.lat(), busan.lng()));
        assertThat(DomesticCities.hubPosition("제주국제공항")).hasValueSatisfying(
                at -> assertThat(at).containsExactly(jeju.lat(), jeju.lng()));
        assertThat(DomesticCities.hubPosition("Dongdaegu Station")).hasValueSatisfying(
                at -> assertThat(at).containsExactly(daegu.lat(), daegu.lng()));
        // 서울 중심(경도 127.0)이 아니라 김포공항(126.79) 위치다.
        assertThat(DomesticCities.hubPosition("Gimpo International Airport")).hasValueSatisfying(
                at -> assertThat(at[1]).isLessThan(126.85));
        // 대구 수성은 수서역이 아니고, 경기 광주역은 전라 광주가 아니다.
        assertThat(DomesticCities.hubPosition("Suseong Station")).isEmpty();
        assertThat(DomesticCities.hubPosition("경기광주역")).hasValueSatisfying(at -> assertThat(at[0]).isGreaterThan(37));
        assertThat(DomesticCities.hubPosition("어딘가 터미널")).isEmpty();
        assertThat(DomesticCities.hubPosition(null)).isEmpty();
        DomesticCities.hubPosition("Gimpo").orElseThrow()[0] = 0;
        assertThat(DomesticCities.hubPosition("Gimpo")).hasValueSatisfying(at -> assertThat(at[0]).isPositive());
    }

    @Test
    @DisplayName("영어·한국어 이름으로 찾고, 제주만 섬이다")
    void findsByEitherNameAndKnowsIsland() {
        assertThat(DomesticCities.find("경주")).map(Destination::name).contains("Gyeongju");
        assertThat(DomesticCities.find(" Busan ")).map(Destination::nameKo).contains("부산");
        assertThat(DomesticCities.find("Osaka")).isEmpty();
        assertThat(DomesticCities.all()).filteredOn(d -> DomesticCities.isIsland(d.name()))
                .extracting(Destination::name).containsExactly("Jeju");
    }
}
