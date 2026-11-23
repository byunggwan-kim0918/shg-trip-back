package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 사용자가 빼 달라고 한 것. 못 먹는 음식·피할 활동, 피할 장소, 배로만 가는 섬, 고른 장소에 합쳐진 큰 장소다.
 * 초안 장소·끼니·숙소·빈 시간 채우기가 모두 이것을 거친다. 한 경로만 막으면 다른 경로로 다시 들어온다.
 */
public final class TripExclusions {

    public static final TripExclusions NONE = new TripExclusions(PlaceRestrictions.NONE, AvoidedPlaces.NONE, List.of(),
            Set.of());

    private final PlaceRestrictions restrictions;
    private final AvoidedPlaces avoided;
    private final List<String> ferryTokens;
    private final Set<Long> displacedIds;

    TripExclusions(PlaceRestrictions restrictions, AvoidedPlaces avoided, List<String> ferryTokens,
                   Set<Long> displacedIds) {
        this.restrictions = restrictions;
        this.avoided = avoided;
        this.ferryTokens = List.copyOf(ferryTokens);
        this.displacedIds = Set.copyOf(displacedIds);
    }

    /** @return 뺄 이유, 넣어도 되면 null. 사용자가 직접 고른 장소는 빼지 않는다 */
    String violatedBy(PlaceCandidate c) {
        if (c.userSelected()) return null;
        if (restrictions.violatedBy(c) != null) return "피할 조건";
        if (c.placeId() != null && displacedIds.contains(c.placeId())) return "고른 장소와 같은 곳";
        if (avoided.matches(c)) return "피할 장소";
        if (FerryAreaFilter.hit(c, ferryTokens) != null) return "배로만 가는 섬";
        return null;
    }

    /**
     * 이름이 피하겠다고 한 곳을 품는지(해변·해수욕장 별칭 포함). 요청 문장에는 "우도는 싫어"처럼 피하는 말도 들어 있어,
     * 이름 언급을 원하는 곳으로 읽는 단계는 이것으로 예외를 둔다. 반대 방향은 보지 않는다. "우도봉"을 피해도 "우도"는 원할 수 있다.
     */
    public static boolean namesAvoided(String name, Collection<String> avoidNames) {
        String key = PlaceNames.exactKey(name);
        if (key.length() < 2 || avoidNames == null) return false;
        for (String avoided : avoidNames) {
            for (String variant : PlaceNames.variants(avoided)) {
                String a = PlaceNames.exactKey(variant);
                if (a.length() >= 2 && key.contains(a)) return true;
            }
        }
        return false;
    }
}
