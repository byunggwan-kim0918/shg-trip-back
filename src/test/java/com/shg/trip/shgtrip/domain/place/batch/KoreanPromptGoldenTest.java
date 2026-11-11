package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 한국 장소 보강 프롬프트 고정본. 바꾸면 이미 보강된 한국 장소와 새 장소의 태그·설명 기준이 갈린다. */
class KoreanPromptGoldenTest {

    private final BatchEnrichScheduler scheduler = new BatchEnrichScheduler(null, new ObjectMapper(), null);

    private static String golden(String name) throws Exception {
        try (var in = new ClassPathResource("enrich-prompt/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("한국 관광지 프롬프트는 고정본과 같다")
    void attraction() throws Exception {
        Place place = Place.builder().id(1L).name("성산일출봉").address("제주 서귀포시")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .category("Landmarks and Outdoors > Mountain").region("Jeju").country("KR").description("화산").build();

        assertThat(scheduler.buildEnrichPrompt(place)).isEqualTo(golden("kr-attraction.txt"));
    }

    @Test
    @DisplayName("한국 식당 프롬프트는 고정본과 같다")
    void dining() throws Exception {
        Place place = Place.builder().id(2L).name("한본새").address("서울 중구")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .category("Dining and Drinking > Restaurant").region("Seoul").country("KR").build();

        assertThat(scheduler.buildEnrichPrompt(place)).isEqualTo(golden("kr-dining.txt"));
    }

    private Place dining(String name, String category, Integer sitelinks) {
        Place place = Place.builder().id(3L).name(name).address("서울 중구")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .category(category).region("Seoul").country("KR").description("기존 설명").build();
        if (sitelinks != null) place.markWikidataMatch("Q1", sitelinks);
        return place;
    }

    @Test
    @DisplayName("유명 식당·카페·술집 프롬프트와 국가 없는 관광지 프롬프트도 고정본과 같다")
    void otherVariants() throws Exception {
        assertThat(scheduler.buildEnrichPrompt(dining("하동관", "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant", 3)))
                .isEqualTo(golden("kr-dining-famous.txt"));
        assertThat(scheduler.buildEnrichPrompt(dining("테라로사", "Dining and Drinking > Cafe, Coffee, and Tea House > Café", null)))
                .isEqualTo(golden("kr-cafe.txt"));
        assertThat(scheduler.buildEnrichPrompt(dining("을지로 포차", "Dining and Drinking > Bar > Pub", null)))
                .isEqualTo(golden("kr-bar.txt"));
        Place noCountry = Place.builder().id(4L).name("이름만 있는 곳").address("주소")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).category("Landmarks and Outdoors > Park").build();
        assertThat(scheduler.buildEnrichPrompt(noCountry)).isEqualTo(golden("kr-no-country.txt"));
    }

    @Test
    @DisplayName("한국 식당 태그 해석은 해외 지역명을 지우지 않는다(쌀국수집의 베트남, 양꼬치집의 칭다오)")
    void koreanTagsKeepOverseasWords() {
        assertThat(DiningEnrichment.finalizeTags(DiningEnrichment.Venue.MEAL, List.of("쌀국수", "베트남", "면요리")))
                .containsExactly("식당", "쌀국수", "베트남", "면요리");
        assertThat(DiningEnrichment.finalizeTags(DiningEnrichment.Venue.BAR, List.of("양꼬치", "칭다오", "하얼빈")))
                .containsExactly("술집", "양꼬치", "칭다오", "하얼빈");
        assertThat(DiningEnrichment.finalizeTags(DiningEnrichment.Venue.MEAL, List.of("라멘", "도쿄", "일본"),
                java.util.Set.of("도쿄", "일본"))).containsExactly("식당", "라멘");
    }
}
