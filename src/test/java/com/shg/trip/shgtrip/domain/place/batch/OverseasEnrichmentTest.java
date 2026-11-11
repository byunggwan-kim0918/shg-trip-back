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
        assertThat(OverseasEnrichment.nameKo(new TextNode(" 기요미즈데라 "))).isEqualTo("기요미즈데라");
        assertThat(OverseasEnrichment.nameKo(new TextNode("\"스린 야시장\""))).isEqualTo("스린 야시장");
        assertThat(OverseasEnrichment.nameKo(new TextNode("센소지(浅草寺)"))).isEqualTo("센소지");
        assertThat(OverseasEnrichment.nameKo(new TextNode("타이베이 101"))).isEqualTo("타이베이 101");
        assertThat(OverseasEnrichment.nameKo(new TextNode("센소지 浅草寺"))).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("清水寺"))).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("한국어 이름"))).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("없음"))).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("알 수 없음"))).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("..."))).isNull();
        assertThat(OverseasEnrichment.nameKo(new TextNode("가".repeat(61)))).isNull();
        assertThat(OverseasEnrichment.nameKo(null)).isNull();
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
