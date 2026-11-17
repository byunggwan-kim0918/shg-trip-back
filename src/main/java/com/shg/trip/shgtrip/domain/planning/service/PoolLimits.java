package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Verdict;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;
import com.shg.trip.shgtrip.domain.planning.service.QualityGate.PoolLimit;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isOpenDuring;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINING_VISIT_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_START;

/**
 * 후보 풀에 바꿔 넣을 곳이 없어 생긴 핵심 위반을 찾는다. 사용자가 대안으로도 고칠 수 없어 '조정 필요'로 두지 않고 알림으로 보인다.
 * 끼니 종류 반복은 {@link MealKindSwaps}와 같은 기준이고, 빠진 끼니·볼거리는 그날 장소 어디서든 구간 상한 안에
 * 쓰지 않은 후보가 하나라도 있으면 풀 탓으로 보지 않는다.
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

    public static List<PoolLimit> of(Verdict verdict, List<StepData> steps, List<PlaceCandidate> candidates,
                                     ItineraryRules.Context ctx) {
        if (verdict == null) return List.of();
        CandidateLookup lookup = new CandidateLookup(candidates);
        Set<Integer> used = steps.stream().map(lookup::of).filter(Objects::nonNull).map(PlaceCandidate::index)
                .collect(Collectors.toSet());
        double cap = ScheduleTuning.distanceLimits(ctx.transportPref(), ctx.pace())[0];
        MealKindSwaps.Classified kinds = MealKindSwaps.classify(steps, candidates, ctx.transportPref(), ctx.pace(),
                ctx.startDate());

        List<PoolLimit> out = new ArrayList<>();
        for (Violation v : verdict.violations()) {
            boolean limited = switch (v.rule()) {
                case LUNCH_MISSING -> noneInReach(v.day(), meal(v.day(), ctx, LUNCH_START), steps, candidates,
                        used, lookup, cap, ctx);
                case DINNER_MISSING -> noneInReach(v.day(), meal(v.day(), ctx, DINNER_START), steps, candidates,
                        used, lookup, cap, ctx);
                case NO_NON_FOOD_ACTIVITY -> noneInReach(v.day(),
                        c -> !c.mealEligible() && CandidatePolicy.roleOf(c).isVisit(), steps, candidates, used,
                        lookup, cap, ctx);
                case SAME_DAY_MEAL_KIND -> kinds.sameDayLimitedDays().contains(v.day());
                // 끼니 종류 분류가 종류별로 풀 탓 여부를 나누지 않아 모든 반복 종류가 풀 탓일 때만 알림으로 본다.
                case MEAL_KIND_REPEATED -> kinds.repeatsLimited();
                default -> false;
            };
            if (limited && !out.contains(PoolLimit.of(v))) out.add(PoolLimit.of(v));
        }
        return List.copyOf(out);
    }

    private static Predicate<PlaceCandidate> meal(int day, ItineraryRules.Context ctx, int slotStart) {
        DayOfWeek dow = ctx.startDate() == null ? null : ctx.startDate().plusDays(day - 1L).getDayOfWeek();
        return c -> c.mealEligible() && isOpenDuring(c, dow, slotStart, slotStart + DINING_VISIT_MINUTES);
    }

    // 그날 좌표를 아는 장소가 없으면 풀을 판단할 수 없어 풀 탓으로 보지 않는다.
    private static boolean noneInReach(int day, Predicate<PlaceCandidate> fits, List<StepData> steps,
                                       List<PlaceCandidate> candidates, Set<Integer> used, CandidateLookup lookup,
                                       double capKm, ItineraryRules.Context ctx) {
        List<double[]> spots = steps.stream().filter(s -> s.dayNumber() == day)
                .map(lookup::of).filter(Objects::nonNull).map(CandidateGeo::coordsOf).filter(Objects::nonNull)
                .toList();
        if (spots.isEmpty()) return false;
        Predicate<PlaceCandidate> open = PlanEdits.openOnDayFilter(ctx.startDate(), day);
        return candidates.stream()
                .filter(c -> !used.contains(c.index()) && coordsOf(c) != null && CandidateTraits.isSelectable(c))
                .filter(open).filter(fits)
                .noneMatch(c -> spots.stream().anyMatch(p -> GeoUtils.haversine(p, coordsOf(c)) <= capKm));
    }
}
