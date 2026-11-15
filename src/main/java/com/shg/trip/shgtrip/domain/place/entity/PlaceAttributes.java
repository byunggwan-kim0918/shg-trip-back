package com.shg.trip.shgtrip.domain.place.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 보강 배치가 정한 장소 속성. 모든 필드는 null 허용이며 null은 "모름"이다. 읽는 쪽은 기존 추정으로 돌아간다.
 *
 * <p>{@code official}은 TourAPI detailIntro2 원문(필드명 → 텍스트, HTML 제거)이다. 영업시간·휴무·요금은
 * 모델이 아니라 이 원문에서만 가져온다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceAttributes(
        String venue,
        String subType,
        String visitStyle,
        String hikeLevel,
        String lodgingType,
        Boolean breakfast,
        Map<String, String> official,
        // 식당 대표 요리의 주재료. null은 아직 묻지 않음, 빈 목록은 고기·해산물이 주재료가 아니거나 근거가 없음
        List<String> ingredients
) {
    public static final Set<String> VENUES = Set.of("MEAL", "CAFE", "BAKERY", "DESSERT", "BAR", "SNACK");
    public static final Set<String> SUB_TYPES = Set.of(
            "viewpoint", "beach", "trail", "hill", "mountain", "waterfall", "cave", "island", "garden", "park",
            "museum", "gallery", "palace", "temple", "historic_site", "village", "street", "market",
            "theme_park", "zoo_aquarium", "experience", "shopping", "spa", "landmark", "other");
    public static final Set<String> VISIT_STYLES = Set.of("STOP", "PASS_THROUGH");
    public static final Set<String> HIKE_LEVELS = Set.of("NONE", "EASY", "LONG");
    public static final Set<String> LODGING_TYPES =
            Set.of("HOTEL", "RESORT", "PENSION", "GUESTHOUSE", "HOSTEL", "MOTEL", "CAMPING");
    public static final Set<String> MEAT_INGREDIENTS = Set.of("PORK", "BEEF", "CHICKEN", "DUCK", "LAMB", "OTHER_MEAT");
    public static final Set<String> INGREDIENTS = Stream.concat(MEAT_INGREDIENTS.stream(), Stream.of("SEAFOOD"))
            .collect(Collectors.toUnmodifiableSet());

    public PlaceAttributes(String venue, String subType, String visitStyle, String hikeLevel, String lodgingType,
                           Boolean breakfast, Map<String, String> official) {
        this(venue, subType, visitStyle, hikeLevel, lodgingType, breakfast, official, null);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 허용 목록 밖의 값은 null로 바꾼 사본. 모델 출력과 DB 값을 같은 규칙으로 거른다. */
    public PlaceAttributes normalized() {
        return new PlaceAttributes(
                pick(venue, VENUES, true), pick(subType, SUB_TYPES, false), pick(visitStyle, VISIT_STYLES, true),
                pick(hikeLevel, HIKE_LEVELS, true), pick(lodgingType, LODGING_TYPES, true), breakfast,
                official == null || official.isEmpty() ? null : Map.copyOf(official), pickIngredients(ingredients));
    }

    /** 모델이 정하는 필드가 하나도 없는지. official만 있는 행도 비어 있는 것으로 본다. */
    public boolean hasNoClassification() {
        return venue == null && subType == null && visitStyle == null && hikeLevel == null
                && lodgingType == null && breakfast == null;
    }

    public boolean hasIngredient(String kind) {
        return ingredients != null && ingredients.contains(kind);
    }

    /** 주재료를 물어 하나 이상 받았는지. 빈 목록은 근거가 없다는 답이라 모르는 것으로 본다. */
    public boolean knowsIngredients() {
        return ingredients != null && !ingredients.isEmpty();
    }

    public PlaceAttributes withOfficial(Map<String, String> newOfficial) {
        return new PlaceAttributes(venue, subType, visitStyle, hikeLevel, lodgingType, breakfast,
                newOfficial == null || newOfficial.isEmpty() ? null : Map.copyOf(newOfficial), ingredients);
    }

    /** official·주재료를 뺀 분류 JSON. 엔티티 매핑과 같은 키(필드명)를 쓴다. */
    public String classificationJson() {
        try {
            return JSON.writeValueAsString(
                    new PlaceAttributes(venue, subType, visitStyle, hikeLevel, lodgingType, breakfast, null, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSONB 문자열 → 레코드. 비었거나 읽을 수 없으면 null(속성 없음으로 취급). */
    public static PlaceAttributes fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, PlaceAttributes.class).normalized();
        } catch (Exception e) {
            return null;
        }
    }

    /** null(아직 묻지 않음)은 빈 목록으로 바꾸지 않는다. */
    public static List<String> pickIngredients(List<String> values) {
        if (values == null) return null;
        return values.stream().map(v -> pick(v, INGREDIENTS, true)).filter(Objects::nonNull).distinct().toList();
    }

    private static String pick(String value, Set<String> allowed, boolean upper) {
        if (value == null) return null;
        String v = value.strip();
        v = upper ? v.toUpperCase(Locale.ROOT) : v.toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : null;
    }
}
