package com.shg.trip.shgtrip.domain.planning.service;

import java.util.Set;

/**
 * 장소 카테고리 분류 키워드 상수.
 * HardValidator, ItineraryAutoFixer 등에서 공통으로 사용한다.
 */
public final class PlaceCategoryConstants {

    private PlaceCategoryConstants() {}

    public static final Set<String> ACCOMMODATION_KEYWORDS = Set.of(
            "숙소", "호텔", "리조트", "펜션", "게스트하우스", "모텔", "에어비앤비",
            "캠핑장", "캠프",
            "hotel", "resort", "hostel", "motel", "lodge", "accommodation", "camp"
    );

    public static final Set<String> TRANSIT_HUB_KEYWORDS = Set.of(
            "역", "공항", "터미널", "항구", "airport", "station", "terminal", "port"
    );

    public static boolean isAccommodation(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        return ACCOMMODATION_KEYWORDS.stream().anyMatch(lower::contains);
    }

    public static boolean isTransitHub(String name, String category) {
        String combined = ((name != null ? name : "") + " " + (category != null ? category : "")).toLowerCase();
        return TRANSIT_HUB_KEYWORDS.stream().anyMatch(combined::contains);
    }

    /**
     * 이름에 교통 허브 신호(역/공항/터미널/항구 등)가 있는지 판정한다.
     * 카테고리만으로 판단하지 않는다 — Foursquare/Google 데이터에 "흰여울문화마을"이
     * "Transport Hub > Bus Station"으로 오적재되는 사례가 있어, 실제 허브(공항·역·터미널)만
     * 도착/출발 지점으로 쓰이도록 이름 기반으로 한 번 더 거른다.
     */
    public static boolean hasTransitNameSignal(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return TRANSIT_HUB_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * DB 카테고리(Foursquare 계층 경로, 예: "Dining and Drinking > Restaurant > ...")를
     * 5개 대분류로 매핑한다. 대안 카테고리 매칭, 동선 재정렬 등에서 공통으로 사용한다.
     */
    public static String majorCategory(String category) {
        if (category == null) return "OTHER";
        String lower = category.toLowerCase();
        if (lower.contains("lodging")) return "LODGING";
        if (lower.contains("dining and drinking") && (lower.contains("cafe") || lower.contains("coffee"))) return "CAFE";
        if (lower.contains("dining and drinking") || lower.contains("restaurant")) return "DINING";
        if (lower.contains("landmarks") || lower.contains("arts and entertainment")
                || lower.contains("sports and recreation") || lower.contains("outdoors")) return "ATTRACTION";
        if (TRANSIT_HUB_KEYWORDS.stream().anyMatch(lower::contains)) return "TRANSIT_HUB";
        return "OTHER";
    }
}
