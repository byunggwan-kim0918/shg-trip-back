package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;

/**
 * 대안 카드와 스텝의 한 줄 설명. 식당은 설명·사진 데이터가 거의 없어 종류, 지금 장소와의 거리, 알려진 정도만으로 만든다.
 * 데이터에 없는 평가("분위기 좋은")는 쓰지 않는다.
 */
final class AlternativeNotes {

    private AlternativeNotes() {
    }

    private static final Map<String, String> KIND_LABELS = Map.ofEntries(
            Map.entry("dumpling", "만두"), Map.entry("banh_mi", "반미"), Map.entry("takoyaki", "타코야키"),
            Map.entry("okonomiyaki", "오코노미야키"), Map.entry("tofu", "두부 요리"),
            Map.entry("chinese_noodle", "짬뽕·짜장"), Map.entry("gukbap", "국밥"), Map.entry("cold_noodle", "냉면·밀면"),
            Map.entry("shellfish", "조개 요리"), Map.entry("eel", "장어"), Map.entry("crab", "게 요리"),
            Map.entry("raw_fish", "회"), Map.entry("grilled_fish", "생선구이"), Map.entry("meat", "고기 요리"),
            Map.entry("chicken", "닭 요리"), Map.entry("seafood", "해산물"), Map.entry("noodle", "면 요리"),
            Map.entry("soup", "국물 요리"), Map.entry("bakery", "베이커리"), Map.entry("cafe_drink", "카페"),
            Map.entry("waterfall", "폭포"), Map.entry("beach", "해변"), Map.entry("cave", "동굴"),
            Map.entry("hill", "산·오름"), Map.entry("trail", "산책로"), Map.entry("park", "공원"),
            Map.entry("lake", "호수"), Map.entry("island", "섬"), Map.entry("harbor", "항구"),
            Map.entry("viewpoint", "전망대"), Map.entry("tower", "타워"), Map.entry("temple", "사찰·신사"),
            Map.entry("palace", "궁"), Map.entry("museum", "박물관·미술관"), Map.entry("market", "시장"),
            Map.entry("theme_park", "테마파크"), Map.entry("spa", "온천·스파"), Map.entry("shopping", "쇼핑"),
            Map.entry("street", "거리"));

    private static final String WELL_KNOWN = "널리 알려진 곳";

    private static final Map<CandidatePolicy.Role, String> ROLE_LABELS = Map.of(
            CandidatePolicy.Role.MEAL, "식당", CandidatePolicy.Role.CAFE, "카페", CandidatePolicy.Role.BAR, "술집",
            CandidatePolicy.Role.SIGHT, "볼거리", CandidatePolicy.Role.ACTIVITY, "체험",
            CandidatePolicy.Role.SHOPPING, "쇼핑", CandidatePolicy.Role.MARKET, "시장",
            CandidatePolicy.Role.LODGING, "숙소");

    /** "해산물 · 지금 장소에서 600m · 널리 알려진 곳". 지금 장소나 좌표가 없으면 거리를 뺀다. */
    static String describe(PlaceCandidate alternative, PlaceCandidate current) {
        List<String> parts = new ArrayList<>();
        parts.add(label(alternative));
        double[] from = current == null ? null : coordsOf(current);
        double[] to = coordsOf(alternative);
        if (from != null && to != null) {
            parts.add("지금 장소에서 " + distance(GeoUtils.haversine(from, to) * GeoUtils.ROAD_DISTANCE_FACTOR));
        }
        if (wellKnown(alternative)) parts.add(WELL_KNOWN);
        return String.join(" · ", parts);
    }

    /** "해산물 · 널리 알려진 곳". */
    static String summary(PlaceCandidate c) {
        return wellKnown(c) ? label(c) + " · " + WELL_KNOWN : label(c);
    }

    private static boolean wellKnown(PlaceCandidate c) {
        Integer links = c.wikidataSitelinks();
        return links != null && links >= ScheduleTuning.ICONIC_PROTECT_SITELINKS;
    }

    static String label(PlaceCandidate c) {
        String kind = c.mealEligible() ? CandidatePolicy.cuisineOf(c) : subTypeOf(c);
        String byKind = kind == null ? null : KIND_LABELS.get(kind);
        if (byKind != null) return byKind;
        return ROLE_LABELS.getOrDefault(CandidatePolicy.roleOf(c), "장소");
    }

    // 이동 표시와 같은 도로 환산 거리다.
    static String distance(double roadKm) {
        long meters = Math.max(50, Math.round(roadKm * 20) * 50);
        if (meters < 1000) return meters + "m";
        if (Math.round(roadKm * 10) < 100) return String.format(Locale.ROOT, "%.1fkm", roadKm);
        return Math.round(roadKm) + "km";
    }
}
