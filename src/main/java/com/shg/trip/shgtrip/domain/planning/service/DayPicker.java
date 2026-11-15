package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.accommodationCoords;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isSelectable;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.dayBudgets;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isOpenDuring;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.resolveVisitMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.timePreference;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.addToDay;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.openOnDayFilter;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.wouldExceedDayBudget;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.ARRIVAL_HUB_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINING_VISIT_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.EARLY_FINISH_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.GAP_FILL_DETOUR_WEIGHT;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.INTRA_BUCKET_TRAVEL_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_START;

/**
 * 선택 결과 없이 후보 풀에서 날마다 끼니와 관광지를 고른다. 날마다 기준 장소를 하나 두고, 그날은 기준 장소에서 한 구간으로
 * 닿는 곳만 고른다.
 */
final class DayPicker {

    private static final Comparator<PlaceCandidate> REQUESTED_FIRST =
            Comparator.comparing((PlaceCandidate c) -> !c.keepsPlace());

    private static final Comparator<PlaceCandidate> BY_MERIT = REQUESTED_FIRST
            .thenComparing(Comparator.comparingDouble((PlaceCandidate c) -> PlaceProminence.of(c)).reversed())
            .thenComparing(Comparator.comparingDouble(PlaceCandidate::similarityScore).reversed())
            .thenComparingInt(PlaceCandidate::index);

    private DayPicker() {
    }

    /** 날마다 기준 장소를 놓고 숙소를 정한다. 날짜별 구성이 비어 있는 상태에서 한 번 부른다. */
    static void prepare(PlanState s, TransportationHub hub) {
        // 기준 장소를 거점에서 가까운 순으로 놓으려면 거점이 먼저 있어야 한다.
        LodgingPlanner.assignHubsAndLodging(s.days, s.candidates, hub);
        placeBases(s);
        for (int i = 0; i < s.days.size(); i++) {
            LodgingPlanner.ensureAccommodation(s.days.get(i), s.candidates, DayState.endsTrip(s.days, i));
        }
        // 날마다 가장 가까운 숙소를 잡은 상태라 연박과 숙소 수 상한을 다시 맞춘다.
        LodgingPlanner.assignHubsAndLodging(s.days, s.candidates, hub);
        keepBasesInReach(s);
    }

    /**
     * 그날 기준 장소 둘레에서 끼니를 먼저 고르고, 하루 관광 수와 시간 예산이 허락하는 만큼 관광지를 더한다.
     * 끼니를 나중에 고르면 관광지가 거리 예산을 다 써서 끼니를 넣을 수 없다.
     *
     * @param previousLodging 전날 숙소. 아침 구간을 거리 예산에 넣는다
     * @return 하루 관광 하한에서 모자란 수
     */
    static int fill(PlanState s, DayState day, Integer previousLodging, boolean lastDay) {
        int[] range = ScheduleTuning.sightsPerDay(s.pace);
        int floor = day.hasUserWindow() ? 1 : range[0];
        PlaceCandidate base = baseOf(s, day);
        if (base == null) return day.tooShortForSightseeing() ? 0 : floor;
        day.startLodgingIndex = day.arrivalHubIndex == null ? previousLodging : null;

        ScheduleConfig cfg = s.cfg.forDay(day);
        double cap = legCapKm(s);
        Map<String, Integer> typeCount = DiversityRepair.countSubTypes(s.days, s.candidates);
        // 요청 의도 장소는 유형 트림에서 빠지는 것과 같은 이유로 유형 반복을 따지지 않는다.
        Predicate<PlaceCandidate> underTypeCap = c -> c.keepsPlace() || !DiversityRepair.isSpecificType(subTypeOf(c))
                || typeCount.getOrDefault(subTypeOf(c), 0) < DiversityRepair.typeCapFor(c, s.days.size());
        Predicate<PlaceCandidate> addable = openOnDayFilter(s.startDate, day.dayNumber)
                .and(c -> !s.usedIndices.contains(c.index()) && coordsOf(c) != null && isSelectable(c))
                .and(c -> km(base, c) <= cap)
                .and(c -> !wouldExceedDayBudget(day, c, s.candidates, s.transportPref, cfg));

        if (day.lunchInWindow()) {
            pickMeal(s, day, addable, underTypeCap, typeCount, LUNCH_START, coordsOf(base), cap);
        }
        if (day.dinnerInWindow() && !lastDay) {
            double[] lodging = accommodationCoords(day, s.candidates);
            double[] near = lodging != null && GeoUtils.haversine(lodging, coordsOf(base)) <= cap
                    ? lodging : coordsOf(base);
            pickMeal(s, day, addable, underTypeCap, typeCount, DINNER_START, near, cap);
        }

        int meals = (day.lunchInWindow() ? 1 : 0) + (day.dinnerInWindow() ? 1 : 0);
        int[] budgets = dayBudgets(day, cfg, meals, ARRIVAL_HUB_MINUTES);
        // 낮에만 가는 곳은 저녁 예산을 쓰지 못한다. 합쳐서 세면 낮 관광지를 넘치게 골라 확정 때 빠진다.
        // 마지막 날은 저녁을 고르지 않아 저녁 시간까지 채우면 저녁 없는 일정이 된다.
        int[] left = {budgets[0] + budgets[1], lastDay ? 0 : budgets[2]};
        // 카페·간식은 하루 관광 자리 하나를 쓴다(ScheduleTuning.SIGHTS_PER_DAY).
        int stops = 0;
        int endsAt = cfg.morningStart + (day.lunchInWindow() ? DINING_VISIT_MINUTES : 0);
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(s.candidates, idx);
            if (c == null || c.mealEligible()) continue;
            stops++;
            spend(left, c);
            endsAt += minutesFor(c);
        }

        // 사용자가 고른 식당은 끼니로만 고른다. 관광 자리로 들이면 세 끼째가 되어 확정에서 빠진다.
        Predicate<PlaceCandidate> sight = addable.and(underTypeCap).and(c -> !c.mealEligible())
                .and(c -> c.keepsPlace() || (CandidatePolicy.roleOf(c).isVisit() && !repeatsToday(s, day, c)));
        // 저녁이 없는 마지막 날은 하루 관광 수를 채워도 일찍 끝나 한 곳 더 고른다.
        boolean oneMore = lastDay && !day.endsEarlyByDeparture();
        // 기준 장소에서 상한의 절반 안에 있는 두 곳은 서로 한 구간 안이다.
        for (double radius : rings(cap)) {
            while (stops < range[1] || (oneMore && stops == range[1] && endsAt < EARLY_FINISH_MINUTES)) {
                PlaceCandidate next = s.candidates.stream()
                        .filter(sight)
                        .filter(c -> km(base, c) <= radius && fits(left, c))
                        .min(byMeritNear(coordsOf(base))).orElse(null);
                if (next == null) break;
                addToDay(day, next.index(), s.spare, s.usedIndices);
                typeCount.merge(subTypeOf(next), 1, Integer::sum);
                stops++;
                spend(left, next);
                endsAt += minutesFor(next);
            }
        }
        return Math.max(0, floor - stops);
    }

    // 첫날은 도착 거점, 마지막 날은 출발 거점에서 가까운 곳을 주고 사이 날은 앞날 기준 장소에서 가까운 순으로 잇는다.
    private static void placeBases(PlanState s) {
        List<DayState> open = s.days.stream().filter(d -> !d.tooShortForSightseeing()).toList();
        if (open.isEmpty()) return;
        double cap = legCapKm(s);
        List<PlaceCandidate> ranked = baseCandidates(s).sorted(BY_MERIT).toList();

        // 요청 의도 장소는 서로 가까워도 각자 하루의 기준이 된다.
        List<PlaceCandidate> bases = new ArrayList<>(ranked.stream().filter(PlaceCandidate::keepsPlace)
                .limit(open.size()).toList());
        // 넓은 여행지에서 날마다 다른 구역을 돌게 서로 떨어진 곳부터 고르고, 모자라면 붙어 있는 곳도 쓴다.
        for (boolean apart : new boolean[]{true, false}) {
            for (PlaceCandidate c : ranked) {
                if (bases.size() == open.size()) break;
                if (bases.contains(c) || (apart && bases.stream().anyMatch(b -> km(b, c) < cap / 2))) continue;
                bases.add(c);
            }
        }

        PlaceCandidate[] ofDay = new PlaceCandidate[open.size()];
        ofDay[0] = takeNearest(bases, hubCoords(s, open.get(0).arrivalHubIndex));
        if (open.size() > 1) {
            ofDay[open.size() - 1] = takeNearest(bases, hubCoords(s, open.get(open.size() - 1).departureHubIndex));
        }
        for (int i = 1; i < open.size() - 1; i++) {
            ofDay[i] = takeNearest(bases, ofDay[i - 1] == null ? null : coordsOf(ofDay[i - 1]));
        }
        for (int i = 0; i < open.size(); i++) {
            if (ofDay[i] != null) open.get(i).placeIndices.add(ofDay[i].index());
        }
    }

    // 숙소를 연박으로 합치면 기준 장소가 숙소에서 한 구간 밖에 놓일 수 있고, 기준 장소가 그날 쉬거나 저녁에만 갈 곳일 수 있다.
    private static void keepBasesInReach(PlanState s) {
        for (int i = 0; i < s.days.size(); i++) {
            DayState day = s.days.get(i);
            PlaceCandidate base = baseOf(s, day);
            if (base == null || usableOn(s, i).test(base)) continue;
            boolean released = false;
            if (base.keepsPlace()) {
                if (swapWithAnotherDay(s, i, base)) continue;
                // 숙소에서 멀기만 한 요청 장소는 거리를 감수하고 둔다. 그날 갈 수 없는 곳은 내려야 다른 날이 고를 수 있다.
                if (openOn(s, i).test(base)) continue;
                day.placeIndices.remove(Integer.valueOf(base.index()));
                released = true;
            }
            List<Integer> taken = s.days.stream().flatMap(d -> d.placeIndices.stream()).toList();
            PlaceCandidate replacement = baseCandidates(s).filter(c -> !taken.contains(c.index()))
                    .filter(usableOn(s, i)).min(BY_MERIT).orElse(null);
            if (replacement == null) continue;
            if (released) day.placeIndices.add(0, replacement.index());
            else replaceBase(day, base, replacement);
        }
    }

    private static boolean swapWithAnotherDay(PlanState s, int i, PlaceCandidate base) {
        for (int j = 0; j < s.days.size(); j++) {
            PlaceCandidate other = baseOf(s, s.days.get(j));
            if (j == i || other == null || !usableOn(s, j).test(base) || !usableOn(s, i).test(other)) continue;
            replaceBase(s.days.get(i), base, other);
            replaceBase(s.days.get(j), other, base);
            return true;
        }
        return false;
    }

    private static Predicate<PlaceCandidate> usableOn(PlanState s, int i) {
        DayState day = s.days.get(i);
        // 숙소가 없는 날은 전날 숙소에서 출발한다.
        double[] tonight = accommodationCoords(day, s.candidates);
        double[] lodging = tonight != null || i == 0 ? tonight : accommodationCoords(s.days.get(i - 1), s.candidates);
        double cap = legCapKm(s);
        return openOn(s, i).and(c -> lodging == null || GeoUtils.haversine(lodging, coordsOf(c)) <= cap);
    }

    // 여행이 끝나는 날에 저녁에 갈 곳을 두면 출발 시각을 넘기거나 저녁 의도를 못 지킨다.
    private static Predicate<PlaceCandidate> openOn(PlanState s, int i) {
        boolean endsTrip = DayState.endsTrip(s.days, i);
        return openOnDayFilter(s.startDate, s.days.get(i).dayNumber)
                .and(c -> !endsTrip || timePreference(c) != TimePreference.EVENING);
    }

    private static void replaceBase(DayState day, PlaceCandidate from, PlaceCandidate to) {
        day.placeIndices.set(day.placeIndices.indexOf(from.index()), to.index());
    }

    private static void pickMeal(PlanState s, DayState day, Predicate<PlaceCandidate> addable,
                                 Predicate<PlaceCandidate> underTypeCap, Map<String, Integer> typeCount,
                                 int slotStart, double[] near, double cap) {
        Predicate<PlaceCandidate> meal = addable.and(PlaceCandidate::mealEligible)
                .and(c -> isOpenDuring(c, day.dayOfWeek, slotStart, slotStart + DINING_VISIT_MINUTES));
        Predicate<PlaceCandidate> otherKindToday = VarietyLedger.admitsOnDay(day, s.candidates);
        Predicate<PlaceCandidate> fresh = underTypeCap.and(VarietyLedger.avoidsGroupOnDay(day, s.candidates));
        // 그날 같은 종류 끼니는 범위를 넓혀서라도 피한다. 묶음과 여행 전체 반복은 같은 거리 안에서만 피한다.
        for (Predicate<PlaceCandidate> kind : List.<Predicate<PlaceCandidate>>of(otherKindToday, c -> true)) {
            for (double radius : rings(cap)) {
                PlaceCandidate pick = s.candidates.stream().filter(meal).filter(kind)
                        .filter(c -> GeoUtils.haversine(near, coordsOf(c)) <= radius)
                        .min(Comparator.comparing((PlaceCandidate c) -> !fresh.test(c)).thenComparing(byMeritNear(near)))
                        .orElse(null);
                if (pick != null) {
                    addToDay(day, pick.index(), s.spare, s.usedIndices);
                    typeCount.merge(subTypeOf(pick), 1, Integer::sum);
                    return;
                }
            }
        }
    }

    // 유명도가 비슷한 식당은 가까운 곳이 이기고, 먼 명소는 유명도 차이가 거리 손해보다 클 때만 이긴다.
    private static Comparator<PlaceCandidate> byMeritNear(double[] from) {
        return REQUESTED_FIRST
                .thenComparing(Comparator.comparingDouble((PlaceCandidate c) -> PlaceProminence.of(c)
                        - GeoUtils.haversine(from, coordsOf(c)) * GAP_FILL_DETOUR_WEIGHT).reversed())
                .thenComparing(BY_MERIT);
    }

    private static Stream<PlaceCandidate> baseCandidates(PlanState s) {
        return s.candidates.stream().filter(c -> coordsOf(c) != null && isSelectable(c)
                && CandidatePolicy.roleOf(c).isVisit()
                && "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category())));
    }

    private static PlaceCandidate baseOf(PlanState s, DayState day) {
        return day.placeIndices.stream().map(i -> byIndex(s.candidates, i))
                .filter(c -> c != null && coordsOf(c) != null && CandidatePolicy.roleOf(c).isVisit())
                .findFirst().orElse(null);
    }

    // 세부 유형이 달라도 백화점과 쇼핑몰은 같은 일정이라 쇼핑·시장은 역할로 하루 한 곳만 둔다.
    private static boolean repeatsToday(PlanState s, DayState day, PlaceCandidate c) {
        String type = subTypeOf(c);
        CandidatePolicy.Role role = CandidatePolicy.roleOf(c);
        boolean oncePerDay = role == CandidatePolicy.Role.SHOPPING || role == CandidatePolicy.Role.MARKET;
        return day.placeIndices.stream().map(i -> byIndex(s.candidates, i)).filter(Objects::nonNull)
                .anyMatch(p -> (DiversityRepair.isSpecificType(type) && type.equals(subTypeOf(p)))
                        || (oncePerDay && CandidatePolicy.roleOf(p) == role));
    }

    private static double[] rings(double capKm) {
        return new double[]{capKm / 2, capKm};
    }

    private static int minutesFor(PlaceCandidate c) {
        return resolveVisitMinutes(c) + INTRA_BUCKET_TRAVEL_MINUTES;
    }

    /** @param left [낮 예산, 저녁 예산](분) */
    private static boolean fits(int[] left, PlaceCandidate c) {
        int need = minutesFor(c);
        return switch (timePreference(c)) {
            case EVENING -> need <= left[1];
            case DAYTIME, MORNING_ONLY -> need <= left[0];
            case FLEXIBLE -> need <= left[0] || need <= left[1];
        };
    }

    private static void spend(int[] left, PlaceCandidate c) {
        int need = minutesFor(c);
        boolean evening = timePreference(c) == TimePreference.EVENING
                || (timePreference(c) == TimePreference.FLEXIBLE && need > left[0]);
        left[evening ? 1 : 0] -= need;
    }

    private static double legCapKm(PlanState s) {
        return ScheduleTuning.distanceLimits(s.transportPref, s.pace)[0];
    }

    private static double km(PlaceCandidate a, PlaceCandidate b) {
        return GeoUtils.haversine(coordsOf(a), coordsOf(b));
    }

    private static double[] hubCoords(PlanState s, Integer hubIndex) {
        return hubIndex == null ? null : coordsOf(byIndex(s.candidates, hubIndex));
    }

    /** 기준점이 없으면 순위가 가장 높은 곳을 준다. */
    private static PlaceCandidate takeNearest(List<PlaceCandidate> left, double[] from) {
        if (left.isEmpty()) return null;
        PlaceCandidate pick = from == null ? left.get(0)
                : left.stream().min(Comparator.comparingDouble(c -> GeoUtils.haversine(from, coordsOf(c)))).get();
        left.remove(pick);
        return pick;
    }
}
