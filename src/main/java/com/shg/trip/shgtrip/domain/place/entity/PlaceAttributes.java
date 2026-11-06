package com.shg.trip.shgtrip.domain.place.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 보강 배치가 정한 장소 속성. 모든 필드는 null 허용이며 null은 "모름"이다 — 읽는 쪽은 기존 추정으로 돌아간다.
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
        Map<String, String> official
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

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 허용 목록 밖의 값은 null로 바꾼 사본. 모델 출력과 DB 값을 같은 규칙으로 거른다. */
    public PlaceAttributes normalized() {
        return new PlaceAttributes(
                pick(venue, VENUES, true), pick(subType, SUB_TYPES, false), pick(visitStyle, VISIT_STYLES, true),
                pick(hikeLevel, HIKE_LEVELS, true), pick(lodgingType, LODGING_TYPES, true), breakfast,
                official == null || official.isEmpty() ? null : Map.copyOf(official));
    }

    /** 모델이 정하는 필드가 하나도 없는지. official만 있는 행도 비어 있는 것으로 본다. */
    public boolean hasNoClassification() {
        return venue == null && subType == null && visitStyle == null && hikeLevel == null
                && lodgingType == null && breakfast == null;
    }

    public PlaceAttributes withClassification(PlaceAttributes c) {
        return new PlaceAttributes(c.venue, c.subType, c.visitStyle, c.hikeLevel, c.lodgingType, c.breakfast,
                official);
    }

    public PlaceAttributes withOfficial(Map<String, String> newOfficial) {
        return new PlaceAttributes(venue, subType, visitStyle, hikeLevel, lodgingType, breakfast,
                newOfficial == null || newOfficial.isEmpty() ? null : Map.copyOf(newOfficial));
    }

    public static PlaceAttributes empty() {
        return new PlaceAttributes(null, null, null, null, null, null, null);
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

    private static String pick(String value, Set<String> allowed, boolean upper) {
        if (value == null) return null;
        String v = value.strip();
        v = upper ? v.toUpperCase(Locale.ROOT) : v.toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : null;
    }
}
