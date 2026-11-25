package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OverseasEnrichmentTest {

    @Test
    @DisplayName("여행지 목록의 모든 나라에 한국어 국가명과 현지 통화가 있다")
    void everyDestinationCountryCovered() {
        assertThat(Destinations.fromClasspath().all()).allSatisfy(d -> {
            assertThat(OverseasEnrichment.COUNTRIES).containsKey(d.country());
        });
        assertThat(OverseasEnrichment.isOverseas("JP")).isTrue();
        assertThat(OverseasEnrichment.isOverseas("KR")).isFalse();
        assertThat(OverseasEnrichment.isOverseas("일본")).isFalse();
        // 보강이 받는 통화마다 원화 환율이 있어야 입장료가 일정 비용에 들어간다
        assertThat(OverseasEnrichment.COUNTRIES.values()).allSatisfy(c ->
                assertThat(com.shg.trip.shgtrip.domain.place.destination.KrwRates.toWon(1_000, c.currency().code()))
                        .as(c.currency().code()).isNotNull());
    }

    @Test
    @DisplayName("프롬프트 장소 정보에 국가, 한국어 여행지 이름, 주소가 들어간다")
    void contextLines() {
        Place place = Place.builder().name("清水寺").address("京都府京都市東山区清水1丁目294")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).category("Temple")
                .region("Kyoto").country("JP").build();

        assertThat(OverseasEnrichment.contextLines(place))
                .contains("- 국가: 일본", "- 여행지: 교토 (Kyoto)", "- 주소: 京都府京都市東山区清水1丁目294");
    }

    @Test
    @DisplayName("태그에서 지울 지역명은 그 장소의 나라·여행지 이름이다")
    void ownRegionNames() {
        Place place = Place.builder().name("x").address("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .category("x").region("Kyoto").country("JP").build();

        assertThat(OverseasEnrichment.ownRegionNames(place)).containsExactlyInAnyOrder("일본", "japan", "교토", "kyoto");
    }

    @Test
    @DisplayName("한국어 이름만 받는다: 괄호 병기는 떼고, 한자·가나가 남거나 이름이 아닌 말·자리표시자는 버린다")
    void nameKoValidation() {
        assertThat(OverseasEnrichment.nameKo(new TextNode(" 기요미즈데라 "), null)).isEqualTo("기요미즈데라");
        assertThat(OverseasEnrichment.nameKo(new TextNode("\"스린 야시장\""), null)).isEqualTo("스린 야시장");
        assertThat(OverseasEnrichment.nameKo(new TextNode("센소지(浅草寺)"), null)).isEqualTo("센소지");
        assertThat(OverseasEnrichment.nameKo(new TextNode("타이베이 101"), null)).isEqualTo("타이베이 101");
        assertThat(OverseasEnrichment.nameKo(new TextNode("센소지 浅草寺"), null)).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("清水寺"), null)).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("한국어 이름"), null)).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("없음"), null)).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("알 수 없음"), null)).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("..."), null)).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("가".repeat(61)), null)).isNull();
        assertThat(OverseasEnrichment.nameKo(null, null)).isNull();
    }

    private static String nameKo(String modelName, String originalName) {
        return OverseasEnrichment.nameKo(new TextNode(modelName), originalName);
    }

    @Test
    @DisplayName("원문에 없는 외국 글자가 섞인 이름은 버린다(모델 출력이 깨진 것이다)")
    void nameKoRejectsCorruptedLetters() {
        assertThat(nameKo("로ккō 하치만 신사", "六甲八幡神社")).isNull();
        assertThat(nameKo("고마쓰안 소honke", "小松庵総本家")).isNull();
        assertThat(nameKo("부processing", "仏眼寺")).isNull();
        assertThat(nameKo("사न", "粲")).isNull();
        assertThat(nameKo("카shō 산젠 마쓰시마 테라마치코지점", "菓匠三全 松島寺町小路店")).isNull();
        assertThat(nameKo("joban 온천", "常磐湯")).isNull();
    }

    @Test
    @DisplayName("원문에 있는 로마자·기호와 가타카나 약어는 이름의 일부로 받는다")
    void nameKoKeepsLettersFromOriginal() {
        assertThat(nameKo("PokéPark 간토", "PokéPark Kanto")).isEqualTo("PokéPark 간토");
        assertThat(nameKo("ØL 라 카비나", "ØL La Cabina")).isEqualTo("ØL 라 카비나");
        assertThat(nameKo("스튜디오 π 유리 공방", "Studio π glass factory")).isEqualTo("스튜디오 π 유리 공방");
        assertThat(nameKo("JA 교류광장", "ＪＡ交流ひろば")).isEqualTo("JA 교류광장");
        assertThat(nameKo("DI 아사히카와", "ディーアイあさひかわ")).isEqualTo("DI 아사히카와");
        assertThat(nameKo("골프5 고쿠라 히가시 IC점", "ゴルフ５小倉東インター店")).isEqualTo("골프5 고쿠라 히가시 IC점");
        assertThat(nameKo("덴진 주오 공원", "天神中央公園")).isEqualTo("덴진 주오 공원");
    }

    @Test
    @DisplayName("이름 칸에 붙은 설명(줄표·쌍점)은 원문에 그런 기호가 없으면 버린다")
    void nameKoRejectsRemarks() {
        assertThat(nameKo("본점 아님 — 본문: 범승도도점", "凡僧都島店")).isNull();
        assertThat(nameKo("카:로노타키", "甘露の滝")).isNull();
        assertThat(nameKo("워너 브라더스 스튜디오 투어 도쿄 – 메이킹 오브 해리 포터",
                "ワーナー ブラザース スタジオツアー東京 - メイキング・オブ・ハリー・ポッター"))
                .isEqualTo("워너 브라더스 스튜디오 투어 도쿄 – 메이킹 오브 해리 포터");
    }

    @Test
    @DisplayName("원문 괄호를 옮긴 한글 괄호는 부속 시설 이름이라 남기고, 모델이 붙인 구분 괄호는 뗀다")
    void nameKoKeepsTranslatedBracketOfOriginal() {
        assertThat(nameKo("이모세노타키 (오타키)", "妹背の滝（雄滝）")).isEqualTo("이모세노타키 오타키");
        assertThat(nameKo("나카노 역 (도쿄 도)", "中野駅")).isEqualTo("나카노 역");
        assertThat(nameKo("센소지(浅草寺)", "浅草寺 (Senso-ji)")).isEqualTo("센소지");
    }

    @Test
    @DisplayName("알려진 정도는 0~2 정수만 받는다")
    void fame() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(EnrichOutputs.fame(om.readTree("2"))).isEqualTo((short) 2);
        assertThat(EnrichOutputs.fame(om.readTree("0"))).isEqualTo((short) 0);
        assertThat(EnrichOutputs.fame(om.readTree("3"))).isNull();
        assertThat(EnrichOutputs.fame(om.readTree("-1"))).isNull();
        assertThat(EnrichOutputs.fame(om.readTree("1.5"))).isNull();
        assertThat(EnrichOutputs.fame(om.readTree("\"2\""))).isNull();
        assertThat(EnrichOutputs.fame(null)).isNull();
    }

    @Test
    @DisplayName("현지 통화 입장료는 그 통화로 있을 수 있는 금액만 받는다(0은 무료)")
    void admissionLocal() throws Exception {
        ObjectMapper om = new ObjectMapper();
        var jpy = OverseasEnrichment.currencyOf("JP");
        var vnd = OverseasEnrichment.currencyOf("VN");
        assertThat(OverseasEnrichment.admissionLocal(om.readTree("400"), jpy)).isEqualTo(400);
        assertThat(OverseasEnrichment.admissionLocal(om.readTree("0"), jpy)).isZero();
        assertThat(OverseasEnrichment.admissionLocal(om.readTree("-1"), jpy)).isNull();
        assertThat(OverseasEnrichment.admissionLocal(om.readTree("\"무료\""), jpy)).isNull();
        assertThat(OverseasEnrichment.admissionLocal(null, jpy)).isNull();
        // 천 단위로 줄여 적은 동, 원화로 바꿔 적은 엔은 버린다
        assertThat(OverseasEnrichment.admissionLocal(om.readTree("40"), vnd)).isNull();
        assertThat(OverseasEnrichment.admissionLocal(om.readTree("40000"), vnd)).isEqualTo(40000);
        assertThat(OverseasEnrichment.admissionLocal(om.readTree("150000"), jpy)).isNull();
    }

    @Test
    @DisplayName("해외 요리 이름 태그로 음식 종류를 정하고, 없으면 한국 규칙을 쓴다")
    void cuisineFromDishes() {
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "돈코츠라멘", "일식"), null)).isEqualTo("NOODLE");
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "훠궈", "중식"), null)).isEqualTo("SOUP");
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "쌀국수"), null)).isEqualTo("NOODLE");
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "야키니쿠"), null)).isEqualTo("MEAT");
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "스시", "일식"), null)).isEqualTo("SEAFOOD");
        // "회"는 태그 전체가 같을 때만 본다("회전초밥"은 초밥으로 해산물)
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "회전초밥"), null)).isEqualTo("SEAFOOD");
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "회관"), null)).isEqualTo("OTHER");
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당", "딤섬", "중식"), null)).isEqualTo("CHINESE");
        assertThat(OverseasEnrichment.cuisineOf(List.of("식당"), "Dining and Drinking > Restaurant > Ramen Restaurant"))
                .isEqualTo("NOODLE");
    }
}
