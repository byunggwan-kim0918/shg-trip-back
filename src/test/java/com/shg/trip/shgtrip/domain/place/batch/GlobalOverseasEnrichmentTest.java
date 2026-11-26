package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 국가 규칙 표에 없는 해외 국가도 해외 보강 규칙을 타고, 표에 있는 나라 규칙은 그대로인지 본다. */
class GlobalOverseasEnrichmentTest {

    private static Place place(String name, String category, String country) {
        return Place.builder().id(7L).name(name).address("158 Thanon Wang Doem, Bangkok").category(category)
                .region("Bangkok").country(country).latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build();
    }

    @Test
    @DisplayName("한국이 아닌 나라는 표에 없어도 해외다. 국가가 비면 국내 규칙이다")
    void everyNonKoreanCountryIsOverseas() {
        assertThat(OverseasEnrichment.isOverseas("TH")).isTrue();
        assertThat(OverseasEnrichment.isOverseas("FR")).isTrue();
        assertThat(OverseasEnrichment.isOverseas("JP")).isTrue();
        assertThat(OverseasEnrichment.isOverseas("KR")).isFalse();
        assertThat(OverseasEnrichment.isOverseas(null)).isFalse();
        assertThat(OverseasEnrichment.isOverseas(" ")).isFalse();
        assertThat(OverseasEnrichment.isOverseas("일본")).isFalse();
        assertThat(OverseasEnrichment.isOverseas("jp")).isFalse();
    }

    @Test
    @DisplayName("표에 없는 나라 이름은 ISO 국가 데이터에서 읽고, 입장료 금액 범위는 모른다")
    void unlistedCountryUsesIsoNames() {
        OverseasEnrichment.Country thailand = OverseasEnrichment.countryOf("TH");
        assertThat(thailand.nameKo()).isEqualTo("태국");
        assertThat(thailand.nameEn()).isEqualTo("thailand");
        assertThat(thailand.currency()).isNull();
        assertThat(OverseasEnrichment.countryOf("JP").currency().code()).isEqualTo("JPY");
        assertThat(OverseasEnrichment.countryOf("X1").nameKo()).isEqualTo("X1");
    }

    @Test
    @DisplayName("표에 없는 나라 관광지는 해외 프롬프트(국가·한국어 이름 규칙)를 받고 입장료는 묻지 않는다")
    void unlistedCountryAttractionPrompt() {
        EnrichPrompt prompt = PlaceEnrichment.prompt(place("Wat Arun", "Landmarks and Outdoors > Temple", "TH"));

        assertThat(prompt.user()).contains("다음 해외 장소").contains("- 국가: 태국").contains("- 주소: 158 Thanon");
        assertThat(prompt.system()).contains("\"name_ko\"").contains("국립국어원 외래어 표기법")
                .doesNotContain("admission_fee_local").doesNotContain("입장료 가이드");
        EnrichPrompt japan = PlaceEnrichment.prompt(place("金閣寺", "Landmarks and Outdoors > Temple", "JP"));
        assertThat(japan.system()).contains("admission_fee_local").contains("엔 단위");
    }

    @Test
    @DisplayName("표에 없는 나라 식당도 해외 문맥과 한국어 이름을 묻는다")
    void unlistedCountryDiningPrompt() {
        EnrichPrompt prompt = PlaceEnrichment.prompt(place("Jay Fai", "Dining and Drinking > Restaurant > Thai Restaurant", "TH"));

        assertThat(prompt.user() + prompt.system()).contains("국가: 태국").contains("name_ko");
    }

    @Test
    @DisplayName("표에 없는 나라 응답은 한국어 이름을 받고, 범위를 모르는 현지 통화 입장료는 저장하지 않는다")
    void unlistedCountryApply() {
        Place watArun = place("Wat Arun", "Landmarks and Outdoors > Temple", "TH");
        String response = """
                {"name_ko":"왓 아룬","tags":["사원","강변"],"description":"짜오프라야강 서쪽 강변의 불교 사원이다.",
                 "place_role":"SIGHT","admission_fee_local":200,"traveler_fame":2}""";

        assertThat(PlaceEnrichment.apply(new ObjectMapper(), watArun, response)).isTrue();

        assertThat(watArun.getNameKo()).isEqualTo("왓 아룬");
        assertThat(watArun.getAdmissionFeeLocal()).isNull();
        assertThat(watArun.getAdmissionCurrency()).isNull();
        assertThat(watArun.getPlaceRole()).isEqualTo("SIGHT");
    }

    @Test
    @DisplayName("식당 태그에서 지우는 자기 나라 이름은 표에 없는 나라도 ISO 이름으로 지운다")
    void ownRegionNamesForUnlistedCountry() {
        assertThat(OverseasEnrichment.ownRegionNames(place("Jay Fai", "Dining and Drinking > Restaurant", "TH")))
                .contains("태국", "thailand");
    }

    @Test
    @DisplayName("국내 행에 섞인 한자를 막는 검사는 해외 설명에 걸지 않는다")
    void mixedScriptCheckIsDomesticOnly() {
        assertThat(EnrichOutputs.rejectsDescription(7L, "Wat Arun", OverseasEnrichment.isOverseas("TH"),
                "鄭王廟라고도 부르는 사원이다")).isFalse();
        assertThat(EnrichOutputs.rejectsDescription(7L, "어느 절", OverseasEnrichment.isOverseas("KR"),
                "가족과 즐기기에 适합니다")).isTrue();
    }
}
