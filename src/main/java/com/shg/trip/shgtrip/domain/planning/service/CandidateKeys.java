package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 후보 풀을 만들고 덧붙일 때 같은 장소를 가리는 키와 판정. 검색 풀 정리와 날짜별 보완이 같은 기준을 써야 같은 곳이 두 번 들어오지 않는다. */
@Slf4j
final class CandidateKeys {

    private CandidateKeys() {
    }

    /**
     * 이름 동일성 키: region + 공백제거·소문자 이름. 이름이 없으면 병합하지 않는다(고유 키).
     * Wikidata 명소는 영문 병기 행이 따로 살아남지 않게 괄호 주석을 떼고 언어판 수를 키에 넣는다.
     * 식당 "OO (제주점)"·"OO (서귀포점)"은 다른 가게라 명소에만 한다.
     */
    static String nameKey(PlaceCandidate c) {
        String raw = c.name() == null ? "" : c.name().toLowerCase();
        boolean wikidataItem = c.wikidataSitelinks() != null && c.wikidataSitelinks() > 0;
        if (wikidataItem) raw = raw.replaceAll("\\(.*?\\)|\\[.*?]", "");
        String name = raw.replaceAll("\\s+", "");
        if (name.isEmpty()) return "idx:" + c.index();
        String region = c.region() == null ? "" : c.region();
        return wikidataItem ? region + "|wd" + c.wikidataSitelinks() + "|" + name : region + "|" + name;
    }

    /**
     * 뒤에 이어 붙이는 후보(예비·숙소)용 중복 키(id·이름·좌표). id만 보면 출처만 다른 같은 장소가
     * 다시 들어와 일정에 두 번 배치된다.
     */
    static Set<String> knownKeys(List<PlaceCandidate> candidates) {
        Set<String> keys = new HashSet<>();
        candidates.forEach(c -> addIfNew(keys, c));
        return keys;
    }

    /** 이름 표기만 다른 출처별 행은 키가 달라 이미 있는 후보와 같은 장소인지 따로 본다. */
    static boolean addIfNew(Set<String> keys, List<PlaceCandidate> pool, PlaceCandidate c) {
        return pool.stream().noneMatch(p -> PlaceIdentity.sameAs(c, p).isPresent()) && addIfNew(keys, c);
    }

    static boolean addIfNew(Set<String> keys, PlaceCandidate c) {
        List<String> mine = new ArrayList<>(3);
        if (c.placeId() != null) mine.add("id:" + c.placeId());
        mine.add(nameKey(c));
        mine.add(physicalKey(c));
        if (mine.stream().anyMatch(keys::contains)) return false;
        keys.addAll(mine);
        return true;
    }

    /** 물리적 동일성 키: 유효좌표면 4자리 반올림 좌표, 없으면 공백제거·소문자 이름. */
    static String physicalKey(PlaceCandidate c) {
        if (hasValidCoords(c)) {
            long lat = Math.round(c.latitude().doubleValue() * 10000);
            long lng = Math.round(c.longitude().doubleValue() * 10000);
            return "geo:" + lat + "|" + lng;
        }
        String name = c.name() == null ? "" : c.name().toLowerCase().replaceAll("\\s+", "");
        return "name:" + name;
    }

    static boolean hasValidCoords(PlaceCandidate c) {
        return c.latitude() != null && c.longitude() != null
                && !(c.latitude().signum() == 0 && c.longitude().signum() == 0);
    }

    private static final double STORE_INSIDE_VENUE_KM = 0.3;
    private static final int VENUE_NAME_MIN_LENGTH = 3;

    /** 상업시설 이름으로 시작하는 근처 매장은 그 건물 안이라 둘 다 두면 같은 건물을 두 번 방문한다. */
    static List<PlaceCandidate> dropStoresInsideVenue(List<PlaceCandidate> candidates) {
        List<PlaceCandidate> result = new ArrayList<>(candidates.size());
        for (PlaceCandidate c : candidates) {
            if (isStoreInsideVenue(c, candidates)) {
                log.info("건물 안 매장 제외: {}", c.name());
            } else {
                result.add(c);
            }
        }
        return result;
    }

    static boolean isStoreInsideVenue(PlaceCandidate c, List<PlaceCandidate> pool) {
        if (c.userSelected() || !isRetail(c) || !hasCoords(c)) return false;
        String name = compactName(c);
        return pool.stream().anyMatch(v -> v != c && isRetail(v) && hasCoords(v)
                && compactName(v).length() >= VENUE_NAME_MIN_LENGTH
                && name.length() > compactName(v).length()
                && name.startsWith(compactName(v))
                && GeoUtils.haversine(v.latitude().doubleValue(), v.longitude().doubleValue(),
                        c.latitude().doubleValue(), c.longitude().doubleValue()) <= STORE_INSIDE_VENUE_KM);
    }

    private static boolean isRetail(PlaceCandidate c) {
        return c.category() != null && c.category().toLowerCase().startsWith("retail");
    }

    private static boolean hasCoords(PlaceCandidate c) {
        return c.latitude() != null && c.longitude() != null
                && !GeoUtils.isZeroCoord(c.latitude(), c.longitude());
    }

    static String compactName(PlaceCandidate c) {
        return c.name() == null ? "" : c.name().toLowerCase().replaceAll("\\s+", "");
    }
}
