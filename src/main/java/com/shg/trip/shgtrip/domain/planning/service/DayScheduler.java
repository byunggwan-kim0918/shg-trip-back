package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.accommodationCoords;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isDiningCand;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.placeKey;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.buildStep;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.computeLeg;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.dayBudgets;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.resolveVisitMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.timePreference;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.cafeStopProtected;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.coverageProtected;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.isSameIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.formatMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 하루 방문 순서를 시각이 붙은 스텝으로 확정하고 출발 기한에 맞춰 줄인다. */
@Slf4j
final class DayScheduler {

    private DayScheduler() {
    }

    /**
     * 전체 day를 순회하며 순서 확정 → 거리 예산 → 시간 배정을 수행한다. 트림·보충이 day.placeIndices·spare·usedIndices를
     * 바꾸므로 다시 부르기 전 상태는 {@link PlanState#checkpoint()}로 잡아 둔다.
     */
    static List<StepData> scheduleAll(PlanState s) {
        List<StepData> steps = new ArrayList<>();
        for (int i = 0; i < s.days.size(); i++) {
            DayState day = s.days.get(i);
            // 첫날은 도착 허브, 그 외는 전날 묵은 숙소에서 시작한다.
            double[] startAnchor = day.arrivalHubIndex != null
                    ? coordsOf(byIndex(s.candidates, day.arrivalHubIndex))
                    : (i > 0 ? accommodationCoords(s.days.get(i - 1), s.candidates) : null);
            // 그날 숙소 또는 마지막 날 출발 허브에서 끝난다.
            double[] endAnchor = day.accommodationIndex != null
                    ? coordsOf(byIndex(s.candidates, day.accommodationIndex))
                    : (day.departureHubIndex != null ? coordsOf(byIndex(s.candidates, day.departureHubIndex)) : null);

            List<Integer> ordered = RouteOrdering.orderDay(day, s.candidates, s.pairs, s.highlights, s.rests,
                    startAnchor, endAnchor);
            ordered = applyWalkContinuity(ordered, day, s.days, i, s.candidates, s.transportPref);
            ordered = DistanceBudget.enforceDistanceBudget(day, ordered, s.candidates, s.spare, s.usedIndices,
                    s.transportPref, s.cfg);
            double[] dayCentroid = centroidOf(day.placeIndices, s.candidates);
            // 첫 스텝의 이동 정보와 도착 시각은 전날 숙소를 출발지로 계산한다.
            PlaceCandidate startFrom = (i > 0 && day.arrivalHubIndex == null
                    && s.days.get(i - 1).accommodationIndex != null)
                    ? byIndex(s.candidates, s.days.get(i - 1).accommodationIndex) : null;
            steps.addAll(scheduleDay(day, ordered, s.candidates, s.spare, s.cfg,
                    dayCentroid, s.usedIndices, s.transportPref, startFrom, s.maxPerDay));
        }
        return steps;
    }

    /**
     * 출발 허브 도착이 기한(기본 {@value ScheduleTuning#DEPARTURE_HUB_LATEST_MINUTES}분)을 넘을 것으로 보이면
     * 보호 대상이 아닌 활동을 <b>뒤에서부터</b> 잘라 귀가를 앞당긴다.
     *
     * <p>거리 예산과는 다른 축이다. 이동이 상한 안이어도 체류시간이 쌓이면 심야 귀가가 된다.
     */
    private static void trimSequenceForDepartureTime(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                                     Deque<Integer> spareIndices, ScheduleConfig cfg,
                                                     PlaceCandidate origin, String transportPref,
                                                     Set<Integer> protectedIdx,
                                                     Integer breakfastIdx, Integer lunchIdx, Integer dinnerIdx) {
        if (day.departureHubIndex == null) return;

        boolean userDeadline = day.departureDeadline != null;
        int attempts = userDeadline ? seq.size() : 3;
        for (int guard = 0; guard < attempts; guard++) {
            int arrival = estimateDepartureHubArrival(day, seq, candidates, cfg, origin, transportPref,
                    breakfastIdx, lunchIdx, dinnerIdx);
            if (arrival <= departureDeadline(day)) return;

            Integer victim = null;
            for (int i = seq.size() - 1; i >= 0; i--) {
                Integer idx = seq.get(i);
                if (protectedIdx.contains(idx)) continue;
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.userSelected()) continue;
                victim = idx;
                break;
            }
            // 사용자가 준 출발 시각(비행기·기차)은 넘길 수 없어 대표 명소·식사 보호보다 앞선다.
            // 기본 마감(21:00)일 때도 보호 장소는 자르고 끼니만 남긴다. 저녁 뒤 활동을 지키면 공항에 23시에 닿는다.
            if (victim == null) {
                for (int i = seq.size() - 1; i >= 0; i--) {
                    Integer idx = seq.get(i);
                    if (isSameIndex(day.departureHubIndex, idx) || isSameIndex(day.arrivalHubIndex, idx)) continue;
                    if (!userDeadline && (isSameIndex(breakfastIdx, idx) || isSameIndex(lunchIdx, idx)
                            || isSameIndex(dinnerIdx, idx))) continue;
                    PlaceCandidate c = byIndex(candidates, idx);
                    if (c == null || c.userSelected()) continue;
                    victim = idx;
                    break;
                }
            }
            if (victim == null) {
                log.info("귀가 시각 초과({}) — 트림 가능한 활동 없음: day={}",
                        formatMinutes(Math.min(arrival, END_OF_DAY_MINUTES)), day.dayNumber);
                return;
            }
            seq.remove(victim);
            day.placeIndices.remove(victim);
            spareIndices.addLast(victim);
            log.info("귀가 시각 초과({} > {}) — 트림: day={} {}",
                    formatMinutes(Math.min(arrival, END_OF_DAY_MINUTES)),
                    formatMinutes(departureDeadline(day)), day.dayNumber, nameOf(candidates, victim));
        }
    }

    /** 확정 루프와 같은 규칙(체류+이동+식사 슬롯 하한)으로 출발 허브 도착 시각을 추정한다. */
    private static int estimateDepartureHubArrival(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                                   ScheduleConfig cfg, PlaceCandidate origin, String transportPref,
                                                   Integer breakfastIdx, Integer lunchIdx, Integer dinnerIdx) {
        int minutes = cfg.morningStart;
        PlaceCandidate prev = origin;

        for (Integer idx : seq) {
            PlaceCandidate cand = byIndex(candidates, idx);
            if (cand == null) continue;

            TransportLeg leg = computeLeg(prev, cand, transportPref, day.dayNumber);
            if (leg != null) minutes += leg.durationMin();

            if (isSameIndex(breakfastIdx, idx)) minutes = Math.max(minutes, cfg.morningStart);
            if (isSameIndex(lunchIdx, idx)) minutes = Math.max(minutes, LUNCH_START);
            if (isSameIndex(dinnerIdx, idx)) minutes = Math.max(minutes, DINNER_START);

            if (isSameIndex(day.departureHubIndex, idx)) return minutes;

            if (isSameIndex(day.accommodationIndex, idx)
                    || PlaceCategoryConstants.isAccommodation(cand.category())) {
                minutes += ACCOMMODATION_ARRIVAL_MINUTES;
            } else if (isDiningCand(cand)) {
                minutes += DINING_VISIT_MINUTES;
            } else {
                minutes += resolveVisitMinutes(cand);
            }
            prev = cand;
        }
        return minutes;
    }

    /**
     * 야외·주간 장소의 시작 상한. 테마의 eveningCap에서 역산하고 15:00 아래로는 내리지 않는다.
     * 여행지 규칙이 아니라 <b>사용자가 고른 테마에서만</b> 파생된다.
     */
    private static int daytimeLatestStart(ScheduleConfig cfg) {
        int cap = cfg != null ? cfg.eveningCap : DEFAULT_EVENING_CAP_MINUTES;
        return Math.max(15 * 60, cap - 180);
    }

    /**
     * 확정된 day 순서에 시간·교통·대안을 부여한다.
     *
     * <p>경로 순서대로 시간을 누적하면 저녁 식당이 경로상 앞일 때 뒤 활동이 전부 저녁 이후로 밀린다. 그래서
     * <ul>
     *   <li>DINING 개수로 슬롯을 정한다(1→점심, 2→점심+저녁, 3→아침+점심+저녁, 4번째부터 spare).
     *       첫=점심/마지막=저녁으로 정하면 브런치를 점심으로 오판한다.</li>
     *   <li>비식사 활동은 상대 순서를 지키며 오전/오후/저녁 시간창 용량만큼 채우고 초과분은 spare로 보낸다.</li>
     * </ul>
     */
    private static List<StepData> scheduleDay(DayState day, List<Integer> orderedMain,
                                              List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                              ScheduleConfig cfg, double[] dayCentroid,
                                              Set<Integer> usedIndices, String transportPref,
                                              PlaceCandidate startFrom, int maxPerDay) {
        cfg = withDepartureLegLead(day, cfg.forDay(day), candidates, startFrom, transportPref);
        List<Integer> diningAll = orderedMain.stream()
                .filter(idx -> {
                    PlaceCandidate c = byIndex(candidates, idx);
                    // 바·디저트·베이커리·간식과 집합 POI는 한 끼가 아니라 식사 슬롯에서 뺀다. 검색 슬롯과 같은 기준(isMealPlace)이다.
                    return c != null && c.mealEligible();
                })
                .collect(Collectors.toList());
        List<Integer> meals = MealPlanner.selectMeals(diningAll, candidates, spareIndices);
        // spare로 보낸 장소를 그날 목록에 남기면 쿼터를 점유해 빈 시간 채우기가 시도되지 않는다.
        for (Integer idx : diningAll) {
            if (!meals.contains(idx)) day.placeIndices.remove(idx);
        }
        // 아침 슬롯은 숙소에서 출발하는 날에만 성립한다. 도착일에 두면 공항 옆 식당이 늦은 아침이 된다.
        boolean allowBreakfast = day.arrivalHubIndex == null && startFrom != null
                && cfg.morningStart <= BREAKFAST_LATEST_START;
        meals = MealPlanner.dropBreakfastIfNotAllowed(meals, day, candidates, spareIndices, allowBreakfast);
        // 숙소에서 먼 아침을 버려 아침부터 원정을 막는다. 남은 개수가 버킷 용량을 정한다.
        meals = MealPlanner.dropDistantBreakfast(meals, day, candidates, spareIndices);
        Set<Integer> mealSet = new HashSet<>(diningAll);

        // 비식사 활동을 시간대 적합성(야외=주간, Bar=저녁)을 지키며 오전/오후/저녁 버킷에 나누고, 못 넣는 활동은 spare로 트림한다.
        List<Integer> activities = orderedMain.stream()
                .filter(idx -> !mealSet.contains(idx))
                .collect(Collectors.toList());
        int[] budgets = dayBudgets(day, cfg, meals.size(), arrivalLeadMinutes(day, orderedMain, candidates, transportPref));
        // 도착 허브는 활동 목록에 없지만 오전 시간을 먹는다. 빼지 않으면 오전 예산이 부풀어 점심이 창 밖으로 밀린다.
        if (day.arrivalHubIndex != null) {
            PlaceCandidate hub = byIndex(candidates, day.arrivalHubIndex);
            if (hub != null) {
                budgets[0] = Math.max(0, budgets[0] - resolveVisitMinutes(hub) - INTRA_BUCKET_TRAVEL_MINUTES);
            }
        }
        // 버킷은 실제 체류시간으로 채워야 시계와 용량이 어긋나지 않는다.
        int[] usedM = {0}, usedA = {0}, usedE = {0};
        List<Integer> morning = new ArrayList<>(), afternoon = new ArrayList<>(), evening = new ArrayList<>();
        for (Integer idx : activities) {
            PlaceCandidate actCand = byIndex(candidates, idx);
            // 카페 스톱은 점심과 저녁 사이(오후)에 둔다. 오전 버킷부터 채우면 아침 9시 카페가 된다.
            if (cfg.cafeStop && isSameIndex(day.cafeStopIndex, idx)) {
                // 늦게 도착한 날은 오후가 짧아 카페까지 넣으면 저녁이 시간대 밖으로 밀린다
                if (day.hasUserWindow() && !afternoon.isEmpty()
                        && usedA[0] + resolveVisitMinutes(actCand) + INTRA_BUCKET_TRAVEL_MINUTES > budgets[1]) {
                    day.placeIndices.remove(idx);
                    spareIndices.addLast(idx);
                    log.info("시간대 예산 초과로 카페 생략: day={} {}", day.dayNumber, nameOf(candidates, idx));
                    continue;
                }
                afternoon.add(idx);
                usedA[0] += resolveVisitMinutes(actCand) + INTRA_BUCKET_TRAVEL_MINUTES;
                continue;
            }
            boolean placed = switch (timePreference(actCand)) {
                case DAYTIME -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, false)
                        || addIfFitsBudget(afternoon, budgets[1], usedA, idx, actCand, true);
                case EVENING -> addIfFitsBudget(evening, budgets[2], usedE, idx, actCand, false);
                // 긴 등산은 오전에만 두고, 오전 예산보다 길어도 빈 오전에는 넣는다. 점심이 밀려도 오후 등산보다 현실적이다.
                case MORNING_ONLY -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, true);
                case FLEXIBLE -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, false)
                        || addIfFitsBudget(afternoon, budgets[1], usedA, idx, actCand, true)
                        || addIfFitsBudget(evening, budgets[2], usedE, idx, actCand, false);
            };
            if (!placed) {
                // 사용자 필수 장소는 버킷 용량에 밀려도 강제 편입한다. 시각은 23:59 클램프가 안전망이다.
                if (actCand != null && actCand.keepsPlace()) {
                    // 오후에 몰아넣으면 야경 명소가 낮에 가므로 시간대가 정해진 장소는 그 버킷으로 보낸다.
                    switch (timePreference(actCand)) {
                        case EVENING -> evening.add(idx);
                        case MORNING_ONLY -> morning.add(idx);
                        default -> afternoon.add(idx);
                    }
                } else {
                    day.placeIndices.remove(idx);
                    spareIndices.addLast(idx);
                    log.info("시간대 예산 초과로 제외: day={} {} ({}, {}분)", day.dayNumber,
                            actCand != null ? actCand.name() : idx, timePreference(actCand),
                            resolveVisitMinutes(actCand));
                }
            }
        }

        // 버킷 경계를 알아야 점심이 오전·오후 활동 사이 우회를 최소화하는지 판정할 수 있어 버킷 확정 뒤에 식사 슬롯을 정한다.
        // 그날 중심 최근접으로 고르면 오전 → 점심 → 오후가 동서로 왕복한다.
        MealSlots slots = MealPlanner.assignMealSlots(meals, day, candidates, morning, afternoon, startFrom,
                dayCentroid, spareIndices, cfg.morningStart);
        Integer breakfastIdx = slots.breakfast();
        Integer lunchIdx = slots.lunch();
        Integer dinnerIdx = slots.dinner();
        // assignMealSlots는 저녁·점심을 먼저 고르고 남은 것을 아침으로 둬, 실제 아침이 dropDistantBreakfast 검사를
        // 거치지 않았을 수 있다. 그래서 슬롯 확정 뒤 다시 본다.
        if (breakfastIdx != null && !MealPlanner.isReasonableBreakfast(day, byIndex(candidates, breakfastIdx),
                startFrom, candidates, transportPref, cfg.morningStart)) {
            day.placeIndices.remove(breakfastIdx);
            spareIndices.addLast(breakfastIdx);
            log.info("아침 식사 생략(숙소에서 멀거나 점심에 붙음): day={} {}", day.dayNumber,
                    nameOf(candidates, breakfastIdx));
            breakfastIdx = null;
        }

        // 저녁 버킷이 비면 저녁 식당이 숙소 직전이 된다. 숙소에 더 가까운 오후 활동이 있으면 저녁 뒤로 옮겨 숙소 근처에서 하루를 마친다.
        LodgingPlanner.endDayNearAccommodation(day, candidates, afternoon, evening, dinnerIdx);
        // 저녁 활동이 숙소-저녁식당 거리보다 확연히 멀면 spare로 보내 저녁 뒤 장거리 원정을 막는다.
        LodgingPlanner.filterEveningAwayFromAccommodation(day, candidates, evening, dinnerIdx, spareIndices);

        // 먹는 스텝이 연달아 붙으면 "빵집 나와서 7분 뒤 횟집"이 된다. 버킷 안의 연속도,
        // 버킷 마지막(바로 뒤가 끼니)도 같은 문제라 한 규칙으로 본다.
        MealPlanner.separateFoodSteps(day, morning, candidates, lunchIdx != null, "점심");
        MealPlanner.separateFoodSteps(day, afternoon, candidates, dinnerIdx != null, "저녁");
        MealPlanner.separateFoodSteps(day, evening, candidates, false, null);

        // 최종 시퀀스: 허브 → 아침 → 오전활동 → 점심 → 오후활동 → 저녁 → 저녁활동 → 숙소 → 출발허브
        List<Integer> seq = new ArrayList<>();
        if (day.arrivalHubIndex != null) seq.add(day.arrivalHubIndex);
        if (breakfastIdx != null) seq.add(breakfastIdx);
        seq.addAll(morning);
        if (lunchIdx != null) seq.add(lunchIdx);
        seq.addAll(afternoon);
        if (dinnerIdx != null) seq.add(dinnerIdx);
        seq.addAll(evening);
        // 도보 연속성 보정이 전날 숙소를 첫 스텝으로 붙이면 같은 인덱스가 이미 있어,
        // 무조건 덧붙이면 같은 장소가 두 스텝이 되고 숙박비도 두 번 계산된다.
        if (day.accommodationIndex != null && !seq.contains(day.accommodationIndex)) {
            seq.add(day.accommodationIndex);
        }
        if (day.departureHubIndex != null && !seq.contains(day.departureHubIndex)) {
            seq.add(day.departureHubIndex);
        }

        // 식사 슬롯 재배열로 순서가 orderDay 경로순과 달라져 확정 시퀀스로 거리 예산을 다시 검사한다.
        // 식사·허브·숙소·사용자 필수 장소는 거리 위반이어도 포함이 우선이다.
        Set<Integer> protectedIdx = new HashSet<>();
        if (breakfastIdx != null) protectedIdx.add(breakfastIdx);
        if (lunchIdx != null) protectedIdx.add(lunchIdx);
        if (dinnerIdx != null) protectedIdx.add(dinnerIdx);
        if (day.arrivalHubIndex != null) protectedIdx.add(day.arrivalHubIndex);
        if (day.accommodationIndex != null) protectedIdx.add(day.accommodationIndex);
        if (day.departureHubIndex != null) protectedIdx.add(day.departureHubIndex);
        for (Integer idx : seq) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.keepsPlace()) protectedIdx.add(idx);
        }
        // 카테고리 유일 대표도 보호한다. 빼면 그 카테고리가 0개가 되고, 예산을 크게 깨면 coverageProtected가 양보한다.
        protectedIdx.addAll(coverageProtected(day, candidates, transportPref, cfg));
        protectedIdx.addAll(cafeStopProtected(day, candidates, transportPref, cfg));
        Integer iconic = DistanceBudget.mostIconicInSequence(seq, candidates);
        if (iconic != null) protectedIdx.add(iconic);
        // 전날 숙소(또는 도착 허브)에서 첫 스텝까지의 이동을 빼면 먼 곳에서 시작하는 날을 상한 이내로 오판한다.
        PlaceCandidate budgetOrigin = (startFrom != null && !seq.isEmpty() && seq.get(0) != startFrom.index())
                ? startFrom : null;
        Set<Integer> pinnedMeals = new HashSet<>();
        if (breakfastIdx != null) pinnedMeals.add(breakfastIdx);
        if (lunchIdx != null) pinnedMeals.add(lunchIdx);
        if (dinnerIdx != null) pinnedMeals.add(dinnerIdx);
        // 트림만 막으면 먼 장소를 가까운 spare로 교체하는 경로가 대표 명소를 대신 빼서 교체에서도 고정한다.
        if (iconic != null) pinnedMeals.add(iconic);
        DistanceBudget.trimSequenceOverBudget(day, seq, candidates, spareIndices, transportPref, protectedIdx,
                budgetOrigin, cfg, pinnedMeals, usedIndices);
        // 귀가 시각 상한은 거리 예산과 별개다. 상한 내여도 체류·이동이 쌓이면 심야 귀가가 된다.
        trimSequenceForDepartureTime(day, seq, candidates, spareIndices, cfg, budgetOrigin,
                transportPref, protectedIdx, breakfastIdx, lunchIdx, dinnerIdx);

        List<StepData> steps = new ArrayList<>();
        // 같은 날 안에서 대안이 겹치지 않도록 스텝 간에 공유하는 집합.
        Set<String> dayAltKeys = new HashSet<>();
        int currentMinutes = cfg.morningStart;
        // 전날 숙소를 출발지로 둬야 첫 스텝에도 이동 정보가 붙고 첫 도착이 출발 + 이동시간이 된다.
        // walk 보정으로 숙소가 이미 첫 스텝이면 중복 적용하지 않는다.
        PlaceCandidate prev = (startFrom != null && !seq.isEmpty() && seq.get(0) != startFrom.index())
                ? startFrom : null;
        int[] overQuotaUsed = {0}; // pace 초과 채움은 하루 1곳까지

        for (int idx : seq) {
            PlaceCandidate cand = byIndex(candidates, idx);
            if (cand == null) continue;

            // 식사 슬롯 직전 빈 시간이 활동 1개 분량 이상이면 spare에서 근처 활동을 삽입해 메운다.
            if ((lunchIdx != null && idx == lunchIdx) || (dinnerIdx != null && idx == dinnerIdx)) {
                int mealStart = (lunchIdx != null && idx == lunchIdx) ? LUNCH_START : DINNER_START;
                GapFill fill = GapFiller.fillGapBeforeMeal(day, candidates, spareIndices, usedIndices,
                        prev, currentMinutes, mealStart, cand, dayCentroid, transportPref,
                        // 채운 장소는 day.placeIndices에 들어가므로 이미 센 것으로 친다(0을 넘긴다).
                        GapFiller.gapFillQuota(day, maxPerDay, 0), dayAltKeys, cfg, overQuotaUsed);
                steps.addAll(fill.steps());
                prev = fill.prev();
                currentMinutes = fill.minutes();
            }

            TransportLeg leg = computeLeg(prev, cand, transportPref, day.dayNumber);
            if (leg != null) currentMinutes += leg.durationMin();

            if (breakfastIdx != null && idx == breakfastIdx) currentMinutes = Math.max(currentMinutes, cfg.morningStart);
            if (lunchIdx != null && idx == lunchIdx) currentMinutes = Math.max(currentMinutes, LUNCH_START);
            if (dinnerIdx != null && idx == dinnerIdx) currentMinutes = Math.max(currentMinutes, DINNER_START);
            if (!cand.mealEligible() && timePreference(cand) == TimePreference.EVENING) {
                currentMinutes = Math.max(currentMinutes, EVENING_ACTIVITY_EARLIEST_START);
            }

            boolean isMorningDeparture = prev == null && day.arrivalHubIndex == null
                    && PlaceCategoryConstants.isAccommodation(cand.category());
            int visitMinutes;
            if (isSameIndex(day.departureHubIndex, idx)) {
                // 귀가 허브는 탑승 수속 시간이라 관광 기본값을 쓰면 종료가 그만큼 밀린다.
                visitMinutes = LodgingPlanner.departureHubMinutes(transportPref);
            } else if (isMorningDeparture) {
                visitMinutes = MORNING_DEPARTURE_MINUTES;
            } else if (PlaceCategoryConstants.isAccommodation(cand.category())) {
                visitMinutes = ACCOMMODATION_ARRIVAL_MINUTES; // 체크인을 관광 체류로 잡으면 종료가 심야로 밀린다
            } else if (isDiningCand(cand)) {
                visitMinutes = DINING_VISIT_MINUTES;
            } else {
                visitMinutes = resolveVisitMinutes(cand);
            }
            // 누적 시각이 23:59에 닿으면 start와 end가 같아져 HardValidator의 endTime>startTime이 깨진다.
            // start를 최소 1분 체류가 남는 지점까지로 제한한다.
            int startMin = Math.min(currentMinutes, END_OF_DAY_MINUTES - 1);
            if (!steps.isEmpty()) {
                int lastEnd = toMinutesOrZero(steps.get(steps.size() - 1).endTime());
                startMin = Math.max(startMin, Math.min(lastEnd, END_OF_DAY_MINUTES - 1));
            }

            // 버킷은 분 예산만 세서 시계가 밀리면 정상 배치된 장소도 한밤중에 시작할 수 있어 확정 시각으로 다시 거른다.
            // 식사·허브·숙소·사용자 필수 장소는 구조상 빼지 않는다.
            boolean fixedSlot = isSameIndex(day.arrivalHubIndex, idx)
                    || isSameIndex(day.departureHubIndex, idx)
                    || isSameIndex(day.accommodationIndex, idx)
                    || isSameIndex(breakfastIdx, idx)
                    || isSameIndex(lunchIdx, idx)
                    || isSameIndex(dinnerIdx, idx);
            if (!fixedSlot && !cand.userSelected()) {
                // 확정 시각에 영업 중인지 본다. 숙소 영업시간은 프런트 운영 시간이라 걸면 저녁 체크인이 막혀 fixedSlot은 제외한다.
                Integer openFrom = OpeningHoursText.earliestStart(
                        cand.openingHours(), day.dayOfWeek, startMin, visitMinutes);
                if (openFrom != null && (openFrom < 0 || openFrom - startMin > OPENING_WAIT_LIMIT_MINUTES)) {
                    day.placeIndices.remove(Integer.valueOf(idx));
                    spareIndices.addLast(idx);
                    log.info("영업시간 가드 트림: day={}({}) {} ({} 시작 예정, 영업 [{}])",
                            day.dayNumber, day.dayOfWeek, cand.name(), formatMinutes(startMin),
                            cand.openingHours() == null ? "?" : cand.openingHours());
                    continue;
                }
                if (openFrom != null && openFrom > startMin) {
                    // 개장까지 대기가 한도 안이면 버리지 않고 시작을 늦춘다.
                    log.info("개장 대기로 시작 시각 조정: day={}({}) {} {} → {}",
                            day.dayNumber, day.dayOfWeek, cand.name(),
                            formatMinutes(startMin), formatMinutes(openFrom));
                    startMin = openFrom;
                    currentMinutes = Math.max(currentMinutes, startMin);
                }
                int latestStart = Math.min(cfg.eveningCap, ABSOLUTE_LATEST_START_MINUTES);
                TimePreference pref = timePreference(cand);
                if (pref == TimePreference.DAYTIME) {
                    latestStart = Math.min(latestStart, daytimeLatestStart(cfg));
                } else if (pref == TimePreference.MORNING_ONLY) {
                    latestStart = Math.min(latestStart, LONG_HIKE_LATEST_START_MINUTES);
                }
                if (startMin > latestStart) {
                    day.placeIndices.remove(Integer.valueOf(idx));
                    spareIndices.addLast(idx);
                    log.info("시각 가드 트림: day={} {} ({} 시작, 상한 {})", day.dayNumber, cand.name(),
                            formatMinutes(startMin), formatMinutes(latestStart));
                    continue;
                }
            }

            int endMin = Math.min(startMin + visitMinutes, END_OF_DAY_MINUTES);
            if (endMin <= startMin) endMin = startMin + 1;

            steps.add(buildStep(day, cand, startMin, endMin, leg,
                    AlternativeBuilder.buildAlternatives(cand, dayCentroid, spareIndices, usedIndices, candidates,
                            transportPref, dayAltKeys)));

            currentMinutes = Math.max(currentMinutes, endMin);
            prev = cand;
        }
        return dropAdjacentDuplicates(steps, day);
    }

    /**
     * 같은 장소가 <b>바로 연달아</b> 두 스텝으로 들어간 경우를 제거한다(출력 경계 안전망).
     *
     * <p>버킷·식사 슬롯·갭 보충·숙소 배정이 각자 시퀀스에 장소를 넣어 같은 인덱스를 집을 수 있다.
     * 인접 중복은 항상 오류라 마지막에 한 번 더 거른다. 떨어진 같은 장소(아침 숙소 출발 → 밤 체크인)는 정상이다.
     */
    private static List<StepData> dropAdjacentDuplicates(List<StepData> steps, DayState day) {
        List<StepData> result = new ArrayList<>(steps.size());
        String prevKey = null;
        for (StepData s : steps) {
            String key = s.place() != null ? placeKey(s.place().name(), s.place().address()) : null;
            if (key != null && key.equals(prevKey)) {
                log.info("인접 중복 스텝 제거: day={} {}", day.dayNumber, s.place().name());
                continue;
            }
            result.add(s);
            prevKey = key;
        }
        return result;
    }

    /**
     * 숙소에서 출발 허브까지가 출발 기한 앞 여유보다 길면 그만큼 일찍 시작한다. 고정 여유
     * ({@link ScheduleTuning#EARLY_DEPARTURE_LEAD_MINUTES})만 두면 먼 숙소에서는 기한을 넘긴다.
     */
    private static ScheduleConfig withDepartureLegLead(DayState day, ScheduleConfig cfg, List<PlaceCandidate> candidates,
                                                       PlaceCandidate startFrom, String transportPref) {
        if (day.startNotBefore != null || day.departureDeadline == null || day.departureHubIndex == null
                || startFrom == null) {
            return cfg;
        }
        TransportLeg leg = computeLeg(startFrom, byIndex(candidates, day.departureHubIndex), transportPref,
                day.dayNumber);
        if (leg == null) return cfg;
        int latestStart = day.departureDeadline - leg.durationMin();
        if (cfg.morningStart <= latestStart) return cfg;
        int start = Math.max(EARLIEST_DAY_START_MINUTES, latestStart);
        return cfg.withWindow(start, Math.max(start, cfg.eveningCap));
    }

    private static int departureDeadline(DayState day) {
        return day.departureDeadline != null
                ? Math.min(day.departureDeadline, DEPARTURE_HUB_LATEST_MINUTES) : DEPARTURE_HUB_LATEST_MINUTES;
    }

    /**
     * 도착 허브 체류 + 허브에서 그날 첫 장소까지 이동. 도착이 늦은 날은 이 시간이 예산에서 바로 빠진다
     * (제주공항 → 서귀포처럼 한 시간 이동이면 오후 예산의 절반이 사라진다).
     */
    private static int arrivalLeadMinutes(DayState day, List<Integer> orderedMain, List<PlaceCandidate> candidates,
                                          String transportPref) {
        if (day.startNotBefore == null || day.arrivalHubIndex == null) return 0;
        PlaceCandidate hub = byIndex(candidates, day.arrivalHubIndex);
        PlaceCandidate first = orderedMain.stream()
                .filter(idx -> !isSameIndex(day.arrivalHubIndex, idx))
                .map(idx -> byIndex(candidates, idx))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        TransportLeg leg = hub != null && first != null ? computeLeg(hub, first, transportPref, day.dayNumber) : null;
        return ARRIVAL_HUB_MINUTES + (leg != null ? leg.durationMin() : 0);
    }

    /**
     * 버킷에 활동을 <b>분 예산</b>(체류시간 + 구간 이동 여유)으로 넣는다. guaranteeOne이면 빈 버킷에는 예산을 넘겨도 1개 넣는다.
     */
    private static boolean addIfFitsBudget(List<Integer> bucket, int budgetMinutes, int[] usedMinutes,
                                           Integer idx, PlaceCandidate cand, boolean guaranteeOne) {
        if (budgetMinutes <= 0) return false;
        int need = resolveVisitMinutes(cand) + INTRA_BUCKET_TRAVEL_MINUTES;
        boolean fits = usedMinutes[0] + need <= budgetMinutes;
        // 빈 버킷 1개 보장은 오후에만 준다. 오전·저녁은 뒤에 식사·마감 데드라인이 있어 넘겨 넣으면 그대로 밀린다.
        // 오후는 다른 버킷의 폴백 목적지라 하루가 비지 않게 지킨다.
        if (!fits && !(guaranteeOne && bucket.isEmpty())) return false;
        bucket.add(idx);
        usedMinutes[0] += need;
        return true;
    }

    /**
     * 도보 선호일 때 익일 첫 방문지가 전날 숙소에서 도보권({@link ScheduleTuning#WALK_CONTINUITY_THRESHOLD_KM})을 벗어나면
     * 전날 숙소를 그날 첫 스텝으로 붙여 숙소에서 아침 출발하게 한다. car/any는 차량 이동을 전제로 해 보정하지 않는다.
     * 첫날, 도착 허브가 있는 날, 좌표 불명, 이미 첫 스텝이 그 숙소인 경우는 대상이 아니다.
     */
    private static List<Integer> applyWalkContinuity(List<Integer> ordered, DayState day, List<DayState> days,
                                                     int dayIdx, List<PlaceCandidate> candidates, String transportPref) {
        if (!"walk".equals(transportPref) || dayIdx == 0 || ordered.isEmpty()
                || day.arrivalHubIndex != null) {
            return ordered;
        }
        Integer prevAccom = days.get(dayIdx - 1).accommodationIndex;
        if (prevAccom == null || prevAccom.equals(ordered.get(0))) return ordered;

        double[] prevAccomCoord = coordsOf(byIndex(candidates, prevAccom));
        double[] firstCoord = coordsOf(byIndex(candidates, ordered.get(0)));
        if (prevAccomCoord == null || firstCoord == null) return ordered;
        if (GeoUtils.haversine(prevAccomCoord, firstCoord) <= WALK_CONTINUITY_THRESHOLD_KM) return ordered;

        log.info("walk 연속성 보정: day={} 첫 방문지가 전날 숙소에서 도보권 밖 — 숙소(idx={})에서 출발하도록 조정",
                day.dayNumber, prevAccom);
        List<Integer> adjusted = new ArrayList<>(ordered.size() + 1);
        adjusted.add(prevAccom);
        adjusted.addAll(ordered);
        return adjusted;
    }
}
