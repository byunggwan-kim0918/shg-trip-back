package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/**
 * 스텝과 후보 사실만 보고 일정이 지켜야 할 규칙의 위반을 판정한다. 고치지 않는다.
 * 생성 직후 기록·재채점·저장 관문이 같은 판정을 써야 기준이 갈라지지 않는다.
 */
public final class ItineraryRules {

    /** 규칙이나 등급이 바뀌면 올린다. 다른 버전의 판정 기록끼리 비교하지 않기 위해서다. */
    public static final int VERSION = 4;

    public enum Severity {
        /** 일정을 쓸 수 없다. */
        HARD,
        /** 쓸 수는 있지만 핵심 품질 기준에 못 미친다. */
        CORE,
        /** 사용자에게 경고로 보이지 않는다. */
        SOFT
    }

    public enum Rule {
        DAY_MISSING(Severity.HARD),
        CLOSED_DAY(Severity.HARD),
        DEPARTURE_MISSED(Severity.HARD),
        LUNCH_MISSING(Severity.CORE),
        DINNER_MISSING(Severity.CORE),
        NOT_ENDING_AT_LODGING(Severity.CORE),
        DEPARTURE_LATE(Severity.CORE),
        OUTSIDE_HOURS(Severity.CORE),
        SAME_DAY_MEAL_KIND(Severity.CORE),
        MEAL_KIND_REPEATED(Severity.CORE),
        CAFE_PER_DAY(Severity.CORE),
        NO_NON_FOOD_ACTIVITY(Severity.CORE),
        ADDRESS_REGION_MISMATCH(Severity.CORE),
        REQUIRED_EXPERIENCE_MISSING(Severity.CORE),
        // 거리는 직선 × 도로 계수 추정이라 편집으로 고칠 수 없어 사용자 경고로 띄우지 않는다
        LEG_OVER_CAP(Severity.SOFT),
        DAY_DISTANCE_OVER_CAP(Severity.SOFT),
        EARLY_FINISH(Severity.SOFT),
        CONSECUTIVE_MEAL_KIND(Severity.SOFT),
        SAME_DAY_MEAL_GROUP(Severity.SOFT),
        MEAL_GROUP_REPEATED(Severity.SOFT);

        public final Severity severity;

        Rule(Severity severity) {
            this.severity = severity;
        }
    }

    /**
     * @param day      0이면 여행 전체에 걸친 위반
     * @param evidence 안내 문구에 넣을 근거(장소 이름·시각·거리). 규칙마다 담는 것이 정해져 있다
     */
    public record Violation(Rule rule, int day, String message, List<String> evidence) {
        public Violation {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        Violation(Rule rule, int day, String message) {
            this(rule, day, message, List.of());
        }

        /** 기록(generation_traces.score)만 보고 SQL로 등급을 셀 수 있게 함께 직렬화한다. */
        @JsonProperty("severity")
        public Severity severity() {
            return rule.severity;
        }
    }

    /**
     * 일정의 plan_context에 JSON으로 저장된다. 필드 이름을 바꾸면 저장된 일정에서 그 값이 비어 읽혀 편집 뒤 판정이 달라진다.
     *
     * @param dayCount            여행 일수. 0이면 날짜 누락을 판정하지 않는다
     * @param lastDayHubDeadline  사용자 출발 시각에서 정한 허브 도착 마감(분). 없으면 null
     * @param requiredExperiences 필수 경험에 맞춘 장소 id와 안내 이름. 맞출 후보가 없던 경험은 안내만 하므로 넣지 않는다.
     *                            저장 뒤 판정은 스텝 장소만 후보로 보므로 빠진 장소의 이름을 여기서 읽는다
     */
    public record Context(String pace, String transportPref, LocalDate startDate, int dayCount,
                          Integer lastDayHubDeadline, Map<Long, String> requiredExperiences) {
        public Context {
            requiredExperiences = requiredExperiences == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(requiredExperiences));
        }

        public Context(String pace, String transportPref, LocalDate startDate, int dayCount,
                       Integer lastDayHubDeadline) {
            this(pace, transportPref, startDate, dayCount, lastDayHubDeadline, Map.of());
        }
    }

    /** @param unknownKindMeals 종류를 판정하지 못한 끼니 수. 이 끼니들은 반복 판정에서 빠진다 */
    public record Verdict(int version, List<Violation> violations, int meals, int unknownKindMeals) {
        public boolean has(Severity severity) {
            return violations.stream().anyMatch(v -> v.severity() == severity);
        }

        @JsonProperty("hard")
        public boolean hard() {
            return has(Severity.HARD);
        }

        @JsonProperty("core")
        public boolean core() {
            return has(Severity.CORE);
        }

        public Map<Rule, Long> countByRule() {
            return violations.stream().collect(Collectors.groupingBy(Violation::rule,
                    () -> new java.util.EnumMap<>(Rule.class), Collectors.counting()));
        }
    }

    public Verdict evaluate(List<StepData> steps, List<PlaceCandidate> candidates, Context ctx) {
        CandidateLookup lookup = new CandidateLookup(candidates);
        String pace = ctx.pace() != null ? ctx.pace() : "normal";
        // 모르면 any(가장 좁은 상한)로 본다
        double[] limits = distanceLimits(ctx.transportPref() != null ? ctx.transportPref() : "any", pace);

        TreeMap<Integer, List<StepData>> byDay = (steps == null ? List.<StepData>of() : steps).stream()
                .sorted(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));

        List<Violation> out = new ArrayList<>();
        for (int d = 1; d <= ctx.dayCount(); d++) {
            if (!byDay.containsKey(d)) out.add(new Violation(Rule.DAY_MISSING, d, "day" + d + " 일정 없음"));
        }

        MealTally tally = new MealTally();
        for (Map.Entry<Integer, List<StepData>> entry : byDay.entrySet()) {
            int day = entry.getKey();
            boolean first = day == byDay.firstKey();
            boolean last = day == byDay.lastKey();
            checkDay(day, entry.getValue(), first, last, limits, ctx, lookup, tally, out);
        }
        tally.checkTrip(byDay.size(), out);
        checkAddressRegion(byDay, out);
        checkRequiredExperiences(steps, candidates, ctx.requiredExperiences(), out);
        return new Verdict(VERSION, out, tally.meals, tally.unknown);
    }

    private void checkDay(int day, List<StepData> daySteps, boolean first, boolean last, double[] limits,
                          Context ctx, CandidateLookup lookup, MealTally tally, List<Violation> out) {
        int dayStart = toMinutesOrZero(daySteps.get(0).startTime());
        StepData lastStep = daySteps.get(daySteps.size() - 1);
        // 최적화기는 출발 허브 도착 시각으로 마감·끼니를 맞춘다. 허브 체류가 끝나는 시각으로 보면
        // 20시 도착이 마감 초과가 되고, 18시 도착이 저녁 없음이 된다
        boolean endsAtHub = lookup.role(lastStep) == CandidatePolicy.Role.TRANSIT;
        int dayEnd = toMinutesOrZero(endsAtHub ? lastStep.startTime() : lastStep.endTime());

        // 오후 도착·오전 출발인 날은 그 끼니를 요구하지 않는다
        if (dayStart <= LUNCH_START && dayEnd >= LUNCH_END
                && !InvariantChecker.hasMealInWindow(daySteps, LUNCH_START, LUNCH_END, lookup::isMeal)) {
            out.add(new Violation(Rule.LUNCH_MISSING, day, "day" + day + " 점심 없음"));
        }
        if (dayStart <= DINNER_START && dayEnd >= DINNER_END
                && !InvariantChecker.hasMealInWindow(daySteps, DINNER_START, DINNER_END, lookup::isMeal)) {
            out.add(new Violation(Rule.DINNER_MISSING, day, "day" + day + " 저녁 없음"));
        }
        if (!last && lookup.role(lastStep) != CandidatePolicy.Role.LODGING) {
            out.add(new Violation(Rule.NOT_ENDING_AT_LODGING, day, "day" + day + " 숙소로 끝나지 않음"));
        }
        if (last && dayEnd > DEPARTURE_HUB_LATEST_MINUTES) {
            out.add(new Violation(Rule.DEPARTURE_LATE, day, "day" + day + " 출발 마감 초과"));
        }
        if (last && ctx.lastDayHubDeadline() != null && endsAtHub && dayEnd > ctx.lastDayHubDeadline()) {
            out.add(new Violation(Rule.DEPARTURE_MISSED, day, "day" + day + " 사용자 출발 시각 초과"));
        }

        double dayKm = 0;
        for (int i = 0; i < daySteps.size(); i++) {
            StepData s = daySteps.get(i);
            if (s.transportationDistance() == null || InvariantChecker.isOutsideDayBudget(daySteps, i, first, last)) {
                continue;
            }
            // 저장된 이동거리는 도로 환산값이라 상한과 같은 직선 단위로 되돌린다
            double straight = s.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR;
            dayKm += straight;
            if (straight > limits[0]) {
                out.add(new Violation(Rule.LEG_OVER_CAP, day,
                        "day" + day + " 구간 상한 초과(" + CandidateLookup.name(s) + ")", List.of(shownName(s))));
            }
        }
        if (dayKm > limits[1]) {
            String shownKm = String.format("%.0f", dayKm * GeoUtils.ROAD_DISTANCE_FACTOR);
            out.add(new Violation(Rule.DAY_DISTANCE_OVER_CAP, day, String.format(
                    "day%d 하루 이동 %skm(상한 %.0fkm)", day, shownKm,
                    limits[1] * GeoUtils.ROAD_DISTANCE_FACTOR), List.of(shownKm)));
        }

        checkOpeningHours(day, daySteps, ctx.startDate(), lookup, out);
        checkComposition(day, daySteps, dayStart, dayEnd, lookup, out);
        tally.addDay(day, daySteps, lookup, out);

        // 최적화기(DayState.endsEarlyByDeparture)와 면제 기준이 같아야 같은 날을 두고 판정이 갈리지 않는다
        boolean endsEarlyByDeparture = last && ctx.lastDayHubDeadline() != null
                && ctx.lastDayHubDeadline() < EARLY_FINISH_MINUTES;
        int lastActivityEnd = lookup.lastActivityEnd(daySteps);
        if (!endsEarlyByDeparture && lastActivityEnd > 0 && lastActivityEnd < EARLY_FINISH_MINUTES) {
            String end = ScheduleTimes.formatMinutes(lastActivityEnd);
            out.add(new Violation(Rule.EARLY_FINISH, day, "day" + day + " 일정이 " + end + "에 끝남", List.of(end)));
        }
    }

    private void checkRequiredExperiences(List<StepData> steps, List<PlaceCandidate> candidates,
                                          Map<Long, String> required, List<Violation> out) {
        if (required.isEmpty()) return;
        Set<Long> placed = (steps == null ? List.<StepData>of() : steps).stream()
                .filter(s -> s.place() != null && s.place().placeId() != null)
                .map(s -> s.place().placeId())
                .collect(Collectors.toSet());
        // plan_context(JSONB)는 객체 키 순서를 바꾸므로 id 순으로 돌아야 편집 전후 안내 순서가 같다.
        for (Map.Entry<Long, String> entry : required.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Long id = entry.getKey();
            if (placed.contains(id)) continue;
            PlaceCandidate c = (candidates == null ? List.<PlaceCandidate>of() : candidates).stream()
                    .filter(x -> x != null && id.equals(x.placeId())).findFirst().orElse(null);
            String known = entry.getValue() == null || entry.getValue().isBlank() ? "id " + id : entry.getValue();
            String shown = c == null ? known : shownName(c.name(), c.nameKo());
            out.add(new Violation(Rule.REQUIRED_EXPERIENCE_MISSING, 0, "필수 경험 장소 빠짐(" + shown + ")",
                    List.of(shown)));
        }
    }

    /** 연박 숙소처럼 여러 날 나오는 장소도 한 번만 센다. */
    private void checkAddressRegion(TreeMap<Integer, List<StepData>> byDay, List<Violation> out) {
        Set<String> seen = new HashSet<>();
        byDay.forEach((day, daySteps) -> {
            for (StepData s : daySteps) {
                if (s.place() == null) continue;
                String key = s.place().placeId() != null ? "id:" + s.place().placeId() : "name:" + CandidateLookup.name(s);
                if (KoreanAddressRegion.contradicts(s.place().address(), s.place().region()) && seen.add(key)) {
                    out.add(new Violation(Rule.ADDRESS_REGION_MISMATCH, day,
                            "day" + day + " 주소와 지역 불일치(" + CandidateLookup.name(s) + ")", List.of(shownName(s))));
                }
            }
        });
    }

    private void checkOpeningHours(int day, List<StepData> daySteps, LocalDate startDate, CandidateLookup lookup,
                                   List<Violation> out) {
        if (startDate == null) return;
        DayOfWeek dow = startDate.plusDays(day - 1L).getDayOfWeek();
        for (StepData s : daySteps) {
            PlaceCandidate c = lookup.of(s);
            if (c == null || c.openingHours() == null || c.openingHours().isBlank()) continue;
            CandidatePolicy.Role role = CandidatePolicy.roleOf(c);
            if (role == CandidatePolicy.Role.LODGING || role == CandidatePolicy.Role.TRANSIT) continue;
            if (OpeningHoursText.isClosedOnDay(c.openingHours(), dow)) {
                out.add(new Violation(Rule.CLOSED_DAY, day, "day" + day + " 휴무일 방문(" + c.name() + ")",
                        List.of(shownName(s))));
            } else if (!OpeningHoursText.isOpenDuring(c.openingHours(), dow,
                    toMinutesOrZero(s.startTime()), toMinutesOrZero(s.endTime()))) {
                out.add(new Violation(Rule.OUTSIDE_HOURS, day, "day" + day + " 영업시간 밖(" + c.name() + ")",
                        List.of(shownName(s))));
            }
        }
    }

    /** 카페를 옮겨 다니는 날과 먹기만 하는 날. 먹기만 하는 날은 점심·저녁 창을 모두 덮는 날만 본다. */
    private void checkComposition(int day, List<StepData> daySteps, int dayStart, int dayEnd,
                                  CandidateLookup lookup, List<Violation> out) {
        List<String> cafes = daySteps.stream().filter(s -> lookup.role(s) == CandidatePolicy.Role.CAFE)
                .map(ItineraryRules::shownName).toList();
        if (cafes.size() > CAFE_PER_DAY_CAP) {
            out.add(new Violation(Rule.CAFE_PER_DAY, day, "day" + day + " 카페·디저트 " + cafes.size() + "곳", cafes));
        }
        boolean fullDay = dayStart <= LUNCH_START && dayEnd >= DINNER_START;
        if (fullDay && daySteps.stream().noneMatch(s -> lookup.role(s).isVisit())) {
            out.add(new Violation(Rule.NO_NON_FOOD_ACTIVITY, day, "day" + day + " 식사·카페 외 활동 없음"));
        }
    }

    // 해외 장소는 안내에 한국어 이름을 쓴다.
    private static String shownName(StepData s) {
        return s.place() == null ? "" : shownName(s.place().name(), s.place().nameKo());
    }

    static String shownName(String name, String nameKo) {
        return nameKo != null && !nameKo.isBlank() ? nameKo : name == null ? "" : name;
    }

    /** 같은 날 점심·저녁, 전날 저녁·다음 날 점심, 여행 전체의 음식 종류·묶음 반복. */
    private static final class MealTally {
        private final Map<String, List<String>> placesByKind = new LinkedHashMap<>();
        private final Map<String, Set<String>> kindsByGroup = new LinkedHashMap<>();
        private final Map<String, List<String>> placesByGroup = new LinkedHashMap<>();
        private String previousDinnerKind;
        private String previousDinnerName;
        private int previousDinnerDay;
        int meals;
        int unknown;

        void addDay(int day, List<StepData> daySteps, CandidateLookup lookup, List<Violation> out) {
            String lunchKind = null;
            String dinnerKind = null;
            String lunchName = null;
            String dinnerName = null;
            boolean lunchSeen = false;
            for (StepData s : daySteps) {
                if (s.place() == null || !lookup.isMeal(s)) continue;
                String kind = lookup.mealKind(s);
                String name = shownName(s);
                meals++;
                if (!CandidateLookup.isSpecificKind(kind)) {
                    unknown++;
                    kind = null;
                } else {
                    placesByKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(name);
                    String group = PlaceCategoryConstants.mealGroup(kind);
                    placesByGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(name);
                    kindsByGroup.computeIfAbsent(group, g -> new LinkedHashSet<>()).add(kind);
                }
                int start = toMinutesOrZero(s.startTime());
                if (!lunchSeen && InvariantChecker.inMealWindow(start, LUNCH_START, LUNCH_END)) {
                    lunchSeen = kind != null;
                    lunchKind = kind;
                    lunchName = name;
                }
                if (InvariantChecker.inMealWindow(start, DINNER_START, DINNER_END)) {
                    dinnerKind = kind;
                    dinnerName = name;
                }
            }
            if (PlaceCategoryConstants.sameMealKind(lunchKind, dinnerKind)) {
                out.add(new Violation(Rule.SAME_DAY_MEAL_KIND, day,
                        "day" + day + " 점심·저녁 같은 종류(" + lunchKind + ")", List.of(lunchName, dinnerName)));
            } else {
                String lunchGroup = PlaceCategoryConstants.mealGroup(lunchKind);
                if (lunchGroup != null && lunchGroup.equals(PlaceCategoryConstants.mealGroup(dinnerKind))) {
                    out.add(new Violation(Rule.SAME_DAY_MEAL_GROUP, day,
                            "day" + day + " 점심·저녁 같은 묶음(" + lunchGroup + ")", List.of(lunchName, dinnerName)));
                }
                if (previousDinnerDay == day - 1 && PlaceCategoryConstants.sameMealKind(lunchKind, previousDinnerKind)) {
                    out.add(new Violation(Rule.CONSECUTIVE_MEAL_KIND, day,
                            "day" + day + " 전날 저녁과 점심 같은 종류(" + lunchKind + ")",
                            List.of(previousDinnerName, lunchName)));
                }
            }
            previousDinnerKind = dinnerKind;
            previousDinnerName = dinnerName;
            previousDinnerDay = day;
        }

        void checkTrip(int dayCount, List<Violation> out) {
            int cap = mealKindTripCap(dayCount);
            placesByKind.forEach((kind, places) -> {
                if (places.size() > cap) {
                    out.add(new Violation(Rule.MEAL_KIND_REPEATED, 0,
                            "같은 종류 " + kind + " " + places.size() + "끼(상한 " + cap + ")", places));
                }
            });
            // 한 요리만 반복된 묶음은 위 종류 상한이 이미 잡는다.
            int groupCap = mealGroupTripCap(dayCount);
            placesByGroup.forEach((group, places) -> {
                if (places.size() > groupCap && kindsByGroup.get(group).size() > 1) {
                    out.add(new Violation(Rule.MEAL_GROUP_REPEATED, 0,
                            "같은 묶음 " + group + " " + places.size() + "끼(상한 " + groupCap + ")", places));
                }
            });
        }
    }
}
