package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
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
 * 생성 중 편집 판단·재생 채점·저장 관문·편집 재판정이 같은 판정을 써야 기준이 갈라지지 않는다.
 */
public final class ItineraryRules {

    /** 규칙이나 등급이 바뀌면 올린다. 다른 버전의 판정 기록끼리 비교하지 않기 위해서다. */
    public static final int VERSION = 6;

    public enum Severity {
        /** 지킬 수 없는 일정이다. 저장하지 않는다. */
        HARD,
        /** 덜 좋은 일정이다. 저장을 막지 않고 어떤 것을 안내할지는 {@link QualityNotices}가 고른다. */
        SOFT,
        /** 지킬 수 있는지 데이터로 확인하지 못했다. 지켰다고 보지 않고 저장 뒤 안내로 남긴다. */
        UNKNOWN
    }

    public enum Rule {
        DAY_MISSING(Severity.HARD),
        CLOSED_DAY(Severity.HARD),
        OUTSIDE_HOURS(Severity.HARD),
        DEPARTURE_MISSED(Severity.HARD),
        WANT_MISSING(Severity.HARD),
        WANT_TIME(Severity.HARD),
        CITY_DAYS(Severity.HARD),
        AREA_MISSING(Severity.HARD),
        /** 숙소는 묵기로 한 도시 안에서 고르므로 어긋나면 그 도시에 쓸 숙소가 없다. 편집은 숙소를 바꾸지 못한다. */
        STAY_MISMATCH(Severity.HARD, true),
        /** 숙소에서 바로 허브로 가도 출발편 기한에 닿지 못한다. 편집으로 고칠 장소가 없다. */
        DEPARTURE_UNREACHABLE(Severity.HARD, true),
        AVOID(Severity.HARD),
        UNREACHABLE(Severity.HARD),
        TRAVEL_MISSING(Severity.HARD),
        NOT_ENDING_AT_LODGING(Severity.HARD),
        REQUIRED_NOT_FOUND(Severity.HARD, true),
        REQUIRED_NO_SLOT(Severity.HARD, true),
        LUNCH_MISSING(Severity.SOFT),
        DINNER_MISSING(Severity.SOFT),
        MEALS_BACK_TO_BACK(Severity.SOFT),
        DEPARTURE_LATE(Severity.SOFT),
        SAME_DAY_MEAL_KIND(Severity.SOFT),
        MEAL_KIND_REPEATED(Severity.SOFT),
        CAFE_PER_DAY(Severity.SOFT),
        NO_NON_FOOD_ACTIVITY(Severity.SOFT),
        ADDRESS_REGION_MISMATCH(Severity.SOFT),
        WANT_FOOD_MISSING(Severity.SOFT),
        WANT_NOT_FOUND(Severity.SOFT),
        WANT_NO_SLOT(Severity.SOFT),
        WANT_ACCEPTED_UNMET(Severity.SOFT),
        PACE_SHORT(Severity.SOFT),
        LONG_GAP(Severity.SOFT),
        LONG_LEG(Severity.SOFT),
        /** 렌터카를 숙소·역·공항이 아닌 곳에서 받거나 두고 다른 교통편으로 바꾼다. */
        RENTAL_HANDOFF(Severity.SOFT),
        DAILY_TRANSIT(Severity.SOFT),
        STRENUOUS(Severity.SOFT),
        DUPLICATE(Severity.SOFT),
        LEG_OVER_CAP(Severity.SOFT),
        DAY_DISTANCE_OVER_CAP(Severity.SOFT),
        EARLY_FINISH(Severity.SOFT),
        CONSECUTIVE_MEAL_KIND(Severity.SOFT),
        SAME_DAY_MEAL_GROUP(Severity.SOFT),
        MEAL_GROUP_REPEATED(Severity.SOFT),
        HOURS_UNKNOWN(Severity.UNKNOWN);

        public final Severity severity;
        /** 요청 자체로 생긴 위반이라 일정 편집으로 고칠 수 없다. 대화에서 요청을 바꿔야 한다. */
        public final boolean fromRequest;

        Rule(Severity severity) {
            this(severity, false);
        }

        Rule(Severity severity, boolean fromRequest) {
            this.severity = severity;
            this.fromRequest = fromRequest;
        }
    }

    /**
     * @param day      0이면 여행 전체에 걸친 위반
     * @param evidence 안내 문구에 넣을 근거(장소 이름·시각·거리). 규칙마다 담는 것이 정해져 있다
     * @param fix      일정 편집 모델에게 보일 고칠 방향. 없으면 null
     */
    public record Violation(Rule rule, int day, String message, List<String> evidence, String fix) {
        public Violation {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        Violation(Rule rule, int day, String message) {
            this(rule, day, message, List.of(), null);
        }

        Violation(Rule rule, int day, String message, List<String> evidence) {
            this(rule, day, message, evidence, null);
        }

        /** 기록(generation_traces.score)만 보고 SQL로 등급을 셀 수 있게 함께 직렬화한다. */
        @JsonProperty("severity")
        public Severity severity() {
            return rule.severity;
        }
    }

    /** 여행 도시. 날마다 어느 도시에서 보냈는지 가장 가까운 도시로 센다. */
    public record City(String nameKo, double lat, double lng) {
    }

    /**
     * 일정의 plan_context에 JSON으로 저장된다. 필드 이름을 바꾸면 저장된 일정에서 그 값이 비어 읽혀 편집 뒤 판정이 달라진다.
     *
     * @param dayCount            여행 일수. 0이면 날짜 누락을 판정하지 않는다
     * @param lastDayHubDeadline  사용자 출발 시각에서 정한 허브 도착 마감(분). 없으면 null
     * @param requiredExperiences 여행 조건을 기록하기 전에 저장한 일정의 필수 경험 장소 id와 이름. 새 일정은 비어 있다
     * @param conditions          여행 조건. 기록하기 전에 저장한 일정은 null이고 조건이 필요한 규칙을 보지 않는다
     * @param country             시간대·공휴일을 볼 나라. 모르면 null
     */
    public record Context(String pace, String transportPref, LocalDate startDate, int dayCount,
                          Integer lastDayHubDeadline, Map<Long, String> requiredExperiences,
                          TripConditions conditions, String country, List<City> cities) {
        public Context {
            requiredExperiences = requiredExperiences == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(requiredExperiences));
            cities = cities == null ? List.of() : List.copyOf(cities);
        }

        public Context(String pace, String transportPref, LocalDate startDate, int dayCount,
                       Integer lastDayHubDeadline, Map<Long, String> requiredExperiences) {
            this(pace, transportPref, startDate, dayCount, lastDayHubDeadline, requiredExperiences, null, null,
                    List.of());
        }

        public Context(String pace, String transportPref, LocalDate startDate, int dayCount,
                       Integer lastDayHubDeadline) {
            this(pace, transportPref, startDate, dayCount, lastDayHubDeadline, Map.of());
        }

        boolean departureKnown() {
            return conditions != null ? conditions.departure() != null : lastDayHubDeadline != null;
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

        public List<Violation> hardViolations() {
            return violations.stream().filter(v -> v.severity() == Severity.HARD).toList();
        }
    }

    public Verdict evaluate(List<StepData> steps, List<PlaceCandidate> candidates, Context ctx) {
        return evaluate(steps, candidates, ctx, Map.of());
    }

    /** @param facts 스텝 장소의 사실(휴무 요일·출입 여부·상위 장소). 없는 장소는 사실 없이 판정한다 */
    public Verdict evaluate(List<StepData> steps, List<PlaceCandidate> candidates, Context ctx,
                            Map<Long, PlaceFactsReader.PlaceFact> facts) {
        Map<Long, PlaceFactsReader.PlaceFact> known = facts == null ? Map.of() : facts;
        CandidateLookup lookup = new CandidateLookup(candidates);
        String pace = ctx.pace() != null ? ctx.pace() : "normal";
        // 모르면 any(가장 좁은 상한)로 본다
        double[] limits = distanceLimits(ctx.transportPref() != null ? ctx.transportPref() : "any", pace);

        TreeMap<Integer, List<StepData>> byDay = (steps == null ? List.<StepData>of() : steps).stream()
                .sorted(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));

        List<Violation> out = new ArrayList<>();
        if (ctx.conditions() == null) {
            for (int d = 1; d <= ctx.dayCount(); d++) {
                if (!byDay.containsKey(d)) out.add(new Violation(Rule.DAY_MISSING, d, "day" + d + " 일정 없음"));
            }
        }

        MealTally tally = new MealTally();
        StepData previousDayEnd = null;
        for (Map.Entry<Integer, List<StepData>> entry : byDay.entrySet()) {
            int day = entry.getKey();
            boolean first = day == byDay.firstKey();
            boolean last = day == byDay.lastKey();
            checkDay(day, entry.getValue(), first, last, limits, ctx, lookup, known, tally, out);
            previousDayEnd = checkTravel(day, entry.getValue(), previousDayEnd, lookup, ctx.conditions() != null, out);
        }
        tally.checkTrip(byDay.size(), out);
        checkAddressRegion(byDay, out);
        if (ctx.conditions() == null) {
            checkRequiredExperiences(steps, candidates, ctx.requiredExperiences(), out);
        } else {
            TripConditionChecks.check(steps == null ? List.of() : steps, lookup, ctx, known, out);
        }
        return new Verdict(VERSION, out, tally.meals, tally.unknown);
    }

    /**
     * 구간을 떠나는 곳이 든 지역의 이동 방법으로 본 상한. 짓는 쪽이 그 방법으로 구간을 정하므로 렌터카 지역의 구간을
     * 여행 전체의 대중교통 상한으로 재면 지킨 일정을 넘었다고 본다. 하루 상한은 그날 가장 넓은 방법을 따른다.
     */
    private static double[] legLimits(Context ctx, PlaceCandidate origin, double[] tripLimits) {
        if (ctx.conditions() == null || ctx.conditions().layout().modes().isEmpty()) return tripLimits;
        String fallback = ctx.transportPref() != null ? ctx.transportPref() : "any";
        String mode = ctx.conditions().layout().modeAt(origin == null ? null : CandidateGeo.coordsOf(origin), fallback);
        return mode.equals(fallback) ? tripLimits : ScheduleTuning.distanceLimits(mode, ctx.pace());
    }

    private void checkDay(int day, List<StepData> daySteps, boolean first, boolean last, double[] limits,
                          Context ctx, CandidateLookup lookup, Map<Long, PlaceFactsReader.PlaceFact> facts,
                          MealTally tally, List<Violation> out) {
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
        if (ctx.conditions() == null && last && ctx.lastDayHubDeadline() != null && endsAtHub
                && dayEnd > ctx.lastDayHubDeadline()) {
            out.add(new Violation(Rule.DEPARTURE_MISSED, day, "day" + day + " 사용자 출발 시각 초과"));
        }

        double dayKm = 0;
        double dayLimit = limits[1];
        for (int i = 0; i < daySteps.size(); i++) {
            StepData s = daySteps.get(i);
            if (s.transportationDistance() == null || InvariantChecker.isOutsideDayBudget(daySteps, i, first, last)) {
                continue;
            }
            // 저장된 이동거리는 도로 환산값이라 상한과 같은 직선 단위로 되돌린다
            double straight = s.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR;
            dayKm += straight;
            // 그날 첫 구간은 앞 숙소를 모르므로 도착한 곳의 지역으로 본다. 숙소와 첫 장소는 대개 같은 지역이다.
            double[] legLimits = legLimits(ctx, lookup.byPlaceId(daySteps.get(Math.max(0, i - 1))), limits);
            dayLimit = Math.max(dayLimit, legLimits[1]);
            if (straight > legLimits[0]) {
                out.add(new Violation(Rule.LEG_OVER_CAP, day,
                        "day" + day + " 구간 상한 초과(" + CandidateLookup.name(s) + ")", List.of(shownName(s))));
            }
        }
        if (dayKm > dayLimit) {
            String shownKm = String.format("%.0f", dayKm * GeoUtils.ROAD_DISTANCE_FACTOR);
            out.add(new Violation(Rule.DAY_DISTANCE_OVER_CAP, day, String.format(
                    "day%d 하루 이동 %skm(상한 %.0fkm)", day, shownKm,
                    dayLimit * GeoUtils.ROAD_DISTANCE_FACTOR), List.of(shownKm)));
        }

        checkOpeningHours(day, daySteps, ctx, lookup, facts, out);
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

    /**
     * 짓는 단계는 하루 안에 오갈 수 없는 구간을 잇지 않는다. 그런데도 이동 시간이 없는 구간은 다른 도시로 건너뛰었거나
     * 오가는 시간을 모르는 곳이다.
     *
     * @param everyLeg 그날 첫 스텝 뒤의 모든 구간에 이동 시간이 있어야 하는지. 여행 조건을 기록하기 전에 저장한 일정은
     *                 이동을 비워 저장한 구간이 있어 먼 구간만 본다
     * @return 그날 좌표가 있는 마지막 스텝. 다음 날 첫 스텝을 이것과 견준다
     */
    private StepData checkTravel(int day, List<StepData> daySteps, StepData previousDayEnd, CandidateLookup lookup,
                                 boolean everyLeg, List<Violation> out) {
        StepData prev = previousDayEnd;
        boolean reported = false;
        for (int i = 0; i < daySteps.size(); i++) {
            StepData s = daySteps.get(i);
            double[] to = CandidateGeo.coordsOf(lookup.of(s));
            // 도시 간 이동 구간은 앞 도시에서 넘어온 이동이라 그 앞 장소와 견주지 않는다.
            if (InvariantChecker.isIntercityLeg(s)) {
                prev = to != null ? s : null;
                continue;
            }
            double[] from = prev == null ? null : CandidateGeo.coordsOf(lookup.of(prev));
            double km = from == null || to == null ? 0 : GeoUtils.haversine(from, to);
            boolean noLeg = s.transportationDuration() == null;
            if (!reported && noLeg && (km > MAX_REASONABLE_LEG_KM || everyLeg && i > 0)) {
                String shownKm = km > 0 ? String.format("%.0f", km * GeoUtils.ROAD_DISTANCE_FACTOR) : "";
                StepData origin = prev != null ? prev : daySteps.get(i - 1);
                out.add(new Violation(Rule.TRAVEL_MISSING, day, "day" + day + " 이동 없이 "
                        + CandidateLookup.name(origin) + " → " + CandidateLookup.name(s)
                        + (shownKm.isEmpty() ? "" : "(" + shownKm + "km)"),
                        List.of(shownName(origin), shownName(s), shownKm)));
                reported = true;
            }
            if (to != null) prev = s;
        }
        return prev;
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
            out.add(new Violation(Rule.WANT_MISSING, 0, "필수 경험 장소 빠짐(" + shown + ")", List.of(shown)));
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

    /** 영업시간에 장소 사실의 휴무 요일을 합쳐 본다. 영업시간이 비어도 휴무 요일만은 아는 장소가 많다. */
    private void checkOpeningHours(int day, List<StepData> daySteps, Context ctx, CandidateLookup lookup,
                                   Map<Long, PlaceFactsReader.PlaceFact> facts, List<Violation> out) {
        if (ctx.startDate() == null) return;
        LocalDate date = ctx.startDate().plusDays(day - 1L);
        DayOfWeek dow = date.getDayOfWeek();
        for (StepData s : daySteps) {
            PlaceCandidate c = lookup.of(s);
            if (c == null) continue;
            CandidatePolicy.Role role = CandidatePolicy.roleOf(c);
            if (role == CandidatePolicy.Role.LODGING || role == CandidatePolicy.Role.TRANSIT) continue;
            PlaceFactsReader.PlaceFact fact = c.placeId() == null ? null : facts.get(c.placeId());
            boolean chosen = ctx.conditions() != null && ctx.conditions().chosenPlaceIds().contains(c.placeId());
            if (fact != null && Boolean.FALSE.equals(fact.reachable()) && !chosen) {
                out.add(new Violation(Rule.UNREACHABLE, day, shownName(s) + "은(는) 일반 관광객이 갈 수 없음",
                        List.of(shownName(s))));
            }
            // 나라를 기록하기 전에 저장한 일정은 한국 명절로 보면 해외 박물관도 휴관이 된다.
            if (ctx.country() != null && TripCalendar.museumsClosed(ctx.country(), date) && DayBuilder.isMuseum(c)) {
                out.add(new Violation(Rule.CLOSED_DAY, day, shownName(s) + " 명절 휴관", List.of(shownName(s))));
                continue;
            }
            String hours = OpeningHoursText.withClosedDays(DayTiming.openingHoursOf(c),
                    fact == null ? null : fact.closedDays());
            if (hours == null || hours.isBlank()) continue;
            if (OpeningHoursText.isClosedOnDay(hours, dow)) {
                out.add(new Violation(Rule.CLOSED_DAY, day, shownName(s) + " 휴무일", List.of(shownName(s))));
            } else if (!OpeningHoursText.isOpenDuring(hours, dow,
                    toMinutesOrZero(s.startTime()), toMinutesOrZero(s.endTime()))) {
                out.add(new Violation(Rule.OUTSIDE_HOURS, day, shownName(s) + " 영업시간 밖(" + s.startTime() + ")",
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
