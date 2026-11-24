package com.shg.trip.shgtrip.domain.place.embedding;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class EmbeddingTextBuilder {

    private EmbeddingTextBuilder() {
    }

    private static final int HANGUL_FIRST = 0xAC00;
    private static final int HANGUL_LAST = 0xD7A3;
    private static final int FINAL_CONSONANTS = 28;
    private static final List<String> ADMIN_SUFFIXES = List.of("", "시", "도", "특별시", "광역시", "특별자치시", "특별자치도");

    private static final class DestinationsHolder {
        static final Destinations INSTANCE = Destinations.fromClasspath();
    }

    /**
     * 장소를 설명하는 한국어 문장. 예: "순수박물관은 Art Gallery입니다. 미술관, 전시와 관련된 곳입니다. (설명)".
     * 임베딩은 글 전체의 뜻을 하나로 합쳐, 낱말을 이어 붙이면 낱말 사이의 관계가 빠지고 모든 장소에 붙는 말
     * (지역명, 카테고리 대분류)이 장소 고유의 뜻을 흐린다. 지역은 검색 SQL이 먼저 거르므로 넣지 않는다.
     * 해외 장소는 한국어 질의("센소지")와 가까워지도록 한국어 이름을 앞에, 원문 이름을 괄호에 둔다.
     *
     * @throws IllegalArgumentException place가 null이거나 name이 null/빈 값인 경우
     */
    public static String buildText(Place place) {
        if (place == null) {
            throw new IllegalArgumentException("Place는 null일 수 없습니다.");
        }
        String name = place.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Place의 name은 비어있을 수 없습니다.");
        }
        String nameKo = place.getNameKo();
        boolean hasKoreanName = nameKo != null && !nameKo.isBlank() && !nameKo.strip().equals(name.strip());
        String subjectWord = hasKoreanName ? nameKo.strip() : name.strip();
        String subject = hasKoreanName ? subjectWord + "(" + name.strip() + ")" : subjectWord;

        StringBuilder sb = new StringBuilder();
        String kind = categoryLeaf(place.getCategory());
        if (kind == null) {
            sb.append(subject).append("입니다.");
        } else {
            sb.append(subject).append(particle(subjectWord, "은", "는")).append(' ').append(kind).append("입니다.");
        }

        Set<String> ownArea = ownAreaKeys(place);
        List<String> tags = place.getTags() == null ? List.of()
                : place.getTags().stream().filter(Objects::nonNull).map(String::strip)
                .filter(t -> !t.isEmpty() && !ownArea.contains(PlaceNames.exactKey(t))).toList();
        if (!tags.isEmpty()) {
            String last = tags.get(tags.size() - 1);
            sb.append(' ').append(String.join(", ", tags)).append(particle(last, "과", "와")).append(" 관련된 곳입니다.");
        }

        String description = place.getDescription();
        if (description != null && !description.isBlank()) {
            sb.append(' ').append(description.strip());
        }
        return sb.toString();
    }

    /**
     * 장소 자신의 지역·나라 이름(서울, 제주도, Tokyo, 일본). 그 지역 장소마다 같은 태그라 장소를 구분하지 못하고 뜻만 흐린다.
     * 태그 전체가 같을 때만 뺀다("제주흑돼지"는 남는다). DB 태그는 그대로 두고 임베딩 글에서만 뺀다.
     */
    static Set<String> ownAreaKeys(Place place) {
        Set<String> keys = new HashSet<>();
        String region = place.getRegion();
        if (region != null && !region.isBlank()) {
            keys.add(PlaceNames.exactKey(region));
            DomesticCities.find(region).or(() -> DestinationsHolder.INSTANCE.find(region)).ifPresent(d -> {
                keys.add(PlaceNames.exactKey(d.name()));
                for (String suffix : ADMIN_SUFFIXES) keys.add(PlaceNames.exactKey(d.nameKo() + suffix));
            });
        }
        String country = place.getCountry();
        if (country != null && !country.isBlank()) {
            keys.add(PlaceNames.exactKey(Destinations.countryNameKo(country)));
            if ("KR".equals(country)) keys.add(PlaceNames.exactKey("대한민국"));
        }
        keys.remove("");
        return keys;
    }

    /** 카테고리 경로("Arts and Entertainment > Art Gallery")의 마지막 단계. 대분류는 많은 장소가 같아 구분에 쓰이지 않는다. */
    static String categoryLeaf(String category) {
        if (category == null || category.isBlank()) return null;
        String[] parts = category.split(">");
        String leaf = parts[parts.length - 1].strip();
        return leaf.isEmpty() ? null : leaf;
    }

    /** 마지막 글자가 한글이면 받침으로 조사를 고르고, 아니면 두 조사를 함께 쓴다("Spao은(는)"). */
    static String particle(String word, String afterConsonant, String afterVowel) {
        if (word == null || word.isEmpty()) return afterConsonant + "(" + afterVowel + ")";
        char last = word.charAt(word.length() - 1);
        if (last < HANGUL_FIRST || last > HANGUL_LAST) return afterConsonant + "(" + afterVowel + ")";
        return (last - HANGUL_FIRST) % FINAL_CONSONANTS == 0 ? afterVowel : afterConsonant;
    }
}
