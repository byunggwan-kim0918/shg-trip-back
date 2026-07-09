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

    /**
     * 허브 본체가 아닌 부속 시설 POI 신호. Foursquare에 "Immigration Check"/"Check-in
     * Counters"/"주차장" 같은 하위 POI가 공항과 같은 카테고리로 섞여 있어, 도착/출발 허브로
     * 쓰이면 일정 첫 스텝이 "출입국심사대"가 되는 사고가 난다.
     */
    private static final Set<String> HUB_SUB_FACILITY_KEYWORDS = Set.of(
            "immigration", "check-in", "checkin", "security", "gate", "counter",
            "baggage", "lounge", "parking", "주차", "수하물", "출입국", "심사", "탑승구"
    );

    /** 야간 방문이 부적합한 야외/주간 성격 카테고리 신호 (일몰 전 버킷에만 배치). */
    private static final Set<String> DAYTIME_OUTDOOR_KEYWORDS = Set.of(
            "beach", "golf", "trail", "hiking", "garden", "farm", "stable",
            "well", "campground", "scenic", "mountain", "island", "waterfall"
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

    /** 허브 카테고리이지만 본체가 아닌 부속 시설(심사대/체크인/주차 등)인지 판정. */
    public static boolean isHubSubFacility(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return HUB_SUB_FACILITY_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Bar 계열(맥주바/펍/와인바 등) 판정. 대분류는 DINING이지만 식사 슬롯(특히 점심)에
     * 배치되면 부적합하므로 scheduleDay에서 식사가 아닌 저녁 활동으로 다룬다.
     */
    public static boolean isBar(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        return lower.contains("> bar") || lower.contains("nightlife")
                || lower.endsWith("bar") || lower.contains("pub");
    }

    /**
     * 카테고리 기반 체류시간 휴리스틱(분) — enrich 권장 체류시간이 없을 때의 폴백.
     * 등산·트레킹류는 길게, 해변·시장은 짧게, 그 외 관광지는 기존 90분 유지.
     */
    public static int heuristicVisitMinutes(String category) {
        if (category == null) return 90;
        String lower = category.toLowerCase();
        if (lower.contains("hiking") || lower.contains("trail") || lower.contains("mountain")) return 150;
        if (lower.contains("beach") || lower.contains("market")) return 60;
        return 90;
    }

    /** 야간 방문이 부적합한 야외/주간 성격 장소인지 판정(해변/골프/등산로 등). */
    public static boolean isDaytimeOutdoor(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        if (!"ATTRACTION".equals(majorCategory(category))) return false;
        return DAYTIME_OUTDOOR_KEYWORDS.stream().anyMatch(lower::contains);
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
