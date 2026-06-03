package com.shg.trip.shgtrip.domain.place.embedding;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test: 임베딩 텍스트 결합 완전성.
 * <p>
 * 장소 데이터에서 name, category, tags, description, region이 모두 존재(non-null, non-empty)할 때,
 * 결합된 임베딩 텍스트는 5개 필드의 내용을 모두 포함해야 한다.
 */
// Feature: llm-optimization, Property 5: 임베딩 텍스트 결합 완전성
class EmbeddingTextCompletenessPropertyTest {

    @Property(tries = 100)
    // Feature: llm-optimization, Property 5: 임베딩 텍스트 결합 완전성
    void buildTextFromPlaceContainsAllFiveFields(
            @ForAll("validNames") String name,
            @ForAll("validCategories") String category,
            @ForAll("validTagLists") List<String> tags,
            @ForAll("validDescriptions") String description,
            @ForAll("validRegions") String region
    ) {
        // Given: 모든 5개 필드가 non-null, non-empty인 Place
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

        // When: 임베딩 텍스트 생성
        String result = EmbeddingTextBuilder.buildText(place);

        // Then: 결합된 텍스트에 5개 필드 내용이 모두 포함되어야 한다
        assertThat(result)
                .as("결합 텍스트에 name '%s'이 포함되어야 한다", name)
                .contains(name);
        assertThat(result)
                .as("결합 텍스트에 category '%s'가 포함되어야 한다", category)
                .contains(category);
        for (String tag : tags) {
            assertThat(result)
                    .as("결합 텍스트에 tag '%s'가 포함되어야 한다", tag)
                    .contains(tag);
        }
        assertThat(result)
                .as("결합 텍스트에 description '%s'이 포함되어야 한다", description)
                .contains(description);
        assertThat(result)
                .as("결합 텍스트에 region '%s'이 포함되어야 한다", region)
                .contains(region);
    }

    @Property(tries = 100)
    // Feature: llm-optimization, Property 5: 임베딩 텍스트 결합 완전성
    void buildTextFromFieldsContainsAllFiveFields(
            @ForAll("validNames") String name,
            @ForAll("validCategories") String category,
            @ForAll("validTagLists") List<String> tags,
            @ForAll("validDescriptions") String description,
            @ForAll("validRegions") String region
    ) {
        // When: 개별 필드 기반 임베딩 텍스트 생성
        String result = EmbeddingTextBuilder.buildText(name, category, tags, description, region);

        // Then: 결합된 텍스트에 5개 필드 내용이 모두 포함되어야 한다
        assertThat(result)
                .as("결합 텍스트에 name '%s'이 포함되어야 한다", name)
                .contains(name);
        assertThat(result)
                .as("결합 텍스트에 category '%s'가 포함되어야 한다", category)
                .contains(category);
        for (String tag : tags) {
            assertThat(result)
                    .as("결합 텍스트에 tag '%s'가 포함되어야 한다", tag)
                    .contains(tag);
        }
        assertThat(result)
                .as("결합 텍스트에 description '%s'이 포함되어야 한다", description)
                .contains(description);
        assertThat(result)
                .as("결합 텍스트에 region '%s'이 포함되어야 한다", region)
                .contains(region);
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
