package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 장소 행 ↔ TourAPI 콘텐츠 매칭 규칙(searchKeyword2 결과 중 하나를 고른다).
 *
 * <p>좌표 반경 조회(locationBasedList2)는 대형 명소가 300m 안에서 나오지 않아 쓰지 않는다.
 * 대형 명소는 TourAPI 좌표가 300~500m 떨어져 있어 거리 기준을 넉넉히 둔다.
 */
final class TourApiMatcher {

    private TourApiMatcher() {}

    record Hit(String title, String contentId, int contentType, double lat, double lng) {}

    record Match(Hit hit, double distanceMeters, boolean exactName) {}

    static final double EXACT_NAME_MAX_METERS = 1000;
    static final double PARTIAL_NAME_MAX_METERS = 600;
    private static final int MIN_NAME_CHARS = 2;

    private static final Set<Integer> DINING_TYPES = Set.of(39);
    /** 관광지·문화시설·레포츠·쇼핑. 축제(15)·코스(25)·숙박(32)은 영업시간을 쓰지 않는다. */
    private static final Set<Integer> VISIT_TYPES = Set.of(12, 14, 28, 38);

    private static final Pattern BRACKETED = Pattern.compile("\\s*[\\(\\[（［].*?[\\)\\]）］]");
    private static final Pattern NON_WORD = Pattern.compile("[^0-9a-z가-힣]");

    /** 검색어: 괄호 안(영문 병기·지역 표기)을 뗀 이름. */
    static String keyword(String name) {
        return name == null ? "" : BRACKETED.matcher(name).replaceAll("").strip();
    }

    static String normalize(String name) {
        return NON_WORD.matcher(keyword(name).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * 이름이 같으면 1km, 한쪽이 다른 쪽을 포함하면 600m 안에서, 유형이 맞는 것 중 가장 가까운 것.
     * 이름이 같은 후보가 있으면 포함 관계보다 우선한다("국립중앙박물관" vs "국립중앙박물관 어린이박물관").
     */
    static Optional<Match> pick(String name, String category, double lat, double lng, List<Hit> hits) {
        String n = normalize(name);
        if (n.length() < MIN_NAME_CHARS) return Optional.empty();
        Set<Integer> types = DiningEnrichment.isDining(category) ? DINING_TYPES : VISIT_TYPES;

        return hits.stream()
                .filter(h -> types.contains(h.contentType()))
                .map(h -> {
                    String t = normalize(h.title());
                    double meters = GeoUtils.haversine(lat, lng, h.lat(), h.lng()) * 1000;
                    boolean exact = t.equals(n);
                    boolean partial = !exact && t.length() >= MIN_NAME_CHARS && (t.contains(n) || n.contains(t));
                    if (exact && meters <= EXACT_NAME_MAX_METERS) return new Match(h, meters, true);
                    if (partial && meters <= PARTIAL_NAME_MAX_METERS) return new Match(h, meters, false);
                    return null;
                })
                .filter(m -> m != null)
                .min(Comparator.comparing((Match m) -> !m.exactName()).thenComparingDouble(Match::distanceMeters));
    }
}
