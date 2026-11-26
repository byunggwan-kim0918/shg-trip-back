package com.shg.trip.shgtrip.domain.place.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanLabelContractTest {

    /** place-pipeline(Python) names.korean_label·wikidata_korean_label과 같이 쓰는 계약. 한쪽만 바뀌면 적재 경로마다 다른 한국어 이름이 들어간다. */
    static Stream<String[]> contract() throws IOException {
        try (InputStream in = KoreanLabelContractTest.class.getResourceAsStream("/place-names/korean-labels.tsv")) {
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .map(l -> l.split("\t", -1))
                    .toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("contract")
    @DisplayName("한국어 표시 이름 검사는 공용 계약을 따른다")
    void nameKoFollowsContract(String kind, String value, String original, String expected) {
        String got = "wikidata".equals(kind) ? WikidataSitelinksJob.koreanLabel(value, original)
                : OverseasEnrichment.nameKoText(value, original);
        assertThat(got).isEqualTo(expected.isEmpty() ? null : expected);
    }
}
