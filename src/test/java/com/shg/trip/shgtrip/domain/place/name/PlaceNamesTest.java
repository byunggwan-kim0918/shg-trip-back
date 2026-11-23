package com.shg.trip.shgtrip.domain.place.name;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceNamesTest {

    /** place-pipeline(Python)·DB 함수와 같이 쓰는 계약. 한쪽만 바뀌면 같은 이름이 경로마다 다르게 잡힌다. */
    static Stream<String[]> contract() throws IOException {
        try (InputStream in = PlaceNamesTest.class.getResourceAsStream("/place-names/exact-keys.tsv")) {
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .map(l -> l.split("\t", -1))
                    .toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("contract")
    @DisplayName("정확 키는 공용 계약을 따른다")
    void exactKeyFollowsContract(String raw, String expected) {
        assertThat(PlaceNames.exactKey(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("괄호 설명은 bareKey에서 빠지고, 괄호 안 한글 이름은 별칭으로 남는다")
    void bracketsAndAliases() {
        assertThat(PlaceNames.bareKey("불국사 (佛國寺)")).isEqualTo("불국사");
        assertThat(PlaceNames.bareKey("(주)")).isEqualTo("주");
        assertThat(PlaceNames.bracketAliasKeys("BIG-O쇼 (빅오쇼)")).isEmpty();
        assertThat(PlaceNames.bracketAliasKeys("Cath Kidston ( 원마운트)")).isEmpty();
        assertThat(PlaceNames.bracketAliasKeys("월정리해변(월정리해수욕장)")).containsExactly("월정리해수욕장");
        assertThat(PlaceNames.bracketAliasKeys("불국사 (佛國寺)")).isEmpty();
        assertThat(PlaceNames.bracketAliasKeys("용지봉 (634m)")).isEmpty();
        assertThat(PlaceNames.bracketAliasKeys("천마총(대릉원)")).isEmpty();
        assertThat(PlaceNames.bracketAliasKeys("박가 부대찌게(동성로)")).isEmpty();
        assertThat(PlaceNames.bracketAliasKeys("용연사(강릉)")).isEmpty();
    }

    @Test
    @DisplayName("끝 괄호 설명과 영어 병기를 뗀다")
    void trailingNotes() {
        assertThat(PlaceNames.withoutTrailingNote("용지봉 (634m)")).isEqualTo("용지봉");
        assertThat(PlaceNames.withoutLatinTail("해동용궁사 Haedong Yonggungsa")).isEqualTo("해동용궁사");
        assertThat(PlaceNames.withoutLatinTail("Times Square")).isEqualTo("Times Square");
        assertThat(PlaceNames.isBilingualOf("해동용궁사 Haedong Yonggungsa Temple", "해동용궁사")).isTrue();
        assertThat(PlaceNames.isBilingualOf("명동교자 2호점", "명동교자")).isFalse();
    }

    @Test
    @DisplayName("해변과 해수욕장, 일본 지명 표기는 같은 곳의 이름이다")
    void variants() {
        assertThat(PlaceNames.variants("경포해변")).containsExactly("경포해변", "경포해수욕장");
        assertThat(PlaceNames.variants("츠키지 혼간지")).contains("쓰키지 혼간지");
    }

    @Test
    @DisplayName("일본어 표기 변형은 일본 여행지에서만 만들고, 받은 이름과 기본 변형은 넣지 않는다")
    void spellingVariants() {
        assertThat(PlaceNames.spellingVariants("코묘젠지", true)).contains("고묘젠지");
        assertThat(PlaceNames.spellingVariants("조조지", true)).contains("조죠지");
        assertThat(PlaceNames.spellingVariants("조죠지", true)).contains("조조지");
        assertThat(PlaceNames.spellingVariants("타누키코지 상점가", true)).contains("다누키코지 상점가");
        assertThat(PlaceNames.spellingVariants("코묘젠지", false)).isEmpty();
        assertThat(PlaceNames.spellingVariants("행궁동 벽화골목", false))
                .containsExactly("행궁동 벽화마을", "행궁동 벽화거리");
        assertThat(PlaceNames.spellingVariants("사직공원 전망타워", false)).containsExactly("사직공원 전망대");
        assertThat(PlaceNames.spellingVariants("코묘젠지", true)).doesNotContain("코묘젠지");
    }

    @Test
    @DisplayName("음식 이름은 낱말 중간에서 시작해 다음 낱말로 넘어가면 맞지 않는다")
    void mentions() {
        assertThat(PlaceNames.mentions("스미요시 라멘 타이코", "멘타이코")).isFalse();
        assertThat(PlaceNames.mentions("멘타이코 덮밥", "멘타이코")).isTrue();
        assertThat(PlaceNames.mentions("원조 멘타이코덮밥", "멘타이코")).isTrue();
        assertThat(PlaceNames.mentions("조점례남문피순대", "피순대")).isTrue();
        assertThat(PlaceNames.mentions("모슬포 고등어 회", "고등어회")).isTrue();
        assertThat(PlaceNames.mentions(null, "멘타이코")).isFalse();
    }

}
