package com.shg.trip.shgtrip.domain.place.embedding;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingTextBuilderTest {

    private static Place place(String name, String nameKo, String category, List<String> tags, String description) {
        return Place.builder().name(name).nameKo(nameKo).category(category).tags(tags).description(description)
                .region("Seoul").build();
    }

    @Test
    @DisplayName("이름·카테고리 마지막 단계·태그·설명을 한국어 문장으로 잇고, 지역과 카테고리 대분류는 넣지 않는다")
    void sentence() {
        Place p = place("순수박물관", null, "Arts and Entertainment > Art Gallery", List.of("미술관", "전시", "예술"),
                "현대 미술 작품을 전시하는 공간입니다.");

        assertThat(EmbeddingTextBuilder.buildText(p)).isEqualTo(
                "순수박물관은 Art Gallery입니다. 미술관, 전시, 예술과 관련된 곳입니다. 현대 미술 작품을 전시하는 공간입니다.");
    }

    @Test
    @DisplayName("조사는 마지막 글자의 받침으로 고른다")
    void particles() {
        Place p = place("어국 스시", null, "Dining and Drinking > Restaurant > Sushi Restaurant", List.of("식당", "일식", "회"),
                null);

        assertThat(EmbeddingTextBuilder.buildText(p)).isEqualTo("어국 스시는 Sushi Restaurant입니다. 식당, 일식, 회와 관련된 곳입니다.");
    }

    @Test
    @DisplayName("마지막 글자가 한글이 아니면 두 조사를 함께 쓴다")
    void nonHangulParticles() {
        Place p = place("Spao", null, "Retail > Fashion Retail", List.of("의류", "SPA"), null);

        assertThat(EmbeddingTextBuilder.buildText(p)).isEqualTo("Spao은(는) Fashion Retail입니다. 의류, SPA과(와) 관련된 곳입니다.");
    }

    @Test
    @DisplayName("해외 장소는 한국어 이름을 앞에, 원문 이름을 괄호에 둔다. 같은 이름이면 한 번만 쓴다")
    void koreanNameFirst() {
        assertThat(EmbeddingTextBuilder.buildText(place("浅草寺", "센소지", "Community and Government > Buddhist Temple",
                null, null))).isEqualTo("센소지(浅草寺)는 Buddhist Temple입니다.");
        assertThat(EmbeddingTextBuilder.buildText(place("경복궁", "경복궁", "Landmarks and Outdoors > Palace", null, null)))
                .isEqualTo("경복궁은 Palace입니다.");
    }

    @Test
    @DisplayName("카테고리·태그·설명이 없으면 그 문장을 빼고, 태그의 null·빈 값은 건너뛴다")
    void missingParts() {
        assertThat(EmbeddingTextBuilder.buildText(place("테스트장소", null, null, null, null))).isEqualTo("테스트장소입니다.");
        assertThat(EmbeddingTextBuilder.buildText(place("장소", null, "관광", Arrays.asList(null, " ", "유효"), "  ")))
                .isEqualTo("장소는 관광입니다. 유효와 관련된 곳입니다.");
    }

    @Test
    @DisplayName("장소 자신의 지역·나라 이름 태그는 임베딩 글에서만 빼고, 지역 이름이 든 다른 태그와 DB 태그는 그대로 둔다")
    void ownAreaTagsDropped() {
        Place seoul = Place.builder().name("순수박물관").category("Arts and Entertainment > Art Gallery")
                .tags(List.of("미술관", "서울", "서울시", "한국", "서울야경")).region("Seoul").country("KR").build();
        Place tokyo = Place.builder().name("浅草寺").nameKo("센소지").category("Buddhist Temple")
                .tags(List.of("사찰", "도쿄", "Tokyo", "일본")).region("Tokyo").country("JP").build();

        assertThat(EmbeddingTextBuilder.buildText(seoul)).isEqualTo("순수박물관은 Art Gallery입니다. 미술관, 서울야경과 관련된 곳입니다.");
        assertThat(seoul.getTags()).contains("서울");
        assertThat(EmbeddingTextBuilder.buildText(tokyo)).isEqualTo("센소지(浅草寺)는 Buddhist Temple입니다. 사찰과 관련된 곳입니다.");
    }

    @Test
    @DisplayName("null 장소나 빈 이름은 IllegalArgumentException")
    void invalid() {
        assertThatThrownBy(() -> EmbeddingTextBuilder.buildText(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingTextBuilder.buildText(place(null, null, "관광", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingTextBuilder.buildText(place("  ", null, "관광", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
