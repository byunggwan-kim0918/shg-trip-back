package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.util.List;
import java.util.Locale;

/**
 * 후보 장소의 <b>일정 속 역할</b>을 정하는 단일 소스.
 *
 * <p>일정에 장소를 넣거나 바꾸는 경로가 28개인데, 경로마다 "방문 가능한가·같은 역할인가"를 따로
 * 판정해 왔다(2026-10 가드 매트릭스). 그 결과 저가치 시설 목록은 한 곳에만 있어 나머지 경로로 자동차
 * 대리점이 관광 스텝이 됐고, 같은 대분류(DINING)라는 이유로 점심 식당이 베이커리로 바뀌었다.
 * 판정은 여기 한 곳에서만 하고, 경로들은 이 결과만 본다.
 *
 * <p>보강 배치가 채운 {@code place_role}이 있으면 그것을 쓴다(LLM이 이름·설명까지 보고 정한 값).
 * 없으면 카테고리·이름·태그로 추정한다 — 추정 규칙은 기존 판정({@link PlaceCategoryConstants}) 그대로다.
 */
public final class CandidatePolicy {

    private CandidatePolicy() {}

    /** 일정 속 역할. */
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
        Role enriched = Role.parse(c.placeRole());
        if (enriched != null) return enriched;
        return estimateRole(c.name(), c.category(), c.tags());
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

    /**
     * 카테고리·이름만으로 비방문이 <b>확실한</b> 장소 — 관광 대분류 안의 명시 목록(골프장·주차장·행정구역 등).
     * DB에 영구 기록해도 되는 범위다. 그 밖의 추정(기타 대분류 기본값, 식사도 카페도 아닌 식당)은 틀릴 수 있어
     * 실행 시 추정과 보강에 맡긴다 — 기록하면 보강 대상에서 빠져 고쳐질 길이 없다.
     */
    public static boolean isCertainlyNotVisitable(String name, String category) {
        String lower = category == null ? "" : category.toLowerCase(Locale.ROOT);
        return "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(category)) && isLowValue(name, lower);
    }

    /**
     * 관광 대분류 안의 저가치 시설. 카테고리로 판정하고, 이름은 카테고리가 총칭일 때만 본다
     * ("Well"을 이름 부분일치로 보면 웰니스·웰컴센터까지 걸린다).
     */
    private static boolean isLowValue(String name, String lowerCategory) {
        if (LOW_VALUE_CATEGORY.stream().anyMatch(lowerCategory::contains)) return true;
        // 박물관은 이름에 "자동차"·"골프"가 있어도 방문지다(경주 세계 자동차 박물관)
        if (lowerCategory.contains("museum")) return false;
        return name != null && LOW_VALUE_NAME.stream().anyMatch(name::contains);
    }

    private static final List<String> LOW_VALUE_CATEGORY = List.of(
            "golf course", "golf driving range", "stable", "car dealership", "automotive",
            "parking", "car wash", "fuel station", "> well", "gym and studio", "playground",
            // 시·구·동 같은 행정 단위 레코드(FSQ "States and Municipalities")는 방문지가 아니라 지역 이름이다.
            // Wikidata 인기도가 도시 항목(서울특별시 260)으로 붙어 대표 명소 1위가 되는 사고가 있었다.
            "states and municipalities", "rest area");

    private static final List<String> LOW_VALUE_NAME = List.of(
            "골프", "컨트리클럽", "자동차", "모터스", "오토모빌", "주차장", "세차");

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
