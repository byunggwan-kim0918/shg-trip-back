package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/**
 * 끼니 종류 반복({@link ItineraryRules.Rule#SAME_DAY_MEAL_KIND}·{@link ItineraryRules.Rule#MEAL_KIND_REPEATED}·
 * {@link ItineraryRules.Rule#CONSECUTIVE_MEAL_KIND})을 후보 풀로 나눈다. 그 끼니를 일정에 쓰지 않은 다른 종류 식당으로 바꿔
 * 앞뒤 구간이 상한 안에 들고 그 시각에 영업하면 고르기에 달린 것이고, 그런 식당이 없으면 풀이 좁아서 생긴 것이다.
 */
public final class MealKindSwaps {

    private MealKindSwaps() {
    }

    /**
     * 규칙마다 바꿔 넣을 식당이 있던 수와 없던 수. 같은 날은 날 수, 여행 전체는 상한을 넘긴 끼니 수, 연속은 날 수다.
     */
    public record Count(int sameDayAvoidable, int sameDayPoolLimited, int repeatedAvoidable,
                        int repeatedPoolLimited, int consecutiveAvoidable, int consecutivePoolLimited) {}

    private record Meal(StepData step, PlaceCandidate place, String kind, StepData before, StepData after,
                        int dayNumber) {}

    /**
     * @param sameDayLimitedDays 점심·저녁이 같은 종류인데 어느 쪽도 바꿔 넣을 식당이 없던 날
     * @param repeatsLimited     상한을 넘긴 종류가 있고, 그 종류의 어느 끼니도 바꿔 넣을 식당이 없었는지
     */
    record Classified(Count count, Set<Integer> sameDayLimitedDays, boolean repeatsLimited) {}

    public static Count count(List<StepData> steps, List<PlaceCandidate> candidates, String transportPref,
                              String pace, LocalDate startDate) {
        return classify(steps, candidates, transportPref, pace, startDate).count();
    }

    static Classified classify(List<StepData> steps, List<PlaceCandidate> candidates, String transportPref,
                               String pace, LocalDate startDate) {
        double cap = ScheduleTuning.distanceLimits(transportPref, pace)[0];
        CandidateLookup lookup = new CandidateLookup(candidates);
        Set<Integer> used = steps.stream().map(lookup::of).filter(Objects::nonNull).map(PlaceCandidate::index)
                .collect(Collectors.toSet());
        List<PlaceCandidate> unusedMeals = candidates.stream()
                .filter(c -> !used.contains(c.index()) && c.mealEligible() && coordsOf(c) != null
                        && CandidateTraits.isSelectable(c)
                        && CandidateLookup.isSpecificKind(CandidatePolicy.cuisineOf(c)))
                .toList();
        Map<Integer, List<StepData>> byDay = steps.stream().sorted(Comparator.comparingInt(StepData::stepOrder))
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));
        int tripCap = mealKindTripCap(byDay.size());

        List<Meal> lunches = new ArrayList<>();
        List<Meal> dinners = new ArrayList<>();
        Map<String, List<Meal>> byKind = new HashMap<>();
        byDay.forEach((day, daySteps) -> {
            for (int i = 0; i < daySteps.size(); i++) {
                StepData s = daySteps.get(i);
                if (s.place() == null || !lookup.isMeal(s)) continue;
                String kind = lookup.mealKind(s);
                if (!CandidateLookup.isSpecificKind(kind)) continue;
                Meal meal = new Meal(s, lookup.of(s), kind, i > 0 ? daySteps.get(i - 1) : null,
                        i + 1 < daySteps.size() ? daySteps.get(i + 1) : null, day);
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(meal);
                int start = toMinutesOrZero(s.startTime());
                if (InvariantChecker.inMealWindow(start, LUNCH_START, LUNCH_END)
                        && lunches.stream().noneMatch(m -> m.dayNumber() == day)) lunches.add(meal);
                if (InvariantChecker.inMealWindow(start, DINNER_START, DINNER_END)) {
                    dinners.removeIf(m -> m.dayNumber() == day);
                    dinners.add(meal);
                }
            }
        });
        Map<String, Integer> kindCount = new HashMap<>();
        byKind.forEach((k, meals) -> kindCount.put(k, meals.size()));

        int sameDayAvoidable = 0, sameDayLimited = 0, consecutiveAvoidable = 0, consecutiveLimited = 0;
        Set<Integer> sameDayLimitedDays = new HashSet<>();
        for (Meal lunch : lunches) {
            Meal dinner = dinners.stream().filter(m -> m.dayNumber() == lunch.dayNumber()).findFirst().orElse(null);
            if (dinner != null && PlaceCategoryConstants.sameMealKind(lunch.kind(), dinner.kind())) {
                if (swappable(lunch, Set.of(dinner.kind()), unusedMeals, kindCount, tripCap, cap, lookup, startDate)
                        || swappable(dinner, Set.of(lunch.kind()), unusedMeals, kindCount, tripCap, cap, lookup,
                        startDate)) {
                    sameDayAvoidable++;
                } else {
                    sameDayLimited++;
                    sameDayLimitedDays.add(lunch.dayNumber());
                }
                continue;
            }
            Meal previousDinner = dinners.stream().filter(m -> m.dayNumber() == lunch.dayNumber() - 1).findFirst()
                    .orElse(null);
            if (previousDinner != null && PlaceCategoryConstants.sameMealKind(lunch.kind(), previousDinner.kind())) {
                Set<String> avoid = dinner == null ? Set.of(previousDinner.kind())
                        : Set.of(previousDinner.kind(), dinner.kind());
                if (swappable(lunch, avoid, unusedMeals, kindCount, tripCap, cap, lookup, startDate)) {
                    consecutiveAvoidable++;
                } else {
                    consecutiveLimited++;
                }
            }
        }

        int repeatedAvoidable = 0, repeatedLimited = 0;
        boolean anyRepeatMovable = false;
        for (Map.Entry<String, List<Meal>> entry : byKind.entrySet()) {
            int over = entry.getValue().size() - tripCap;
            if (over <= 0) continue;
            long movable = entry.getValue().stream()
                    .filter(m -> swappable(m, Set.of(entry.getKey()), unusedMeals, kindCount, tripCap, cap, lookup,
                            startDate))
                    .count();
            anyRepeatMovable |= movable > 0;
            int avoidable = (int) Math.min(over, movable);
            repeatedAvoidable += avoidable;
            repeatedLimited += over - avoidable;
        }
        Count count = new Count(sameDayAvoidable, sameDayLimited, repeatedAvoidable, repeatedLimited,
                consecutiveAvoidable, consecutiveLimited);
        return new Classified(count, Set.copyOf(sameDayLimitedDays), repeatedLimited > 0 && !anyRepeatMovable);
    }

    // 앞뒤 이웃 한쪽만 보면 바꾼 뒤 반대쪽 구간이 상한을 넘는다.
    private static boolean swappable(Meal meal, Set<String> avoidKinds, List<PlaceCandidate> unusedMeals,
                                     Map<String, Integer> kindCount, int tripCap, double cap,
                                     CandidateLookup lookup, LocalDate startDate) {
        List<double[]> around = new ArrayList<>();
        for (StepData n : new StepData[]{meal.before(), meal.after()}) {
            double[] c = n == null ? null : coordsOf(lookup.of(n));
            if (c != null) around.add(c);
        }
        if (around.isEmpty() && meal.place() != null && coordsOf(meal.place()) != null) {
            around.add(coordsOf(meal.place()));
        }
        if (around.isEmpty()) return false;
        DayOfWeek dow = startDate == null ? null : startDate.plusDays(meal.dayNumber() - 1L).getDayOfWeek();
        int start = toMinutesOrZero(meal.step().startTime());
        return unusedMeals.stream()
                .filter(c -> {
                    String kind = CandidatePolicy.cuisineOf(c);
                    return avoidKinds.stream().noneMatch(k -> PlaceCategoryConstants.sameMealKind(k, kind))
                            && kindCount.getOrDefault(kind, 0) < tripCap;
                })
                .filter(c -> DayTiming.isOpenDuring(c, dow, start, start + DINING_VISIT_MINUTES))
                .anyMatch(c -> around.stream().allMatch(n -> GeoUtils.haversine(n, coordsOf(c)) <= cap));
    }
}
