package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Verdict;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;
import com.shg.trip.shgtrip.domain.planning.service.QualityGate.PoolLimit;

import java.util.HashSet;
import java.util.List;

/**
 * 저장된 일정의 후보 풀 한계(바꿔 넣을 곳이 없던 핵심 위반)를 판정에 반영한다. 사용자가 대안으로도 고칠 수 없어
 * '조정 필요'로 두지 않고 알림으로 보인다. 새 생성은 한계를 남기지 않아 저장된 일정의 값만 읽는다.
 */
public final class PoolLimits {

    private PoolLimits() {}

    /** 편집으로 근거 장소가 줄기만 했으면 같은 한계이고, 저장 때 없던 장소가 근거에 들면 사용자가 고칠 수 있는 위반이다. */
    static boolean isLimited(Violation v, List<PoolLimit> limits) {
        return limits != null && limits.stream().anyMatch(l -> l.rule() == v.rule() && l.day() == v.day()
                && new HashSet<>(l.evidence()).containsAll(v.evidence()));
    }

    /** 풀 탓이 아닌 핵심 위반이 하나라도 있으면 사용자가 고칠 일이 남은 일정이다. */
    public static boolean needsAdjustment(Verdict verdict, List<PoolLimit> limits) {
        return verdict.violations().stream()
                .anyMatch(v -> v.severity() == ItineraryRules.Severity.CORE && !isLimited(v, limits));
    }
}
