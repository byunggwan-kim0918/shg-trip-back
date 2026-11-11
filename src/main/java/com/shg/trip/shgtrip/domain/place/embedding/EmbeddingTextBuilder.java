package com.shg.trip.shgtrip.domain.place.embedding;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class EmbeddingTextBuilder {

    private EmbeddingTextBuilder() {
    }

    /**
     * 형식: "[name] [name_ko] [category] [tags joined by space] [description] [region]". null이거나 빈 필드는 건너뛴다.
     * 해외 장소는 원문 이름만으로는 한국어 질의("센소지")와 가까워지지 않아 name_ko를 함께 넣는다.
     *
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
                        place.getNameKo() == null || place.getNameKo().equals(place.getName()) ? null : place.getNameKo(),
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

    /** @throws IllegalArgumentException name이 null이거나 빈 값인 경우 */
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
