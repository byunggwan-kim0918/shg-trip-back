package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isCafeLike;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.CAFE_PER_DAY_CAP;

/**
 * 하루 구성에 든 끼니 종류·묶음과 카페 수. 넣기·바꾸기·옮기기·대안 경로가 그날 같은 종류 끼니와 카페 상한({@link ItineraryRules}와
 * 같은 상수)을 본다. 판정은 점심·저녁만 비교하지만 여기서는 그날 모든 끼니를 본다. 여행 전체 종류 상한은 유형 상한({@code typeCapFor})이 맡는다.
 *
 * <p>하지 않아도 되는 보충·이동은 걸러 내고, 꼭 해야 하는 교체(휴무·거리·중복)와 대안은 통과하는 후보를 먼저 고르기만 한다.
 * 거르면 해산물만 있는 지역에서 교체 자체가 실패한다.
 */
final class VarietyLedger {

    private final Set<String> kinds = new HashSet<>();
    private final Set<String> groups = new HashSet<>();
    private int cafes;

    private VarietyLedger(Collection<Integer> places, Integer replaced, List<PlaceCandidate> candidates) {
        for (Integer idx : places) {
            if (!idx.equals(replaced)) add(byIndex(candidates, idx));
        }
    }

    /** 그날 이미 든 끼니와 같은 종류이거나 하루 카페 상한을 넘기는 후보를 막는다. */
    static Predicate<PlaceCandidate> admitsOnDay(DayState day, List<PlaceCandidate> candidates) {
        return new VarietyLedger(day.placeIndices, null, candidates)::admits;
    }

    /** 그날의 {@code replaced} 자리를 바꿀 후보. 바뀌어 나갈 장소는 세지 않는다. */
    static Predicate<PlaceCandidate> admitsReplacing(DayState day, Integer replaced, List<PlaceCandidate> candidates) {
        return new VarietyLedger(day.placeIndices, replaced, candidates)::admits;
    }

    /** day 목록을 고치는 도중이라 아직 반영하지 않은 구성({@code places})으로 본다. */
    static Predicate<PlaceCandidate> admitsAmong(Collection<Integer> places, List<PlaceCandidate> candidates) {
        return new VarietyLedger(places, null, candidates)::admits;
    }

    /** {@code incoming}을 차례로 그날에 넣어도 모두 통과하는지. 여러 곳을 한꺼번에 옮길 때 서로 겹치는 것도 막는다. */
    static boolean admitsAll(DayState day, Collection<Integer> incoming, List<PlaceCandidate> candidates) {
        VarietyLedger ledger = new VarietyLedger(day.placeIndices, null, candidates);
        for (Integer idx : incoming) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (!ledger.admits(c)) return false;
            ledger.add(c);
        }
        return true;
    }

    /** 그날 끼니와 요리가 달라도 같은 묶음(해산물 등)인 후보를 거른다. 끼니를 못 채우지 않게 선호 단계에서만 쓴다. */
    static Predicate<PlaceCandidate> avoidsGroupOnDay(DayState day, List<PlaceCandidate> candidates) {
        VarietyLedger ledger = new VarietyLedger(day.placeIndices, null, candidates);
        return c -> {
            String kind = c == null ? null : mealKindOf(c);
            return kind == null || !ledger.groups.contains(PlaceCategoryConstants.mealGroup(kind));
        };
    }

    private void add(PlaceCandidate c) {
        if (c == null) return;
        String kind = mealKindOf(c);
        if (kind != null) {
            kinds.add(kind);
            groups.add(PlaceCategoryConstants.mealGroup(kind));
        } else if (isCafeLike(c)) {
            cafes++;
        }
    }

    private boolean admits(PlaceCandidate c) {
        if (c == null) return false;
        String kind = mealKindOf(c);
        if (kind != null) return kinds.stream().noneMatch(k -> PlaceCategoryConstants.sameMealKind(k, kind));
        if (c.mealEligible()) return true;
        return !isCafeLike(c) || cafes < CAFE_PER_DAY_CAP;
    }

    /** 반복으로 셀 수 있는 끼니 종류. 끼니가 아니거나 종류를 모르면 null. */
    private static String mealKindOf(PlaceCandidate c) {
        if (!c.mealEligible()) return null;
        String kind = subTypeOf(c);
        return CandidateLookup.isSpecificKind(kind) ? kind : null;
    }
}
