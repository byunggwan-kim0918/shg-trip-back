package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.accommodationCoords;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.detourVia;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.firstCoord;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.lastCoord;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isCafeLike;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isFoodCand;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isVisitableFill;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.computeLeg;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isOpenDuring;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.openAtSlot;
import static com.shg.trip.shgtrip.domain.planning.service.InvariantChecker.departsAfterDinner;
import static com.shg.trip.shgtrip.domain.planning.service.InvariantChecker.hasMealInWindow;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.addToDay;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.cafeStopRadiusKm;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.collectUsedIndices;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.insertableFilter;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.isSameIndex;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.pickNearbyCandidate;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.replaceInDay;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 끼니 선택·슬롯 배정·보충과 먹는 스텝 분리. */
@Slf4j
final class MealPlanner {

    private MealPlanner() {
    }

    static boolean ensureMealSlots(DayState day, List<StepData> daySteps, List<PlaceCandidate> candidates,
                                   Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                   boolean lastDay, java.time.LocalDate startDate, String transportPref,
                                   InvariantMemo memo, ScheduleConfig cfg,
                                   java.util.function.Predicate<PlaceCandidate> underTypeCap) {
        boolean changed = false;
        java.util.function.Predicate<StepData> isMeal = InvariantChecker.mealJudge(day, candidates);

        if (day.lunchInWindow() && !hasMealInWindow(daySteps, LUNCH_START, day.lunchWindowEnd(), isMeal)) {
            // centroid는 좌표 불명 day에서 null일 수 있고 List.of는 null을 거부해 singletonList를 쓴다.
            changed |= insertMealWithRelaxation(day, candidates, spare, usedIndices, maxPerDay,
                    Collections.singletonList(centroidOf(day.placeIndices, candidates)),
                    "점심", startDate, transportPref, memo, cfg, underTypeCap);
        }

        // 마지막날 저녁은 귀가 시각이 늦을 때만 필요하다(출발 허브 도착이 19:30 전이면 저녁을 먹지 않음)
        boolean dinnerRequired = (!lastDay || departsAfterDinner(daySteps, candidates, day)) && day.dinnerInWindow();
        if (dinnerRequired && !hasMealInWindow(daySteps, DINNER_START, day.dinnerWindowEnd(), isMeal)) {
            // 앵커는 숙소 → 출발 허브 → 방문지 중심 순이다. 마지막 날은 숙소가 없어 허브 앵커가 없으면 공항 길목 식당을 못 찾는다.
            List<double[]> anchors = new ArrayList<>();
            anchors.add(accommodationCoords(day, candidates));
            if (day.departureHubIndex != null) {
                anchors.add(coordsOf(byIndex(candidates, day.departureHubIndex)));
            }
            anchors.add(centroidOf(day.placeIndices, candidates));
            changed |= insertMealWithRelaxation(day, candidates, spare, usedIndices, maxPerDay,
                    anchors, "저녁", startDate, transportPref, memo, cfg, underTypeCap);
        }
        return changed;
    }

    /**
     * 필수 식사 슬롯을 L1 → L2 → L3로 제약을 완화하며 채운다. 세 단계 모두 실패해야 미충족으로 기록한다.
     */
    private static boolean insertMealWithRelaxation(DayState day, List<PlaceCandidate> candidates,
                                                    Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                                    List<double[]> anchors, String slotName,
                                                    java.time.LocalDate startDate, String transportPref,
                                                    InvariantMemo memo, ScheduleConfig cfg,
                                                    java.util.function.Predicate<PlaceCandidate> underTypeCap) {
        // 보충할 자리의 시각을 알려줘야 "그 시각에 여는 식당"만 후보가 된다.
        Integer slotStart = "저녁".equals(slotName) ? DINNER_START : LUNCH_START;
        java.util.function.Predicate<PlaceCandidate> variety = VarietyLedger.admitsOnDay(day, candidates);
        java.util.function.Predicate<PlaceCandidate> otherGroup = VarietyLedger.avoidsGroupOnDay(day, candidates);
        for (RelaxLevel level : RelaxLevel.values()) {
            java.util.function.Predicate<PlaceCandidate> filter =
                    insertableFilter(day, candidates, startDate, transportPref, memo, level, slotStart, cfg);
            // 같은 완화 단계 안에서도 반복을 덜 만드는 식당부터 찾는다. 그날 같은 종류가 여행 전체 상한 초과보다 눈에 띄어 먼저 피한다.
            // 모두 없으면 반복을 감수하고 끼니를 채운다.
            for (java.util.function.Predicate<PlaceCandidate> attempt
                    : List.of(filter.and(underTypeCap).and(variety).and(otherGroup), filter.and(underTypeCap).and(variety),
                    filter.and(variety), filter.and(underTypeCap), filter)) {
                for (double[] anchor : anchors) {
                    if (anchor == null) continue;
                    if (insertMeal(day, candidates, spare, usedIndices, maxPerDay, anchor, slotName, attempt, memo)) {
                        if (level != RelaxLevel.L1) {
                            log.info("{} 슬롯 보충에 제약 완화 {} 적용: day={}", slotName, level, day.dayNumber);
                        }
                        return true;
                    }
                }
            }
        }
        logMealFillFailure(day, candidates, usedIndices, slotName);
        return false;
    }

    /** 필수 식사 삽입. pace 상한을 {@value ScheduleTuning#MEAL_QUOTA_SLACK}개까지 넘겨도 끼니를 우선한다. */
    private static boolean insertMeal(DayState day, List<PlaceCandidate> candidates,
                                      Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                      double[] anchor, String slotName,
                                      java.util.function.Predicate<PlaceCandidate> openOnDay, InvariantMemo memo) {
        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, anchor,
                openOnDay.and(PlaceCandidate::mealEligible)
                        .and(c -> PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country())));
        // 실패 로그는 완화 단계를 전부 소진한 호출부가 남긴다. 여기서 찍으면 L1 실패마다 보충 실패로 보인다.
        if (pick == null) return false;

        if (day.placeIndices.size() < maxPerDay + MEAL_QUOTA_SLACK) {
            addToDay(day, pick, spare, usedIndices);
            memo.recordInsert(day.dayNumber, pick);
            log.info("{} 슬롯 보충: day={} {}", slotName, day.dayNumber, nameOf(candidates, pick));
            return true;
        }

        // quota가 꽉 차도 끼니는 빼지 않는다. 개수를 늘리는 대신 그날 가장 많이 반복된 비식사 장소와 교체한다.
        Integer victim = mostRepeatedNonMeal(day, candidates);
        if (victim == null) {
            log.info("{} 슬롯 보충 실패(quota 여유 없음·교체 대상 없음): day={}", slotName, day.dayNumber);
            return false;
        }
        replaceInDay(day, victim, pick);
        spare.remove(pick);
        spare.addLast(victim);
        usedIndices.add(pick);
        usedIndices.remove(victim);
        memo.recordInsert(day.dayNumber, pick);
        log.info("{} 슬롯 교체 보충: day={} {} → {}", slotName, day.dayNumber,
                nameOf(candidates, victim), nameOf(candidates, pick));
        return true;
    }

    /**
     * 식사 보충이 후보를 못 찾았을 때 "무엇이 막았는지"를 단계별 잔여 수로 남긴다.
     * "후보 없음"만 찍히면 데이터 부족인지 필터가 과한지 구분할 수 없다.
     */
    private static void logMealFillFailure(DayState day, List<PlaceCandidate> candidates,
                                           Set<Integer> usedIndices, String slotName) {
        long meal = candidates.stream().filter(PlaceCandidate::mealEligible).count();
        long unused = candidates.stream()
                .filter(PlaceCandidate::mealEligible)
                .filter(c -> !usedIndices.contains(c.index()))
                .count();
        log.info("{} 슬롯 보충 실패: day={} (식사가능 {}곳 중 미사용 {}곳 — 반경·예산·휴무 필터를 모두 통과한 후보 0)",
                slotName, day.dayNumber, meal, unused);
    }

    /**
     * 활동 버킷에서 먹는 스텝이 연달아 붙지 않게 자리를 바꾼다.
     *
     * <p>버킷 안에서 먹는 스텝이 인접한 경우와, 버킷 마지막이 먹는 스텝이고 바로 뒤가 끼니인 경우를 같은 규칙으로 본다.
     * 버킷 순서가 경로순이라 가까운 가게끼리 붙고, 끼니가 {@code LUNCH_START}/{@code DINNER_START}에 핀되며 간격이 사라진다.
     *
     * <p>뒤쪽의 먹는 스텝을 더 뒤의 먹지 않는 활동과 맞바꾸고, 상대가 없으면 스텝을 버리지 않고 둔다.
     *
     * @param mealFollows 이 버킷 바로 뒤에 끼니 슬롯이 오는지. 그러면 마지막 자리도 경계다
     */
    static void separateFoodSteps(DayState day, List<Integer> bucket, List<PlaceCandidate> candidates,
                                  boolean mealFollows, String mealLabel) {
        if (bucket == null || bucket.size() < 2) return;

        int last = bucket.size() - 1;

        // ① 끼니 경계를 먼저 비우고 그 자리는 ②의 교환 대상에서 뺀다. 두 패스가 서로를 되돌리면 같은 쌍을 무한히 맞바꾼다.
        int forbidden = -1;
        if (mealFollows) {
            PlaceCandidate tail = byIndex(candidates, bucket.get(last));
            if (isFoodCand(tail)
                    && swapWithLaterActivity(day, bucket, candidates, last, tail, mealLabel + " 직전", -1)) {
                forbidden = last;
            }
        }

        // ② 버킷 안의 인접 쌍. 경계에서 뺀 먹는 스텝이 다른 먹는 스텝 옆으로 갈 수 있어 경계만으로는 부족하다.
        for (int i = 1; i < bucket.size(); i++) {
            if (i == forbidden) continue;
            PlaceCandidate curr = byIndex(candidates, bucket.get(i));
            if (!isFoodCand(curr) || !isFoodCand(byIndex(candidates, bucket.get(i - 1)))) continue;
            swapWithLaterActivity(day, bucket, candidates, i, curr, "직전 스텝도 먹는 곳", forbidden);
        }
    }

    /**
     * {@code pos}의 먹는 스텝을 뒤쪽(없으면 앞쪽)의 먹지 않는 활동과 맞바꾼다.
     * 숙소·허브는 상대가 아니다({@link #isSwappableActivity}).
     *
     * @param forbidden 교환 대상에서 뺄 자리(없으면 -1). 이미 비워 둔 끼니 경계를 다시 먹는 스텝으로 채우지 않는다.
     */
    private static boolean swapWithLaterActivity(DayState day, List<Integer> bucket,
                                                 List<PlaceCandidate> candidates, int pos,
                                                 PlaceCandidate moving, String reason, int forbidden) {
        for (int j = bucket.size() - 1; j >= 0; j--) {
            if (j == pos || j == forbidden) continue;
            PlaceCandidate c = byIndex(candidates, bucket.get(j));
            if (c == null || isFoodCand(c) || !isSwappableActivity(day, bucket.get(j), c)) continue;
            Collections.swap(bucket, pos, j);
            log.info("먹는 스텝 분리: day={} {} ({}) ↔ {}",
                    day.dayNumber, moving.name(), reason, c.name());
            return true;
        }
        return false;
    }

    /**
     * 버킷 안에서 자리를 바꿔도 되는 활동인지. 숙소·허브는 제외한다.
     *
     * <p>도보 연속성 보정이 전날 숙소를 활동 버킷에 넣을 수 있어, 거르지 않으면 숙소가 끼니 직전으로 끌려가
     * 체크인 뒤 장거리 저녁이 된다.
     */
    private static boolean isSwappableActivity(DayState day, Integer idx, PlaceCandidate c) {
        if (isSameIndex(day.accommodationIndex, idx)
                || isSameIndex(day.arrivalHubIndex, idx)
                || isSameIndex(day.departureHubIndex, idx)) {
            return false;
        }
        String major = PlaceCategoryConstants.majorCategory(c.category());
        return !"LODGING".equals(major) && !"TRANSIT_HUB".equals(major);
    }

    /**
     * 하루 식사 3회 상한. 초과분은 spare로 트림하되 userSelected 식당을 우선 남긴다.
     * 슬롯 매핑이 경로순 기반이라 남긴 목록은 경로 순서를 유지한다.
     */
    static List<Integer> selectMeals(List<Integer> diningAll, List<PlaceCandidate> candidates,
                                     Deque<Integer> spareIndices) {
        if (diningAll.size() <= 3) return new ArrayList<>(diningAll);

        Set<Integer> keep = new LinkedHashSet<>();
        for (Integer idx : diningAll) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.userSelected() && keep.size() < 3) keep.add(idx);
        }
        for (Integer idx : diningAll) {
            if (keep.size() >= 3) break;
            keep.add(idx);
        }

        List<Integer> meals = new ArrayList<>();
        for (Integer idx : diningAll) {
            if (keep.contains(idx)) meals.add(idx);
            else spareIndices.addLast(idx);
        }
        return meals;
    }

    /**
     * 아침 슬롯이 허용되지 않는 날(도착일 등)에 3식이 잡혀 있으면 경로상 첫 식사를 spare로 돌린다.
     * 도착일의 첫 스텝은 관광 또는 점심부터 시작하는 것이 자연스럽다.
     */
    static List<Integer> dropBreakfastIfNotAllowed(List<Integer> meals, DayState day,
                                                   List<PlaceCandidate> candidates,
                                                   Deque<Integer> spareIndices, boolean allowBreakfast) {
        if (allowBreakfast || meals.size() < 3) return meals;

        Integer candidate = meals.get(0);
        PlaceCandidate bc = byIndex(candidates, candidate);
        if (bc != null && bc.userSelected()) return meals; // 사용자 필수 식당은 버리지 않는다

        List<Integer> remaining = new ArrayList<>(meals);
        remaining.remove(candidate);
        day.placeIndices.remove(candidate);
        spareIndices.addLast(candidate);
        log.info("아침 슬롯 미허용(도착일/숙소 출발 아님) — 식사 1건 제외: day={} {}",
                day.dayNumber, bc != null ? bc.name() : candidate);
        return remaining;
    }

    /**
     * 3식일 때 숙소에서 {@link ScheduleTuning#BREAKFAST_MAX_KM_FROM_ACCOMMODATION}km를 넘는 아침은 spare로 돌려 아침부터 원정을 막는다.
     * 사용자 필수 식당은 버리지 않는다. 슬롯 확정 전이라 경로상 첫 식사를 아침 후보로 본다.
     */
    static List<Integer> dropDistantBreakfast(List<Integer> meals, DayState day,
                                              List<PlaceCandidate> candidates, Deque<Integer> spareIndices) {
        if (meals.size() < 3) return meals;
        double[] hotel = accommodationCoords(day, candidates);
        if (hotel == null) return meals;

        Integer candidate = meals.get(0);
        PlaceCandidate bc = byIndex(candidates, candidate);
        double[] coord = coordsOf(bc);
        if (coord == null || (bc != null && bc.userSelected())) return meals;
        if (GeoUtils.haversine(hotel, coord) <= BREAKFAST_MAX_KM_FROM_ACCOMMODATION) return meals;

        List<Integer> remaining = new ArrayList<>(meals);
        remaining.remove(candidate);
        day.placeIndices.remove(candidate);
        spareIndices.addLast(candidate);
        log.info("아침 식사 생략(숙소에서 {}km 초과): day={} {}",
                String.format("%.0f", BREAKFAST_MAX_KM_FROM_ACCOMMODATION), day.dayNumber,
                bc != null ? bc.name() : candidate);
        return remaining;
    }

    /**
     * 확정된 아침이 성립하는지. 출발지(전날 숙소, 없으면 그날 숙소)에서
     * {@value ScheduleTuning#BREAKFAST_MAX_KM_FROM_ACCOMMODATION}km 이내이고, 식사가 끝난 뒤
     * 점심까지 {@value ScheduleTuning#BREAKFAST_MIN_GAP_BEFORE_LUNCH_MINUTES}분 이상 남아야 한다.
     */
    static boolean isReasonableBreakfast(DayState day, PlaceCandidate breakfast, PlaceCandidate startFrom,
                                         List<PlaceCandidate> candidates, String transportPref,
                                         int morningStart) {
        if (breakfast == null || breakfast.userSelected()) return true;
        double[] origin = startFrom != null ? coordsOf(startFrom) : accommodationCoords(day, candidates);
        double[] coord = coordsOf(breakfast);
        if (origin == null || coord == null) return true;
        if (GeoUtils.haversine(origin, coord) > BREAKFAST_MAX_KM_FROM_ACCOMMODATION) return false;
        TransportLeg leg = computeLeg(startFrom, breakfast, transportPref, day.dayNumber);
        int end = morningStart + (leg != null ? leg.durationMin() : 0) + DINING_VISIT_MINUTES;
        return end <= LUNCH_START - BREAKFAST_MIN_GAP_BEFORE_LUNCH_MINUTES;
    }

    /**
     * 확정된 활동 버킷을 기준으로 식사를 아침/점심/저녁 슬롯에 배정한다.
     * <ul>
     *   <li><b>저녁</b>: 그날 숙소에 가장 가까운 식당. 식후 장거리 귀가를 구조적으로 막는다</li>
     *   <li><b>점심</b>: 오전 마지막 활동 → 식당 → 오후 첫 활동 우회가 가장 작은 식당.
     *       버킷이 비어 있으면 하루 시작점/그날 중심을 앵커로 쓴다</li>
     *   <li><b>아침</b>: 남은 하나</li>
     * </ul>
     * 사용자 필수 식당(userSelected)은 재배치 대상에서 제외한다(경로 순서 유지).
     */
    static MealSlots assignMealSlots(List<Integer> meals, DayState day, List<PlaceCandidate> candidates,
                                     List<Integer> morning, List<Integer> afternoon,
                                     PlaceCandidate startFrom, double[] dayCentroid,
                                     Deque<Integer> spareIndices, int breakfastStart) {
        if (meals.isEmpty()) return new MealSlots(null, null, null);

        java.time.DayOfWeek dow = day.dayOfWeek;
        List<Integer> remaining = new ArrayList<>(meals);

        // 슬롯별로 그 시각에 영업하는 후보만 남겨, 늦게 여는 식당은 쫓아내지 않고도 저녁 자리를 찾는다.
        List<Integer> lunchCapable = openAtSlot(remaining, candidates, dow, LUNCH_START);
        List<Integer> dinnerPool = openAtSlot(remaining, candidates, dow, DINNER_START);
        // 점심 가능한 식당이 하나뿐이면 저녁으로 가져갈 때 점심이 비어 저녁 후보에서 뺀다.
        if (lunchCapable.size() == 1 && dinnerPool.size() > 1) dinnerPool.remove(lunchCapable.get(0));

        // ── 저녁: 숙소에 가장 가까운 식당(식후 장거리 귀가를 구조적으로 차단) ──
        Integer dinner = null;
        if (day.dinnerInWindow() && remaining.size() > 1 && !dinnerPool.isEmpty()) {
            double[] hotel = accommodationCoords(day, candidates);
            dinner = hotel != null
                    ? nearestMeal(dinnerPool, candidates, hotel)
                    : dinnerPool.get(dinnerPool.size() - 1);
            remaining.remove(dinner);
        }

        // ── 점심: 오전 마지막 활동 → 식당 → 오후 첫 활동 우회가 가장 작은 식당 ──
        Integer lunch = null;
        List<Integer> lunchPool = day.lunchInWindow() ? openAtSlot(remaining, candidates, dow, LUNCH_START) : List.of();
        if (!lunchPool.isEmpty()) {
            double[] from = lastCoord(morning, candidates);
            if (from == null) from = coordsOf(startFrom);
            if (from == null) from = dayCentroid;
            double[] to = firstCoord(afternoon, candidates);
            // 영업시간에 맞는 식당이 없으면 저녁은 null로 남고 ensureMealSlots가 채우므로 앵커로 쓸 때 null을 확인한다.
            if (to == null && dinner != null) to = coordsOf(byIndex(candidates, dinner));
            if (to == null) to = dayCentroid;

            lunch = minDetourMeal(lunchPool, candidates, from, to);
            remaining.remove(lunch);
        }

        // 한 끼뿐이라 위에서 저녁을 건너뛰었는데 그 한 끼가 점심에 닫혀 있으면, 저녁으로 돌린다.
        if (dinner == null && day.dinnerInWindow() && !remaining.isEmpty()) {
            List<Integer> late = openAtSlot(remaining, candidates, dow, DINNER_START);
            if (!late.isEmpty()) {
                double[] hotel = accommodationCoords(day, candidates);
                dinner = hotel != null ? nearestMeal(late, candidates, hotel) : late.get(late.size() - 1);
                remaining.remove(dinner);
            }
        }

        // ── 아침: 남은 하나. 그 시각에 닫혀 있으면 두지 않는다 ──
        Integer breakfast = null;
        for (Integer idx : new ArrayList<>(remaining)) {
            if (breakfast != null) break;
            if (isOpenDuring(byIndex(candidates, idx), dow, breakfastStart,
                    breakfastStart + DINING_VISIT_MINUTES)) {
                breakfast = idx;
                remaining.remove(idx);
            }
        }

        // 어느 슬롯에도 못 들어간 식당을 두면 placeIndices에만 남는 유령이 된다.
        // spare로 돌리면 ensureMealSlots가 그 시각에 열린 식당을 찾고, 못 찾으면 안내가 나간다.
        for (Integer idx : remaining) {
            day.placeIndices.remove(Integer.valueOf(idx));
            spareIndices.addLast(idx);
            PlaceCandidate c = byIndex(candidates, idx);
            log.info("식사 영업시간 불일치 — spare 전환: day={}({}) {} [{}]", day.dayNumber, dow,
                    c != null ? c.name() : idx, c != null ? c.openingHours() : "?");
        }
        return new MealSlots(breakfast, lunch, dinner);
    }

    /** from→식당→to 우회가 가장 작은 식사. 사용자 필수 식당은 재배치 대상에서 제외한다. */
    private static Integer minDetourMeal(List<Integer> meals, List<PlaceCandidate> candidates,
                                         double[] from, double[] to) {
        Integer best = null;
        double bestDetour = Double.MAX_VALUE;
        for (Integer m : meals) {
            PlaceCandidate c = byIndex(candidates, m);
            if (c != null && c.userSelected()) continue;
            double[] coord = coordsOf(c);
            if (coord == null) continue;
            double detour = detourVia(from, coord, to);
            if (detour < bestDetour) {
                bestDetour = detour;
                best = m;
            }
        }
        return best != null ? best : meals.get(0);
    }

    /** anchor에 가장 가까운 식사 후보. 사용자 필수 식당은 재배치 대상에서 제외한다. */
    private static Integer nearestMeal(List<Integer> meals, List<PlaceCandidate> candidates, double[] anchor) {
        Integer nearest = null;
        double best = Double.MAX_VALUE;
        for (Integer m : meals) {
            PlaceCandidate c = byIndex(candidates, m);
            if (c != null && c.userSelected()) continue;
            double[] coord = coordsOf(c);
            if (coord == null) continue;
            double d = GeoUtils.haversine(coord, anchor);
            if (d < best) {
                best = d;
                nearest = m;
            }
        }
        // 전부 userSelected거나 좌표 불명이면 경로 순서를 그대로 따른다.
        return nearest != null ? nearest : meals.get(meals.size() - 1);
    }

    /**
     * 마지막 날을 뺀 매일 오후에 들를 카페·디저트 1곳을 정한다({@link ScheduleConfig#cafeStop}일 때).
     * 그날 카페형 장소가 있으면 표시만 하고, 없으면 spare → 미사용 후보 순으로 중심 근처 인기 장소를 넣는다.
     * 카페는 60분 남짓이라 식사처럼 하루 장소 수 상한을 넘을 수 있다.
     */
    static void ensureCafeStops(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                ScheduleConfig cfg, String transportPref) {
        if (!cfg.cafeStop) return;
        Set<Integer> used = collectUsedIndices(days);
        double maxFromCenter = cafeStopRadiusKm(transportPref, cfg);

        for (int i = 0; i < days.size(); i++) {
            if (DayState.endsTrip(days, i)) continue;
            DayState day = days.get(i);
            Integer existing = day.placeIndices.stream()
                    .filter(idx -> isCafeLike(byIndex(candidates, idx)))
                    .findFirst().orElse(null);
            if (existing != null) {
                day.cafeStopIndex = existing;
                continue;
            }
            double[] center = centroidOf(day.placeIndices, candidates);
            if (center == null) continue;

            java.util.function.Predicate<PlaceCandidate> eligible = c -> isCafeLike(c) && isVisitableFill(c)
                    && !used.contains(c.index()) && coordsOf(c) != null
                    && GeoUtils.haversine(center, coordsOf(c)) <= maxFromCenter;
            Comparator<PlaceCandidate> score = Comparator.comparingDouble(c ->
                    PlaceProminence.of(c) - GeoUtils.haversine(center, coordsOf(c)) * GAP_FILL_DETOUR_WEIGHT);
            Integer pick = spare.stream().map(idx -> byIndex(candidates, idx))
                    .filter(Objects::nonNull).filter(eligible).max(score)
                    .or(() -> candidates.stream().filter(eligible).max(score))
                    .map(PlaceCandidate::index).orElse(null);
            if (pick == null) {
                log.info("카페 스톱 후보 없음: day={} (중심 {}km 이내 카페·디저트 없음)",
                        day.dayNumber, String.format("%.0f", maxFromCenter));
                continue;
            }
            day.placeIndices.add(pick);
            spare.remove(pick);
            used.add(pick);
            day.cafeStopIndex = pick;
            log.info("카페 스톱: day={} {}", day.dayNumber, nameOf(candidates, pick));
        }
    }

    /** 그날에서 같은 세부 유형이 가장 많이 반복된 비식사 방문지(사용자 필수 제외). 없으면 null. */
    private static Integer mostRepeatedNonMeal(DayState day, List<PlaceCandidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null) counts.merge(subTypeOf(c), 1, Integer::sum);
        }
        Integer best = null;
        int bestCount = 0;
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c == null || c.keepsPlace() || c.mealEligible()) continue;
            // 카페 스톱·카테고리 유일 대표는 끼니 자리를 만들려고 빼지 않는다.
            if (idx.equals(day.cafeStopIndex) || day.coverageCriticalIndices.contains(idx)) continue;
            int count = counts.getOrDefault(subTypeOf(c), 0);
            if (count > bestCount) {
                bestCount = count;
                best = idx;
            }
        }
        return best;
    }
}
