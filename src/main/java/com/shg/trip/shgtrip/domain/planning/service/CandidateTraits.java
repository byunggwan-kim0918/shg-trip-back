package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.util.*;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 일정 보충·교체·대안이 같이 쓰는 후보 판정과 식별 키(세부 유형, 보충 가능 여부, 긴 등산, 이름 키). */
final class CandidateTraits {

    private CandidateTraits() {
    }

    /** DB places의 (name,address) 유니크 키와 같은 기준으로 정규화한 장소 동일성 키. */
    static String placeKey(String name, String address) {
        return normalizeName(name) + "|" + normalizeName(address);
    }

    static boolean isSelectable(PlaceCandidate c) {
        return isSelectable(c, false);
    }

    /**
     * 코드가 날짜에 놓을 수 있는 후보인지. 주소 규칙은 보지 않는다. 걸면 주소가 짧은 서울·오사카 후보 절반이 빠진다.
     * 요청 의도 장소와 선택 단계가 고른 장소는 경유형·약한 분류·긴 등산이어도 놓는다. 셋은 코드가 스스로 늘리지 않을 곳을
     * 거르는 기준이다.
     *
     * @param pickedBySelection 선택 단계가 날짜에 놓았던 장소
     */
    static boolean isSelectable(PlaceCandidate c, boolean pickedBySelection) {
        return isVisitSpot(c) && (c.keepsPlace() || pickedBySelection || (!isViaRoute(c) && isWorthFilling(c)));
    }

    private static boolean isVisitSpot(PlaceCandidate c) {
        // 역할 판정은 CandidatePolicy 한 곳에서 한다. 경로마다 따로 거르면 한 경로로 비방문지가 샌다.
        if (!CandidatePolicy.roleOf(c).isFillable()) return false;
        if (PlaceCategoryConstants.isAdministrativeZone(c.name())) return false;
        // 선택 단계에서 뺀 집합 POI를 최종 패스가 활동으로 되살리지 않게 자동 보충에서도 뺀다.
        if (PlaceCategoryConstants.isAggregatePoi(c.name(), c.category())) return false;
        // 여객시설은 카테고리가 관광지로 오적재돼 있어도 방문지가 아니다.
        return !PlaceCategoryConstants.isPassengerFacility(c.name());
    }

    private static boolean isViaRoute(PlaceCandidate c) {
        return PlaceCategoryConstants.isViaRoute(c.attributes(), c.name(), c.category(), c.tags());
    }

    private static boolean isWorthFilling(PlaceCandidate c) {
        // 약한 분류는 언어판·알려진 정도로 명소임이 드러날 때만 보충한다(상하이 타워는 "Structure"다).
        if (PlaceCategoryConstants.isWeakFill(c.category())
                && !isNotable(PlaceProminence.effectiveSitelinks(c.wikidataSitelinks(), c.travelerFame()))) {
            return false;
        }
        // 긴 등산은 하루 계획의 중심이라 코드가 빈자리 메우기로 넣지 않는다.
        return !isLongHike(c);
    }

    private static boolean isNotable(Integer sitelinks) {
        return sitelinks != null && sitelinks >= WEAK_FILL_NOTABLE_SITELINKS;
    }

    static String nameOf(List<PlaceCandidate> candidates, Integer idx) {
        PlaceCandidate c = byIndex(candidates, idx);
        return c != null && c.name() != null ? c.name() : String.valueOf(idx);
    }

    static String normalizeName(String name) {
        return PlaceNames.exactKey(name);
    }

    /**
     * 긴 등산: 등산·트레킹 성격이면서 체류가 {@value ScheduleTuning#LONG_HIKE_MIN_MINUTES}분 이상.
     * 오전 시작만 허용하고 자동 보충 경로로는 넣지 않는다(Sonnet·사용자가 고른 경우만).
     */
    static boolean isLongHike(PlaceCandidate c) {
        return isHikeOfAtLeast(c, LONG_HIKE_MIN_MINUTES);
    }

    private static final PlaceRestrictions THEME_PARKS =
            PlaceRestrictions.of(List.of(com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction.THEME_PARK));

    /** 테마파크. 놀이공원을 피하는 조건과 같은 판정이다. */
    static boolean isThemePark(PlaceCandidate c) {
        return c != null && THEME_PARKS.violatedBy(c) != null;
    }

    static boolean isHikeOfAtLeast(PlaceCandidate c, int minMinutes) {
        if (c == null || !PlaceCategoryConstants.isHikingPlace(c.attributes(), c.name(), c.tags())) return false;
        Integer recommended = c.recommendedDurationMinutes();
        int minutes = recommended != null && recommended > 0
                ? recommended
                : PlaceCategoryConstants.heuristicVisitMinutes(c.name(), c.category());
        return minutes >= minMinutes;
    }

    /**
     * 유형 상한·시간대 버킷·대안 매칭이 공유하는 세부 유형 키. 총칭 리프는 이름·태그·설명으로 추론된 유형이 돌아온다.
     */
    static String subTypeOf(PlaceCandidate c) {
        if (c == null) return "";
        // 식사는 검색 다양화와 같은 종류 키(보강된 종류 우선)로 센다. 두 곳이 다르게 세면 상한이 엇나간다.
        if (c.mealEligible()) return CandidatePolicy.cuisineOf(c);
        String byAttribute = PlaceCategoryConstants.attributeSubTypeKey(c.attributes());
        if (byAttribute != null) return byAttribute;
        return PlaceCategoryConstants.subType(c.name(), c.category(), c.tags(), c.description());
    }

    /** 카페·디저트형 장소({@link CandidatePolicy#roleOf}가 CAFE). */
    static boolean isCafeLike(PlaceCandidate c) {
        return c != null && CandidatePolicy.roleOf(c) == CandidatePolicy.Role.CAFE;
    }

    static boolean namesOverlap(String placeName, String hubName) {
        String a = normalizeName(placeName);
        String b = normalizeName(hubName);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

}
