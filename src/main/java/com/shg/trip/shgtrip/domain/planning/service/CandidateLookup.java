package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 스텝에 대응하는 후보와 그 후보로 판정한 역할·끼니·음식 종류. 후보가 없으면 이름·카테고리로 추정한다.
 * 이름만으로 찾으면 다른 도시의 같은 상호(체인 지점)가 잡혀서 placeId를 먼저 본다.
 */
final class CandidateLookup {

    private final Map<Long, PlaceCandidate> byId = new HashMap<>();
    private final Map<String, PlaceCandidate> byName = new HashMap<>();

    CandidateLookup(List<PlaceCandidate> candidates) {
        if (candidates == null) return;
        for (PlaceCandidate c : candidates) {
            if (c.placeId() != null) byId.putIfAbsent(c.placeId(), c);
            if (c.name() != null) byName.putIfAbsent(c.name(), c);
        }
    }

    boolean isEmpty() {
        return byId.isEmpty() && byName.isEmpty();
    }

    PlaceCandidate of(StepData step) {
        if (step.place() == null) return null;
        Long placeId = step.place().placeId();
        PlaceCandidate found = placeId == null ? null : byId.get(placeId);
        return found != null ? found : byName.get(name(step));
    }

    CandidatePolicy.Role role(StepData step) {
        PlaceCandidate c = of(step);
        if (c != null) return CandidatePolicy.roleOf(c);
        return CandidatePolicy.estimateRole(name(step), category(step), null);
    }

    boolean isMeal(StepData step) {
        PlaceCandidate c = of(step);
        return c != null ? c.mealEligible() : PlaceCategoryConstants.isMealPlace(name(step), category(step));
    }

    /** 끼니 반복 판정에 쓰는 음식 종류 키. 유형 상한·검색 다양화와 같은 값이다. */
    String mealKind(StepData step) {
        PlaceCandidate c = of(step);
        if (c != null) return CandidatePolicy.cuisineOf(c);
        return PlaceCategoryConstants.mealKind(name(step), step.place() == null ? null : step.place().nameKo(),
                category(step), null, null, null);
    }

    /** 숙소·허브를 뺀 마지막 방문의 종료 시각(분). 방문이 없으면 0. */
    int lastActivityEnd(List<StepData> daySteps) {
        return daySteps.stream()
                .filter(s -> {
                    CandidatePolicy.Role r = role(s);
                    return r != CandidatePolicy.Role.LODGING && r != CandidatePolicy.Role.TRANSIT;
                })
                .mapToInt(s -> ScheduleTimes.toMinutesOrZero(s.endTime()))
                .max().orElse(0);
    }

    /** 나라 단위 총칭("korean restaurant")이나 판정 불가는 반복으로 세지 않는다. */
    static boolean isSpecificKind(String kind) {
        return kind != null && !kind.isBlank() && !PlaceCategoryConstants.isGenericLeaf(kind);
    }

    static String name(StepData step) {
        return step.place() != null && step.place().name() != null ? step.place().name() : "";
    }

    static String category(StepData step) {
        return step.place() != null ? step.place().category() : null;
    }
}
