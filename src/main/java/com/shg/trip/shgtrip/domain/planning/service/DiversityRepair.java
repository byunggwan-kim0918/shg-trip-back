package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isVisitableFill;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.normalizeName;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.addToDay;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.insertableFilter;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.pickNearbyCandidate;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.replaceInDay;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.unusedIndices;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 세부 유형 상한, 카테고리 커버리지, 같은 대분류 연속 해소. */
@Slf4j
final class DiversityRepair {

    private DiversityRepair() {
    }

    /** 전 일정의 세부 유형별 개수(허브·숙소 제외). */
    static Map<String, Integer> countSubTypes(List<DayState> days, List<PlaceCandidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        for (DayState d : days) {
            for (Integer idx : d.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c != null) counts.merge(subTypeOf(c), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 같은 종류가 연속 {@value ScheduleTuning#MAX_CONSECUTIVE_SAME_MAJOR}개 이상이면 연속 구간 중간을 다른
     * 종류의 근접 spare로 교체한다. 교체할 후보가 없으면 그대로 두고 로그만 남긴다.
     *
     * <p>"같은 종류"는 대분류가 아니라 <b>체감 기준</b>이다.
     * <ul>
     *   <li>식음료(DINING/CAFE): 대분류 연속. 카페 3연속은 그 자체로 단조롭다</li>
     *   <li>관광(ATTRACTION): 세부 유형 연속. 박물관·해변·공원이 이어지는 건 정상이고 "오름 3연속"이 문제다</li>
     * </ul>
     */
    static boolean breakConsecutiveSameMajor(DayState day, List<StepData> daySteps,
                                             List<PlaceCandidate> candidates, Deque<Integer> spare,
                                             Set<Integer> usedIndices,
                                             java.util.function.Predicate<PlaceCandidate> openOnDay) {
        List<StepData> visits = daySteps.stream()
                .filter(s -> s.place() != null)
                .filter(s -> {
                    String m = PlaceCategoryConstants.majorCategory(s.place().category());
                    return !"TRANSIT_HUB".equals(m) && !"LODGING".equals(m);
                })
                .collect(Collectors.toList());
        if (visits.size() < MAX_CONSECUTIVE_SAME_MAJOR) return false;

        int runStart = 0;
        for (int i = 1; i <= visits.size(); i++) {
            String prevKind = consecutiveKind(visits.get(i - 1), day, candidates);
            String curKind = i < visits.size() ? consecutiveKind(visits.get(i), day, candidates) : null;
            if (curKind != null && curKind.equals(prevKind)) continue;

            int runLength = i - runStart;
            if (runLength >= MAX_CONSECUTIVE_SAME_MAJOR) {
                StepData middle = visits.get(runStart + runLength / 2);
                if (replaceStepWithSameKindBroken(day, middle, prevKind, candidates, spare, usedIndices, openOnDay)) {
                    return true;
                }
                log.info("{} {}개 연속 해소 실패(다른 종류 후보 없음): day={}",
                        prevKind, runLength, day.dayNumber);
            }
            runStart = i;
        }
        return false;
    }

    /** 연속 판정 키: 식음료는 대분류, 관광은 세부 유형(보강 속성 우선). kindOf와 기준이 같아야 한다. */
    private static String consecutiveKind(StepData step, DayState day, List<PlaceCandidate> candidates) {
        String major = PlaceCategoryConstants.majorCategory(step.place().category());
        if ("DINING".equals(major) || "CAFE".equals(major)) return major;
        Integer idx = indexOfStep(step, day, candidates);
        PlaceCandidate c = idx == null ? null : byIndex(candidates, idx);
        String byAttribute = c == null ? null : PlaceCategoryConstants.attributeSubTypeKey(c.attributes());
        if (byAttribute != null) return byAttribute;
        return PlaceCategoryConstants.subType(
                step.place().name(), step.place().category(), List.of(), step.place().description());
    }

    /** 스텝 하나를 "연속을 끊는 다른 종류"의 근접 spare로 교체. 사용자 필수 장소는 건드리지 않는다. */
    private static boolean replaceStepWithSameKindBroken(DayState day, StepData step, String kind,
                                                         List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                         Set<Integer> usedIndices,
                                                         java.util.function.Predicate<PlaceCandidate> openOnDay) {
        Integer target = indexOfStep(step, day, candidates);
        if (target == null) return false;
        PlaceCandidate targetCand = byIndex(candidates, target);
        if (targetCand == null || targetCand.keepsPlace()) return false;

        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, coordsOf(targetCand),
                openOnDay.and(c -> isVisitableFill(c) && !kind.equals(kindOf(c))));
        if (pick == null) return false;

        replaceInDay(day, target, pick);
        spare.remove(pick);
        spare.addLast(target);
        usedIndices.add(pick);
        usedIndices.remove(target);
        log.info("{} 연속 해소: day={} {} → {}", kind, day.dayNumber, targetCand.name(),
                nameOf(candidates, pick));
        return true;
    }

    /** 후보의 연속 판정 키(consecutiveKind와 같은 기준). */
    private static String kindOf(PlaceCandidate c) {
        String major = PlaceCategoryConstants.majorCategory(c.category());
        if ("DINING".equals(major) || "CAFE".equals(major)) return major;
        return subTypeOf(c);
    }

    /**
     * 최종 스텝 기준으로 트림에 사라진 사용자 카테고리를 다시 채운다.
     *
     * <p>후보는 대상 day centroid 최근접으로 고르고 구간·일일 거리 예산을 넘기면 넣지 않는다.
     * 커버리지 한 칸 때문에 하루 동선을 무너뜨리느니 미충족 로그를 남긴다.
     */
    static boolean reapplyCoverageOnFinalSteps(List<DayState> days, List<StepData> steps,
                                               List<PlaceCandidate> candidates, Deque<Integer> spare,
                                               Set<Integer> usedIndices, List<String> userCategories,
                                               int maxPerDay, java.time.LocalDate startDate,
                                               InvariantMemo memo, String transportPref, ScheduleConfig cfg) {
        if (userCategories == null || userCategories.isEmpty()) return false;

        for (String categoryId : userCategories) {
            if (categoryId == null || categoryId.isBlank()) continue;
            boolean covered = steps.stream().anyMatch(s -> s.place() != null
                    && PlaceCategoryConstants.matchesUserCategory(
                            categoryId, s.place().name(), s.place().category(), List.of()));
            if (!covered && "accommodation".equalsIgnoreCase(categoryId.trim())) {
                covered = days.stream().anyMatch(d -> d.accommodationIndex != null);
            }
            if (covered) continue;

            // (day, 후보)를 함께 골라 centroid에 가장 가까운 조합을 택해야 동선이 망가지지 않는다.
            DayState bestDay = null;
            Integer bestPick = null;
            double bestDist = Double.MAX_VALUE;
            // spare에 그 카테고리가 없으면 미사용 후보까지 넓힌다. 거리 예산·시각 가드는 insertable이 지킨다.
            List<Integer> pool = new ArrayList<>(spare);
            boolean spareHasCategory = pool.stream()
                    .map(idx -> byIndex(candidates, idx))
                    .filter(Objects::nonNull)
                    .anyMatch(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()));
            if (!spareHasCategory) {
                pool.addAll(unusedIndices(candidates, usedIndices));
            }
            for (DayState d : days) {
                if (d.placeIndices.size() >= maxPerDay) continue;
                double[] centroid = centroidOf(d.placeIndices, candidates);
                java.util.function.Predicate<PlaceCandidate> insertable =
                        insertableFilter(d, candidates, startDate, transportPref, memo, cfg);
                for (Integer idx : pool) {
                    PlaceCandidate c = byIndex(candidates, idx);
                    if (c == null || !isVisitableFill(c)) continue;
                    if (!PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags())) continue;
                    if (!insertable.test(c)) continue;
                    double dist = centroid == null || coordsOf(c) == null
                            ? 0 : GeoUtils.haversine(centroid, coordsOf(c));
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestDay = d;
                        bestPick = idx;
                    }
                }
            }

            if (bestDay != null) {
                addToDay(bestDay, bestPick, spare, usedIndices);
                memo.recordInsert(bestDay.dayNumber, bestPick);
                log.info("최종 커버리지 보충: {} → day={} {} (centroid에서 {}km)", categoryId,
                        bestDay.dayNumber, nameOf(candidates, bestPick), String.format("%.0f", bestDist));
                return true; // 한 번에 하나씩 넣고 나머지는 재스케줄 뒤 다음 패스에서 본다
            }
            // 자리가 없으면 가장 많이 반복된 유형 하나와 교체해 quota를 늘리지 않고 커버리지를 채운다.
            if (replaceMostRepeatedForCoverage(days, candidates, spare, usedIndices, categoryId, startDate,
                    memo, transportPref, steps, cfg)) {
                return true;
            }
            log.info("최종 커버리지 미충족(예산·자리 제약으로 삽입·교체 불가): {}", categoryId);
        }
        return false;
    }

    /**
     * 커버리지 후보를 넣을 자리가 없을 때 가장 많이 반복된 세부 유형의 장소 하나와 맞바꾼다.
     * 식사·허브·숙소·사용자 필수 장소와 반복 2회 미만 유형은 교체하지 않는다.
     */
    private static boolean replaceMostRepeatedForCoverage(List<DayState> days, List<PlaceCandidate> candidates,
                                                          Deque<Integer> spare, Set<Integer> usedIndices,
                                                          String categoryId, java.time.LocalDate startDate,
                                                          InvariantMemo memo, String transportPref,
                                                          List<StepData> steps, ScheduleConfig cfg) {
        Map<String, Integer> typeCount = countSubTypes(days, candidates);
        // 식사 슬롯 장소는 교체하면 끼니가 사라져 대상에서 뺀다.
        Set<String> mealSlotNames = steps.stream()
                .filter(st -> isMealSlotStep(st, days, candidates))
                .map(st -> normalizeName(st.place().name()))
                .collect(Collectors.toSet());

        DayState bestDay = null;
        Integer bestVictim = null;
        Integer bestPick = null;
        int bestCount = 1; // 2회 이상 반복된 유형만 교체 대상

        for (DayState d : days) {
            java.util.function.Predicate<PlaceCandidate> insertable =
                    insertableFilter(d, candidates, startDate, transportPref, memo, cfg);
            Integer pick = spare.stream()
                    .map(i -> byIndex(candidates, i))
                    .filter(Objects::nonNull)
                    .filter(CandidateTraits::isVisitableFill)
                    .filter(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()))
                    .filter(insertable)
                    .min(Comparator.comparingDouble(c -> {
                        double[] centroid = centroidOf(d.placeIndices, candidates);
                        return centroid == null || coordsOf(c) == null ? 0 : GeoUtils.haversine(centroid, coordsOf(c));
                    }))
                    .map(PlaceCandidate::index)
                    .orElse(null);
            if (pick == null) continue;

            for (Integer idx : d.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.keepsPlace()) continue;
                if (c.mealEligible()) continue;
                if (mealSlotNames.contains(normalizeName(c.name()))) continue;
                int count = typeCount.getOrDefault(subTypeOf(c), 0);
                if (count > bestCount) {
                    bestCount = count;
                    bestDay = d;
                    bestVictim = idx;
                    bestPick = pick;
                }
            }
        }
        if (bestDay == null) return false;

        replaceInDay(bestDay, bestVictim, bestPick);
        spare.remove(bestPick);
        spare.addLast(bestVictim);
        usedIndices.add(bestPick);
        usedIndices.remove(bestVictim);
        memo.recordInsert(bestDay.dayNumber, bestPick);
        log.info("최종 커버리지 교체 보충: {} → day={} {} → {} ({} {}회 반복)", categoryId, bestDay.dayNumber,
                nameOf(candidates, bestVictim), nameOf(candidates, bestPick),
                subTypeOf(byIndex(candidates, bestVictim)), bestCount);
        return true;
    }

    /**
     * 최종 스텝 기준으로 세부 유형 상한 초과분을 <b>같은 대분류의</b> 다른 세부 유형으로 교체한다.
     * 대분류가 바뀌면 관광이 식당이 되거나 저녁이 카페가 되어 상위 불변식이 깨진다.
     */
    static boolean reapplyTypeCapOnFinalSteps(List<DayState> days, List<StepData> steps,
                                              List<PlaceCandidate> candidates, Deque<Integer> spare,
                                              Set<Integer> usedIndices, java.time.LocalDate startDate,
                                              InvariantMemo memo, String transportPref, ScheduleConfig cfg) {
        Map<String, Integer> typeCount = new HashMap<>();

        for (StepData step : steps) {
            if (step.place() == null) continue;
            String major = PlaceCategoryConstants.majorCategory(step.place().category());
            if ("TRANSIT_HUB".equals(major) || "LODGING".equals(major)) continue;

            // 스텝(PlaceData)에는 태그가 없어 후보로 되짚어야 태그로만 드러나는 종류도 상한에 걸린다.
            PlaceCandidate stepCand = candidateOfStep(days, step, candidates);
            String type = stepCand != null ? subTypeOf(stepCand) : PlaceCategoryConstants.mealKind(
                    step.place().name(), step.place().nameKo(), step.place().category(), List.of(),
                    step.place().description(), null);
            int typeCap = typeCapFor(stepCand, days.size());
            int count = typeCount.merge(type, 1, Integer::sum);
            if (!isSpecificType(type) || count <= typeCap) continue;

            // 식사 슬롯도 교체 대상이다. DINING 대분류에는 카페·바도 있어 식사 슬롯이면 대체 후보도 식사 장소여야 한다.
            boolean mealSlot = isMealSlotStep(step, days, candidates);

            DayState day = findDayContainingStep(days, step, candidates);
            if (day == null) continue;
            if (replaceStepWithDifferentSubType(day, step, type, major, days.size(), typeCount, candidates, spare,
                    usedIndices, insertableFilter(day, candidates, startDate, transportPref, memo, cfg), mealSlot, memo)) {
                return true; // 한 번에 하나씩
            }
            log.info("최종 유형 상한 초과({} {}회) 해소 실패(같은 대분류 대체 후보 없음): {}",
                    type, count, step.place().name());
        }

        // 전체 상한을 지켜도 같은 날 점심·저녁이 같은 종류일 수 있어 저녁을 바꾼다.
        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, LinkedHashMap::new, Collectors.toList()));
        for (DayState day : days) {
            List<StepData> meals = byDay.getOrDefault(day.dayNumber, List.of()).stream()
                    .filter(st -> isMealSlotStep(st, days, candidates))
                    .toList();
            if (meals.size() < 2) continue;
            StepData lunch = meals.get(0);
            StepData dinner = meals.get(meals.size() - 1);
            PlaceCandidate lc = candidateOfStep(days, lunch, candidates);
            PlaceCandidate dc = candidateOfStep(days, dinner, candidates);
            if (lc == null || dc == null) continue;
            String type = subTypeOf(dc);
            if (!isSpecificType(type) || !type.equals(subTypeOf(lc))) continue;
            if (replaceStepWithDifferentSubType(day, dinner, type, "DINING", days.size(), typeCount, candidates,
                    spare, usedIndices, insertableFilter(day, candidates, startDate, transportPref, memo, cfg),
                    true, memo)) {
                return true;
            }
            log.info("같은 날 점심·저녁 같은 종류({}) 해소 실패(대체 식당 없음): day={}", type, day.dayNumber);
        }
        return false;
    }

    /**
     * @param mustBeMeal 식사 슬롯 교체면 true. DINING에는 카페·바도 들어오므로 이 조건이 없으면 점심이 카페로 바뀐다.
     */
    private static boolean replaceStepWithDifferentSubType(DayState day, StepData step, String type, String major,
                                                           int dayCount, Map<String, Integer> typeCount,
                                                           List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                           Set<Integer> usedIndices,
                                                           java.util.function.Predicate<PlaceCandidate> insertable,
                                                           boolean mustBeMeal,
                                                           InvariantMemo memo) {
        Integer target = indexOfStep(step, day, candidates);
        if (target == null) return false;
        PlaceCandidate targetCand = byIndex(candidates, target);
        if (targetCand == null || targetCand.keepsPlace()) return false;

        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, coordsOf(targetCand),
                insertable.and(c -> isVisitableFill(c)
 // 대분류가 바뀌면 식사·관광 구성이 무너진다
                        && major.equals(PlaceCategoryConstants.majorCategory(c.category()))
                        && (!mustBeMeal || c.mealEligible())
                        && !type.equals(subTypeOf(c))
                        && typeCount.getOrDefault(subTypeOf(c), 0) < typeCapFor(c, dayCount)));
        if (pick == null) return false;

        replaceInDay(day, target, pick);
        spare.remove(pick);
        spare.addLast(target);
        usedIndices.add(pick);
        usedIndices.remove(target);
        memo.recordInsert(day.dayNumber, pick);
        typeCount.merge(type, -1, Integer::sum);
        typeCount.merge(subTypeOf(byIndex(candidates, pick)), 1, Integer::sum);
        log.info("최종 유형 교체({}): day={} {} → {} (대분류 {} 유지)", type,
                day.dayNumber, targetCand.name(), nameOf(candidates, pick), major);
        return true;
    }

    /**
     * 세부 유형 반복 상한. 같은 세부 유형이 전체 일정에서 {@link #typeCapFor} 상한을 넘으면 초과분을 spare로 보내고
     * 쓰이지 않은 다른 유형의 spare로 보충한다.
     *
     * <p>경유형(해안도로·드라이브코스·둘레길)은 리프가 총칭이라 유형 상한에 걸리지 않지만 지나가는 구간이라
     * 반복되면 일정이 무의미해져 전체 {@code VIA_ROUTE_CAP}개로 제한한다.
     *
     * <p>사용자 필수·pair 장소는 상한 계산에는 포함하되 빼지 않는다. 상한을 넘는 유형 안에서는 <b>인기도가 낮은 쪽부터</b> 뺀다.
     * 날짜순으로 세면 뒷날의 대표 명소가 잘린다.
     */
    static void enforceTypeDiversity(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                     List<List<Integer>> pairs) {
        Set<Integer> pairedIndices = pairs == null ? Set.of() : pairs.stream()
                .filter(p -> p != null && p.size() == 2)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        // 총칭 리프는 이름·태그로 세부 유형을 추론해 상한 키로 쓴다. 추론이 없으면 오름 3연속이 같은 유형으로 잡히지 않는다.
        Map<String, List<Integer>> byType = new LinkedHashMap<>();
        List<Integer> vias = new ArrayList<>();
        for (DayState day : days) {
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null) continue;
                String type = subTypeOf(c);
                if (isSpecificType(type)) byType.computeIfAbsent(type, k -> new ArrayList<>()).add(idx);
                if (PlaceCategoryConstants.isViaRoute(c.attributes(), c.name(), c.category(), c.tags())) vias.add(idx);
            }
        }
        Map<Integer, String> trimReason = new LinkedHashMap<>();
        byType.forEach((type, members) -> {
            int cap = typeCapFor(byIndex(candidates, members.get(0)), days.size());
            selectOverCap(members, cap, candidates, pairedIndices)
                    .forEach(i -> trimReason.putIfAbsent(i, type + " " + cap + "회 초과"));
        });
        selectOverCap(vias, VIA_ROUTE_CAP, candidates, pairedIndices)
                .forEach(i -> trimReason.putIfAbsent(i, "경유형 상한 " + VIA_ROUTE_CAP));
        if (trimReason.isEmpty()) return;

        Map<String, Integer> typeCount = new HashMap<>();
        for (DayState day : days) {
            for (Integer idx : day.placeIndices) {
                if (!trimReason.containsKey(idx)) typeCount.merge(subTypeOf(byIndex(candidates, idx)), 1, Integer::sum);
            }
        }
        for (DayState day : days) {
            for (Integer idx : new ArrayList<>(day.placeIndices)) {
                String reason = trimReason.get(idx);
                if (reason == null) continue;
                day.placeIndices.remove(idx);
                spare.addLast(idx);
                log.info("유형 다양성 상한 초과 — 트림: day={} {} ({})", day.dayNumber,
                        nameOf(candidates, idx), reason);

                Integer replacement = pickDiverseSpare(day, candidates, spare, typeCount, days.size());
                if (replacement != null) {
                    day.placeIndices.add(replacement);
                    spare.remove(replacement);
                    PlaceCandidate rc = byIndex(candidates, replacement);
                    typeCount.merge(subTypeOf(rc), 1, Integer::sum);
                    log.info("유형 다양성 보충: day={} {}", day.dayNumber, rc.name());
                }
            }
        }
    }

    /**
     * 상한을 넘는 묶음에서 뺄 인덱스. 사용자 필수·pair 멤버는 상한 계산에는 포함하되 빼지 않고
     * (포함·pair 무결성이 우선), 나머지 중 인기도가 낮은 것부터 고른다.
     */
    private static List<Integer> selectOverCap(List<Integer> members, int cap, List<PlaceCandidate> candidates,
                                               Set<Integer> pairedIndices) {
        if (members.size() <= cap) return List.of();
        List<Integer> removable = new ArrayList<>();
        int fixed = 0;
        for (Integer idx : members) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && (c.keepsPlace() || pairedIndices.contains(idx))) fixed++;
            else removable.add(idx);
        }
        removable.sort(Comparator.<Integer>comparingDouble(i -> PlaceProminence.of(byIndex(candidates, i)))
                .reversed());
        int keep = Math.max(0, cap - fixed);
        return keep >= removable.size() ? List.of() : removable.subList(keep, removable.size());
    }

    /**
     * 세부 유형 반복 상한. 관광·카페는 {@code ceil(days/2) + TYPE_CAP_BASE}, 한 끼 식사는 {@link ScheduleTuning#mealKindTripCap}이다.
     * 판정({@link ItineraryRules})이 같은 끼니 상한을 써야 수리 결과를 다시 위반으로 잡지 않는다.
     */
    static int typeCapFor(PlaceCandidate c, int dayCount) {
        if (c != null && c.mealEligible()) return mealKindTripCap(dayCount);
        return (int) Math.ceil(dayCount / 2.0) + TYPE_CAP_BASE;
    }

    /**
     * 유형 상한을 적용할 만큼 구체적인 리프인지. "Tourist Attraction"/"Landmark"처럼 대분류와
     * 다름없는 총칭 리프는 제외한다(그 범주 안에 서로 다른 종류의 명소가 전부 들어오기 때문).
     */
    static boolean isSpecificType(String type) {
        return !PlaceCategoryConstants.isGenericLeaf(type);
    }

    /** 상한에 걸리지 않은 다른 유형의 spare 중, 그날 중심에서 가장 가까운 방문 가능 후보. */
    private static Integer pickDiverseSpare(DayState day, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                            Map<String, Integer> typeCount, int dayCount) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        Set<Integer> used = collectUsedIndicesIncludingSpareUse(day);
        return spare.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(CandidateTraits::isVisitableFill)
                .filter(c -> !used.contains(c.index()))
                .filter(c -> !PlaceCategoryConstants.isViaRoute(c.attributes(), c.name(), c.category(), c.tags()))
                .filter(c -> typeCount.getOrDefault(subTypeOf(c), 0) < typeCapFor(c, dayCount))
                .filter(c -> centroid == null || coordsOf(c) != null)
                .min(Comparator.comparingDouble(c -> centroid == null ? 0
                        : GeoUtils.haversine(centroid, coordsOf(c))))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    private static Set<Integer> collectUsedIndicesIncludingSpareUse(DayState day) {
        return new HashSet<>(day.placeIndices);
    }

    /**
     * 사용자 카테고리 중 일정에 한 번도 없는 것을 spare에서 찾아 quota 여유가 있는 day에 넣는다.
     * 없는 유형을 억지로 만들지 않고, 후보가 없으면 로그만 남긴다.
     */
    static void ensureCategoryCoverage(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                       List<String> userCategories, int maxPerDay) {
        if (userCategories == null || userCategories.isEmpty()) return;

        for (String categoryId : userCategories) {
            if (categoryId == null || categoryId.isBlank()) continue;

            boolean covered = days.stream()
                    .flatMap(d -> d.placeIndices.stream())
                    .map(i -> byIndex(candidates, i))
                    .filter(Objects::nonNull)
                    .anyMatch(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()));
            // 숙소는 accommodationIndex로 별도 배정되므로 placeIndices에 없어도 커버된 것으로 본다
            if (!covered && "accommodation".equalsIgnoreCase(categoryId.trim())) {
                covered = days.stream().anyMatch(d -> d.accommodationIndex != null);
            }
            if (covered) continue;

            Integer pick = spare.stream()
                    .map(i -> byIndex(candidates, i))
                    .filter(Objects::nonNull)
                    .filter(CandidateTraits::isVisitableFill)
                    .filter(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()))
                    .max(Comparator.<PlaceCandidate>comparingDouble(PlaceProminence::of)
                            .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                    .map(PlaceCandidate::index)
                    .orElse(null);

            if (pick == null) {
                log.info("카테고리 커버리지 미충족(후보 없음): {}", categoryId);
                continue;
            }

            DayState target = pickDayForCoverage(days, candidates, byIndex(candidates, pick), maxPerDay);
            if (target == null) {
                log.info("카테고리 커버리지 미충족(모든 day가 quota 상한): {}", categoryId);
                continue;
            }
            target.placeIndices.add(pick);
            spare.remove(pick);
            log.info("카테고리 커버리지 보충: {} → day={} {}", categoryId, target.dayNumber,
                    nameOf(candidates, pick));
        }
    }

    /** quota 여유가 있는 day 중 centroid가 가장 가까운 곳. 전부 꽉 찼으면 null. */
    private static DayState pickDayForCoverage(List<DayState> days, List<PlaceCandidate> candidates,
                                               PlaceCandidate cand, int maxPerDay) {
        double[] target = coordsOf(cand);
        DayState best = null;
        double bestDist = Double.MAX_VALUE;
        for (DayState d : days) {
            if (d.placeIndices.size() >= maxPerDay) continue;
            double[] centroid = centroidOf(d.placeIndices, candidates);
            double dist = (target == null || centroid == null) ? d.placeIndices.size() : GeoUtils.haversine(target, centroid);
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }

    /**
     * 사용자 카테고리 중 일정 안 대표가 딱 1곳인 인덱스를 각 day에 심는다.
     *
     * <p>거리 트림이 이 인덱스를 빼면 최종 커버리지 패스는 예산이 꽉 차 되돌릴 수 없어 트림 전에 보호 표시를 한다.
     * 대표가 2곳 이상이면 하나를 빼도 커버리지가 남아 표시하지 않는다. 숙소는 accommodationIndex로 따로 배정된다.
     */
    static void markCoverageCritical(List<DayState> days, List<PlaceCandidate> candidates,
                                     List<String> userCategories) {
        if (userCategories == null || userCategories.isEmpty()) return;

        Set<Integer> critical = new HashSet<>();
        for (String categoryId : userCategories) {
            if (categoryId == null || categoryId.isBlank()) continue;
            if ("accommodation".equalsIgnoreCase(categoryId.trim())) continue;

            List<Integer> holders = days.stream()
                    .flatMap(d -> d.placeIndices.stream())
                    .filter(i -> {
                        PlaceCandidate c = byIndex(candidates, i);
                        return c != null && PlaceCategoryConstants.matchesUserCategory(
                                categoryId, c.name(), c.category(), c.tags());
                    })
                    .distinct()
                    .toList();
            if (holders.size() == 1) critical.add(holders.get(0));
        }

        for (DayState day : days) day.coverageCriticalIndices = critical;
        if (!critical.isEmpty()) {
            log.info("커버리지 유일 대표 보호: {}곳 (거리 트림 제외 대상)", critical.size());
        }
    }

    /** 그 스텝이 점심·저녁 슬롯을 채우는지. 교체·트림 보호 판정에 쓴다. */
    private static boolean isMealSlotStep(StepData step, List<DayState> days, List<PlaceCandidate> candidates) {
        if (step.place() == null) return false;
        PlaceCandidate c = candidateOfStep(days, step, candidates);
        boolean meal = c != null ? c.mealEligible()
                : PlaceCategoryConstants.isMealPlace(step.place().name(), step.place().category());
        if (!meal) return false;
        int start = toMinutesOrZero(step.startTime());
        return (start >= LUNCH_START - 60 && start <= LUNCH_END)
                || (start >= DINNER_START - 60 && start <= DINNER_END);
    }

    /** 스텝에 대응하는 후보(그 스텝이 속한 day 기준). 허브·숙소·찾지 못한 경우 null. */
    private static PlaceCandidate candidateOfStep(List<DayState> days, StepData step, List<PlaceCandidate> candidates) {
        DayState day = findDayContainingStep(days, step, candidates);
        if (day == null) return null;
        Integer idx = indexOfStep(step, day, candidates);
        return idx != null ? byIndex(candidates, idx) : null;
    }

    /** 스텝(place 이름 기준)에 대응하는 day.placeIndices의 인덱스. 허브·숙소는 대상 아님. */
    private static Integer indexOfStep(StepData step, DayState day, List<PlaceCandidate> candidates) {
        if (step.place() == null || step.place().name() == null) return null;
        String name = normalizeName(step.place().name());
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && normalizeName(c.name()).equals(name)) return idx;
        }
        return null;
    }

    private static DayState findDayContainingStep(List<DayState> days, StepData step, List<PlaceCandidate> candidates) {
        for (DayState d : days) {
            if (d.dayNumber == step.dayNumber() && indexOfStep(step, d, candidates) != null) return d;
        }
        return null;
    }
}
