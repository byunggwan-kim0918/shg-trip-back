package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.util.List;
import java.util.Locale;

/**
 * 후보 장소의 일정 속 역할을 정하는 단일 소스.
 * 장소를 넣거나 바꾸는 경로가 28개라 경로마다 따로 판정하면 저가치 시설·대체 기준이 어긋난다.
 *
 * <p>보강 배치가 채운 {@code place_role}이 없으면 {@link PlaceCategoryConstants} 규칙으로 추정한다.
 */
public final class CandidatePolicy {

    private CandidatePolicy() {}

    public enum Role {
        MEAL, CAFE, BAR, SIGHT, ACTIVITY, SHOPPING, MARKET, LODGING, TRANSIT, NOT_VISITABLE;

        /** 낮 시간 방문 스텝으로 쓰이는 역할(관광 계열). 서로 바꿔 넣어도 하루 구성이 무너지지 않는다. */
        boolean isVisit() {
            return this == SIGHT || this == ACTIVITY || this == SHOPPING || this == MARKET;
        }

        /** 자동 보충(빈 시간·커버리지·관광 보충 등)으로 넣어도 되는 역할. */
        boolean isFillable() {
            return isVisit() || this == MEAL || this == CAFE;
        }

        static Role parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public static Role roleOf(PlaceCandidate c) {
        if (c == null) return Role.NOT_VISITABLE;
        // 보강 모델은 근린공원·키즈카페도 SIGHT로 적어 명시 목록이 보강 역할보다 앞선다. 사용자가 고른 곳은 예외다.
        if (!c.userSelected() && (isBlocked(c.name(), c.country()) || isCertainlyNotVisitable(c.name(), c.category()))) {
            return Role.NOT_VISITABLE;
        }
        Role enriched = Role.parse(c.placeRole());
        if (enriched != null) return enriched;
        Role byAttributes = roleOf(c.attributes());
        if (byAttributes != null) return byAttributes;
        return estimateRole(c.name(), c.category(), c.tags());
    }

    /**
     * 업종 대응은 v1 보강(DiningEnrichment.roleOf)과 같아야 한다. 숙소 유형은 카테고리보다 우선한다.
     *
     * @return 판정할 속성이 없으면 null
     */
    static Role roleOf(PlaceAttributes attributes) {
        if (attributes == null) return null;
        if (attributes.lodgingType() != null) return Role.LODGING;
        if (attributes.venue() == null) return null;
        return switch (attributes.venue()) {
            case "MEAL" -> Role.MEAL;
            case "BAR" -> Role.BAR;
            default -> Role.CAFE;
        };
    }

    /** 보강 전 추정. 카테고리·이름·태그만 본다. */
    public static Role estimateRole(String name, String category, List<String> tags) {
        String major = PlaceCategoryConstants.majorCategory(category);
        String lower = category == null ? "" : category.toLowerCase(Locale.ROOT);
        switch (major) {
            case "LODGING":
                return Role.LODGING;
            case "TRANSIT_HUB":
                return Role.TRANSIT;
            case "DINING":
            case "CAFE":
                if (PlaceCategoryConstants.isMealPlace(name, category, tags)) return Role.MEAL;
                if (PlaceCategoryConstants.isBar(category, tags)) return Role.BAR;
                if (PlaceCategoryConstants.isCafeLike(name, category, tags)) return Role.CAFE;
                // 이름에 "카페"가 들어간 한식당처럼 식사도 카페도 아닌 것으로 판정된 곳은
                // 어느 자리에도 자동으로 넣지 않는다.
                return Role.NOT_VISITABLE;
            case "ATTRACTION":
                if (isLowValue(name, lower)) return Role.NOT_VISITABLE;
                if (lower.contains("market")) return Role.MARKET;
                if (lower.contains("retail") || lower.contains("shopping")) return Role.SHOPPING;
                if (lower.contains("sports and recreation") || lower.contains("outdoor activity")) return Role.ACTIVITY;
                return Role.SIGHT;
            default:
                return Role.NOT_VISITABLE;
        }
    }

    /** 동행·지역과 무관하게 추천하지 않는 장소. 방문 가능 여부가 아니라 정책이라 DB 역할로 기록하지 않는다. */
    static boolean isBlocked(String name) {
        return isBlocked(name, null);
    }

    /** @param country null이면 국내로 본다 */
    static boolean isBlocked(String name, String country) {
        if (name == null) return false;
        String compact = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (BLOCKED_NAME.stream().anyMatch(compact::contains)) return true;
        boolean overseas = country != null && !country.isBlank() && !"KR".equalsIgnoreCase(country.trim());
        return overseas && OVERSEAS_BLOCKED_NAME.stream().anyMatch(compact::contains);
    }

    // 공백 없는 소문자로 비교한다. 야스쿠니는 근처 도로(靖国通り) 이름을 쓰는 가게가 걸리지 않게 시설 이름 전체로 둔다.
    private static final List<String> BLOCKED_NAME = List.of(
            "야스쿠니신사", "靖国神社", "靖國神社", "yasukunishrine", "yasukunijinja", "유슈칸", "遊就館",
            "제주러브랜드", "jejuloveland");

    // 해외 북한 국영 식당. "평양"은 한국의 평양냉면집 이름에 흔해 해외 행에만 본다.
    private static final List<String> OVERSEAS_BLOCKED_NAME = List.of(
            "평양", "옥류관", "류경", "平壤", "玉流馆", "玉流館", "柳京", "pyongyang", "okryu");

    /**
     * 관광 대분류 안의 명시 목록과 키즈카페처럼 비방문이 확실해 DB에 영구 기록해도 되는 범위.
     * 그 밖의 추정은 기록하면 보강 대상에서 빠져 고쳐질 길이 없다.
     */
    public static boolean isCertainlyNotVisitable(String name, String category) {
        String lower = category == null ? "" : category.toLowerCase(Locale.ROOT);
        // 카페 업종으로 적재된 키즈카페는 관광 대분류가 아니어서 카페 휴식 자리에 들어간다.
        if (name != null && name.contains("키즈카페")) return true;
        return "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(category)) && isLowValue(name, lower);
    }

    /**
     * 관광 대분류 안의 저가치 시설.
     * "Well"을 이름 부분일치로 보면 웰니스·웰컴센터까지 걸린다.
     */
    private static boolean isLowValue(String name, String lowerCategory) {
        if (LOW_VALUE_CATEGORY.stream().anyMatch(lowerCategory::contains)) return true;
        // 박물관은 이름에 "자동차"·"골프"가 있어도 방문지다(경주 세계 자동차 박물관)
        if (lowerCategory.contains("museum")) return false;
        if (name == null) return false;
        if (LOW_VALUE_NAME.stream().anyMatch(name::contains)) return true;
        // 공원·유적 입구 레코드는 본 장소와 따로 적재돼 같은 곳을 두 번 넣게 한다. 탐방로 입구는 등산 출발점이라 남긴다.
        boolean trailhead = lowerCategory.contains("hiking") || lowerCategory.contains("mountain");
        return !trailhead && name.strip().endsWith("입구");
    }

    private static final List<String> LOW_VALUE_CATEGORY = List.of(
            "golf course", "golf driving range", "stable", "car dealership", "automotive",
            "parking", "car wash", "fuel station", "> well", "gym and studio", "playground",
            // 행정 단위 레코드(FSQ "States and Municipalities")는 지역 이름이다.
            // 방문지로 두면 도시 항목에 붙은 Wikidata 인기도로 대표 명소 1위가 된다.
            "states and municipalities", "rest area");

    private static final List<String> LOW_VALUE_NAME = List.of(
            "골프", "컨트리클럽", "자동차", "모터스", "오토모빌", "주차장", "세차", "근린공원", "키즈카페");

    /**
     * 음식 종류 키. 보강된 {@code cuisine}이 있으면 그것을, 없으면 이름·태그 기반 세부 유형을 쓴다.
     * 같은 끼니 반복 판정(유형 상한·같은 날 점심/저녁)이 이 값을 본다.
     */
    static String cuisineOf(PlaceCandidate c) {
        if (c == null) return "";
        return PlaceCategoryConstants.mealKind(c.name(), c.category(), c.tags(), c.description(), c.cuisine());
    }

    /** 바꿔 넣어도 하루 구성이 그대로인 역할끼리인지(식사↔식사, 카페↔카페, 관광 계열끼리). */
    static boolean sameRole(PlaceCandidate a, PlaceCandidate b) {
        Role ra = roleOf(a);
        Role rb = roleOf(b);
        return ra == rb || (ra.isVisit() && rb.isVisit());
    }
}
