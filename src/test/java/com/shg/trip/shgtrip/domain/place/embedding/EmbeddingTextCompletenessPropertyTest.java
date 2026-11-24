package com.shg.trip.shgtrip.domain.place.embedding;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test: 임베딩 텍스트 완전성. 이름·카테고리·태그·설명이 있으면 문장에 모두 들어가고, 지역은 들어가지 않는다.
 */
class EmbeddingTextCompletenessPropertyTest {

    @Property(tries = 100)
    void buildTextContainsDescriptiveFieldsButNotRegion(
            @ForAll("validNames") String name,
            @ForAll("validCategories") String category,
            @ForAll("validTagLists") List<String> tags,
            @ForAll("validDescriptions") String description,
            @ForAll("validRegions") String region
    ) {
        Place place = Place.builder()
                .name(name)
                .address("테스트 주소")
                .latitude(BigDecimal.valueOf(35.6762))
                .longitude(BigDecimal.valueOf(139.6503))
                .category(category)
                .tags(tags)
                .description(description)
                .region(region)
                .savedAt(OffsetDateTime.now())
                .build();

        String result = EmbeddingTextBuilder.buildText(place);

        assertThat(result).contains(name, category, description);
        for (String tag : tags) assertThat(result).contains(tag);
        assertThat(result).doesNotContain(region);
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<String> validNames() {
        return Arbitraries.of(
                "센소지", "메이지신궁", "도쿄타워", "에펠탑", "콜로세움",
                "경복궁", "남산타워", "빅벤", "자유의여신상", "만리장성"
        );
    }

    @Provide
    Arbitrary<String> validCategories() {
        return Arbitraries.of(
                "관광", "음식", "쇼핑", "숙박", "카페", "액티비티", "문화", "자연"
        );
    }

    @Provide
    Arbitrary<List<String>> validTagLists() {
        return Arbitraries.of(
                        "역사", "사찰", "전망대", "랜드마크", "맛집",
                        "쇼핑몰", "해변", "공원", "미술관", "야경"
                )
                .list()
                .ofMinSize(1)
                .ofMaxSize(5)
                .uniqueElements();
    }

    @Provide
    Arbitrary<String> validDescriptions() {
        return Arbitraries.of(
                "도쿄의 가장 오래된 불교 사원",
                "일본의 대표적인 신사",
                "파리의 상징적인 철탑",
                "고대 로마의 원형 경기장",
                "조선시대 대표 궁궐",
                "서울의 야경 명소",
                "런던의 대표적인 시계탑",
                "뉴욕 항구의 상징"
        );
    }

    @Provide
    Arbitrary<String> validRegions() {
        return Arbitraries.of(
                "아사쿠사", "시부야", "하라주쿠", "긴자", "종로",
                "강남", "마레", "웨스트민스터", "맨해튼", "콜로세오"
        );
    }
}
