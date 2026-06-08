package com.shg.trip.shgtrip.domain.place.embedding;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingTextBuilderTest {

    @Nested
    @DisplayName("buildText(Place) - Place 엔티티 기반 텍스트 결합")
    class BuildTextFromPlaceTests {

        @Test
        @DisplayName("모든 필드가 존재하면 name + category + tags + description + region 결합")
        void allFieldsPresent() {
            Place place = Place.builder()
                    .name("센소지")
                    .address("도쿄 아사쿠사")
                    .latitude(BigDecimal.valueOf(35.7148))
                    .longitude(BigDecimal.valueOf(139.7967))
                    .category("관광")
                    .tags(List.of("사찰", "역사", "아사쿠사"))
                    .description("도쿄의 가장 오래된 불교 사원")
                    .region("아사쿠사")
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("센소지 관광 사찰 역사 아사쿠사 도쿄의 가장 오래된 불교 사원 아사쿠사");
        }

        @Test
        @DisplayName("tags가 null이면 건너뛴다")
        void tagsNull() {
            Place place = Place.builder()
                    .name("메이지신궁")
                    .address("도쿄 하라주쿠")
                    .latitude(BigDecimal.valueOf(35.6764))
                    .longitude(BigDecimal.valueOf(139.6993))
                    .category("관광")
                    .tags(null)
                    .description("일본의 대표적인 신사")
                    .region("하라주쿠")
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("메이지신궁 관광 일본의 대표적인 신사 하라주쿠");
        }

        @Test
        @DisplayName("description이 null이면 건너뛴다")
        void descriptionNull() {
            Place place = Place.builder()
                    .name("스크램블교차로")
                    .address("도쿄 시부야")
                    .latitude(BigDecimal.valueOf(35.6595))
                    .longitude(BigDecimal.valueOf(139.7004))
                    .category("관광")
                    .tags(List.of("시부야", "랜드마크"))
                    .description(null)
                    .region("시부야")
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("스크램블교차로 관광 시부야 랜드마크 시부야");
        }

        @Test
        @DisplayName("region이 null이면 건너뛴다")
        void regionNull() {
            Place place = Place.builder()
                    .name("도쿄타워")
                    .address("도쿄 미나토구")
                    .latitude(BigDecimal.valueOf(35.6586))
                    .longitude(BigDecimal.valueOf(139.7454))
                    .category("관광")
                    .tags(List.of("타워", "전망대"))
                    .description("도쿄의 상징적인 타워")
                    .region(null)
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("도쿄타워 관광 타워 전망대 도쿄의 상징적인 타워");
        }

        @Test
        @DisplayName("category가 null이면 건너뛴다")
        void categoryNull() {
            Place place = Place.builder()
                    .name("어떤식당")
                    .address("도쿄 어딘가")
                    .latitude(BigDecimal.valueOf(35.0))
                    .longitude(BigDecimal.valueOf(139.0))
                    .category(null)
                    .tags(List.of("맛집"))
                    .description("좋은 식당")
                    .region("시부야")
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("어떤식당 맛집 좋은 식당 시부야");
        }

        @Test
        @DisplayName("name만 있고 나머지 모두 null이면 name만 반환")
        void onlyName() {
            Place place = Place.builder()
                    .name("테스트장소")
                    .address("주소")
                    .latitude(BigDecimal.ZERO)
                    .longitude(BigDecimal.ZERO)
                    .category(null)
                    .tags(null)
                    .description(null)
                    .region(null)
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("테스트장소");
        }

        @Test
        @DisplayName("빈 tags 리스트는 건너뛴다")
        void emptyTagsList() {
            Place place = Place.builder()
                    .name("카페")
                    .address("주소")
                    .latitude(BigDecimal.ZERO)
                    .longitude(BigDecimal.ZERO)
                    .category("맛집")
                    .tags(List.of())
                    .description("좋은 카페")
                    .region("강남")
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("카페 맛집 좋은 카페 강남");
        }

        @Test
        @DisplayName("tags에 null/빈 값이 섞여있으면 유효한 것만 포함")
        void tagsWithNullAndBlankElements() {
            Place place = Place.builder()
                    .name("장소")
                    .address("주소")
                    .latitude(BigDecimal.ZERO)
                    .longitude(BigDecimal.ZERO)
                    .category("관광")
                    .tags(List.of("유효", "", "  "))
                    .description(null)
                    .region(null)
                    .savedAt(OffsetDateTime.now())
                    .build();

            String result = EmbeddingTextBuilder.buildText(place);

            assertThat(result).isEqualTo("장소 관광 유효");
        }

        @Test
        @DisplayName("null Place에 대해 IllegalArgumentException")
        void nullPlace() {
            assertThatThrownBy(() -> EmbeddingTextBuilder.buildText((Place) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("name이 null인 Place에 대해 IllegalArgumentException")
        void placeWithNullName() {
            Place place = Place.builder()
                    .name(null)
                    .address("주소")
                    .latitude(BigDecimal.ZERO)
                    .longitude(BigDecimal.ZERO)
                    .category("관광")
                    .savedAt(OffsetDateTime.now())
                    .build();

            assertThatThrownBy(() -> EmbeddingTextBuilder.buildText(place))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("name이 빈 문자열인 Place에 대해 IllegalArgumentException")
        void placeWithBlankName() {
            Place place = Place.builder()
                    .name("   ")
                    .address("주소")
                    .latitude(BigDecimal.ZERO)
                    .longitude(BigDecimal.ZERO)
                    .category("관광")
                    .savedAt(OffsetDateTime.now())
                    .build();

            assertThatThrownBy(() -> EmbeddingTextBuilder.buildText(place))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }
    }

    @Nested
    @DisplayName("buildText(fields) - 개별 필드 기반 텍스트 결합")
    class BuildTextFromFieldsTests {

        @Test
        @DisplayName("모든 필드 존재 시 정상 결합")
        void allFieldsPresent() {
            String result = EmbeddingTextBuilder.buildText(
                    "센소지", "관광", List.of("사찰", "역사", "아사쿠사"),
                    "도쿄의 가장 오래된 불교 사원", "아사쿠사");

            assertThat(result).isEqualTo("센소지 관광 사찰 역사 아사쿠사 도쿄의 가장 오래된 불교 사원 아사쿠사");
        }

        @Test
        @DisplayName("null name에 대해 IllegalArgumentException")
        void nullName() {
            assertThatThrownBy(() ->
                    EmbeddingTextBuilder.buildText(null, "관광", List.of("태그"), "설명", "지역"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("빈 name에 대해 IllegalArgumentException")
        void blankName() {
            assertThatThrownBy(() ->
                    EmbeddingTextBuilder.buildText("  ", "관광", List.of("태그"), "설명", "지역"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("optional 필드 모두 null이면 name만 반환")
        void onlyName() {
            String result = EmbeddingTextBuilder.buildText("장소명", null, null, null, null);

            assertThat(result).isEqualTo("장소명");
        }
    }
}
