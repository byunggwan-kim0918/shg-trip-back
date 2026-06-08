package com.shg.trip.shgtrip.domain.place.embedding;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 장소 데이터를 임베딩 생성용 단일 텍스트로 결합하는 유틸리티.
 * 결합 대상 필드: name, category, tags, description, region.
 * null 또는 빈 필드는 건너뛴다.
 */
public final class EmbeddingTextBuilder {

    private EmbeddingTextBuilder() {
        // 유틸리티 클래스 인스턴스화 방지
    }

    /**
     * Place 엔티티의 필드를 결합하여 임베딩 생성용 텍스트를 만든다.
     * 형식: "[name] [category] [tags joined by space] [description] [region]"
     * null이거나 빈 필드는 건너뛴다.
     *
     * @param place 임베딩 텍스트를 생성할 장소 엔티티
     * @return 결합된 임베딩 텍스트
     * @throws IllegalArgumentException place가 null이거나 name이 null/빈 값인 경우
     */
    public static String buildText(Place place) {
        if (place == null) {
            throw new IllegalArgumentException("Place는 null일 수 없습니다.");
        }
        if (place.getName() == null || place.getName().isBlank()) {
            throw new IllegalArgumentException("Place의 name은 비어있을 수 없습니다.");
        }

        String tagsText = joinTags(place.getTags());

        String combined = Stream.of(
                        place.getName(),
                        place.getCategory(),
                        tagsText,
                        place.getDescription(),
                        place.getRegion()
                )
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));

        return combined;
    }

    /**
     * 개별 필드 값으로부터 임베딩 텍스트를 생성한다.
     * Place 엔티티 없이도 사용 가능.
     *
     * @param name        장소명 (필수)
     * @param category    카테고리
     * @param tags        태그 목록
     * @param description 설명
     * @param region      지역
     * @return 결합된 임베딩 텍스트
     * @throws IllegalArgumentException name이 null이거나 빈 값인 경우
     */
    public static String buildText(String name, String category, List<String> tags,
                                   String description, String region) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name은 비어있을 수 없습니다.");
        }

        String tagsText = joinTags(tags);

        return Stream.of(name, category, tagsText, description, region)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String joined = tags.stream()
                .filter(Objects::nonNull)
                .filter(t -> !t.isBlank())
                .collect(Collectors.joining(" "));
        return joined.isBlank() ? null : joined;
    }
}
