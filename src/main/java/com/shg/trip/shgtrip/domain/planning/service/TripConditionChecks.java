package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Context;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 여행 조건(요구·도시 일수·피할 것·동행·도착과 출발)이 있어야 판정할 수 있는 규칙. 조건을 기록하기 전에 저장한 일정은
 * 이 규칙을 보지 않는다.
 */
final class TripConditionChecks {

    /** 하루 이동(대중교통·도보)이 이보다 길면 걷기 힘든 동행에게는 하루가 이동으로 끝난다. */
    private static final int LOW_MOBILITY_DAILY_TRANSIT = 200;
    private static final int DAILY_TRANSIT = 270;
    /** 도시 안 한 구간이 이보다 길면 일정이 권역을 벗어났다. */
    private static final int LONG_LEG = 75;
    /** 앞 끼니가 끝나고 이보다 빨리 다음 끼니를 시작하면 연달아 먹는 것이다. */
    static final int MEAL_GAP = 80;
    private static final int SUNRISE_SLACK = 20;
    /** 빡빡하게·보통 여행에서 활동 사이가 이보다 비면 하루의 취지가 깨진 것으로 본다. */
    static final int LONG_GAP = 150;
    private static final int EVENING_EARLY = 30;
    private static final int STRENUOUS_MINUTES = 90;
    private static final Pattern STRENUOUS = Pattern.compile("Hiking Trail|Mountain|Trail|산$|봉$|오름$|등산");
    /** 출발 허브로 떠나기 전 이보다 짧게 남으면 그날은 관광 없이 떠나는 날이다. */
    private static final int MIN_DEPARTURE_DAY_MINUTES = 120;
    private static final int DAY_START_MINUTES = 9 * 60 + 30;
    private static final int LATE_ARRIVAL_HOUR = 18;

    private TripConditionChecks() {}

    record Visit(StepData step, PlaceCandidate place, int start, int end) {
        CandidatePolicy.Role role() {
            return CandidatePolicy.roleOf(place);
        }

        boolean stayOrHub() {
            return role() == CandidatePolicy.Role.LODGING || role() == CandidatePolicy.Role.TRANSIT;
        }

        boolean meal() {
            return role() == CandidatePolicy.Role.MEAL;
        }

        boolean eatery() {
            CandidatePolicy.Role r = role();
            return r == CandidatePolicy.Role.MEAL || r == CandidatePolicy.Role.CAFE || r == CandidatePolicy.Role.BAR;
        }

        boolean sight() {
            return !stayOrHub() && !eatery();
        }

        String name() {
            return Objects.toString(place.displayName(), "");
        }
    }

    static void check(List<StepData> steps, CandidateLookup lookup, Context ctx,
                      Map<Long, PlaceFactsReader.PlaceFact> facts, List<Violation> out) {
        TripConditions conditions = ctx.conditions();
        Map<Integer, List<Visit>> days = new TreeMap<>();
        for (StepData s : steps) {
            PlaceCandidate c = lookup.byPlaceId(s);
            Integer start = ScheduleTimes.toMinutesOrNull(s.startTime());
            Integer end = ScheduleTimes.toMinutesOrNull(s.endTime());
            if (c == null || start == null || end == null) continue;
            days.computeIfAbsent(s.dayNumber(), k -> new ArrayList<>()).add(new Visit(s, c, start, end));
        }
        wants(conditions, days, ctx.startDate(), ctx.country(), facts, out);
        cityDays(conditions, days, ctx.cities(), out);
        for (int d = 1; d <= ctx.dayCount(); d++) {
            List<Visit> day = days.getOrDefault(d, List.of());
            boolean first = d == 1;
            boolean last = d == ctx.dayCount();
            boolean lateArrival = first && conditions.arrival() != null
                    && conditions.arrival().getHour() >= LATE_ARRIVAL_HOUR;
            boolean earlyDeparture = last && conditions.departure() != null
                    && usableBeforeDeparture(conditions, day, ctx.country()) < MIN_DEPARTURE_DAY_MINUTES;
            if (activities(day).isEmpty()) {
                if (!lateArrival && !earlyDeparture) {
                    out.add(new Violation(Rule.DAY_MISSING, d, d + "일차에 일정이 없음"));
                }
                continue;
            }
            mealsBackToBack(day, d, out);
            pace(day, d, first, last, conditions, out);
            movement(day, d, conditions, out);
            if (last && conditions.departure() != null) departure(day, d, conditions, ctx.country(), out);
        }
        duplicates(days, facts, ctx.cities().stream().map(ItineraryRules.City::nameKo).toList(), out);
        avoid(conditions, days, out);
    }

    /**
     * 출발 허브의 수속 시간을 빼고 그날 쓸 수 있는 시간. 짓는 쪽과 같이 허브를 모르면 공항으로 보고, 차로 다니는데
     * 허브 없이 끝나면 차로 돌아가는 날로 본다.
     */
    private static int usableBeforeDeparture(TripConditions conditions, List<Visit> lastDay, String country) {
        Visit end = lastDay.isEmpty() ? null : lastDay.get(lastDay.size() - 1);
        PlaceCandidate hub = hubOrNull(end == null ? null : end.place());
        boolean drivesHome = drivesHome(hub, conditions);
        // 허브까지 실제 이동을 알면 그 시간을 뺀다. 시내에서 먼 공항은 기본값보다 오래 걸려 그날 쓸 시간이 없다.
        Integer leg = hub == null ? null : end.step().transportationDuration();
        int lead = drivesHome ? 0 : (leg != null ? leg : TripHubs.HUB_TRAVEL_MINUTES) + TripHubs.estimatedLegSlack(leg);
        return TripHubs.departureDeadline(conditions.departure(), hub, drivesHome, country) - lead - DAY_START_MINUTES;
    }

    private static PlaceCandidate hubOrNull(PlaceCandidate end) {
        return end != null && CandidatePolicy.roleOf(end) == CandidatePolicy.Role.TRANSIT ? end : null;
    }

    private static boolean drivesHome(PlaceCandidate hub, TripConditions conditions) {
        return hub == null && "car".equals(conditions.transport());
    }

    private static List<Visit> activities(List<Visit> day) {
        return day.stream().filter(v -> !v.stayOrHub()).toList();
    }

    private static void wants(TripConditions conditions, Map<Integer, List<Visit>> days, LocalDate startDate,
                              String country, Map<Long, PlaceFactsReader.PlaceFact> facts, List<Violation> out) {
        PlaceRestrictions restrictions = PlaceRestrictions.of(conditions.avoid());
        for (TripConditions.Want want : conditions.wants()) {
            // 피할 조건과 부딪치는 요구는 대화에서 되물어 정리할 입력이라 짓는 쪽의 위반으로 세지 않는다.
            if (restrictions.violatedByDish(want.food() != null ? want.food() : want.text()) != null) continue;
            List<Visit> hits = new ArrayList<>();
            for (List<Visit> day : days.values()) {
                for (Visit v : activities(day)) {
                    if (WantMatcher.matches(want, v.place())
                            || WantMatcher.partOf(want, facts.get(v.place().placeId()))) hits.add(v);
                }
            }
            // 같은 장소를 낮에도 지나가면 시각 조건을 지킨 방문을 찾는다.
            Visit hit = hits.stream().filter(v -> timingMet(want, v, startDate, country)).findFirst()
                    .orElse(hits.isEmpty() ? null : hits.get(0));
            if (hit == null) {
                out.add(new Violation(missingRule(want), 0, "요청한 '" + want.text() + "'이(가) 없음",
                        List.of(want.text()), "'" + want.text() + "'을(를) 넣을 날짜와 시간대를 정한다"));
                continue;
            }
            if (DayTiming.hoursUnknown(hit.place(), facts.get(hit.place().placeId()))) {
                out.add(new Violation(Rule.HOURS_UNKNOWN, hit.step().dayNumber(), hit.name() + " 영업시간 미확인",
                        List.of(want.text(), hit.name())));
            }
            String timing = want.timing();
            if (timing == null) continue;
            LocalDate date = startDate.plusDays(hit.step().dayNumber() - 1);
            double[] at = CandidateGeo.coordsOf(hit.place());
            if (at == null) continue;
            int sunrise = SolarClock.sunrise(date, at[0], at[1], country);
            int sunset = SolarClock.sunset(date, at[0], at[1], country);
            boolean sunriseWant = "sunrise".equals(timing) || want.text().contains("일출");
            if (sunriseWant && hit.start() > sunrise + SUNRISE_SLACK) {
                out.add(new Violation(Rule.WANT_TIME, hit.step().dayNumber(),
                        "'" + want.text() + "'을(를) 일출(" + ScheduleTimes.formatMinutes(sunrise) + ")이 아닌 "
                                + hit.step().startTime() + "에 감", List.of(want.text()),
                        "전날 밤을 그 근처에서 묵고 일출 전에 도착한다"));
            } else if ("evening".equals(timing) && hit.start() < sunset - EVENING_EARLY) {
                out.add(new Violation(Rule.WANT_TIME, hit.step().dayNumber(),
                        "'" + want.text() + "'을(를) 해 지기 전 " + hit.step().startTime() + "에 감",
                        List.of(want.text()), "해 진 뒤로 옮긴다"));
            }
        }
    }

    /**
     * 일정에 없는 요구의 규칙. 넣을 수 있었던 요구는 짓는 쪽의 잘못이다. 넣을 수 없던 요구는 여행자가 대화에서 알고도
     * 두기로 했으면 안내만 하고, 꼭 갈 곳으로 직접 정했는데 동의를 받지 않았으면 저장하지 않는다. 음식 요구는 메뉴
     * 데이터로만 확인되어 저장을 막지 않는다.
     */
    private static Rule missingRule(TripConditions.Want want) {
        if (want.accepted()) return Rule.WANT_ACCEPTED_UNMET;
        if (want.food() != null) return Rule.WANT_FOOD_MISSING;
        return switch (want.reach()) {
            case PLACEABLE -> Rule.WANT_MISSING;
            case NOT_FOUND -> want.required() ? Rule.REQUIRED_NOT_FOUND : Rule.WANT_NOT_FOUND;
            case NO_SLOT -> want.required() ? Rule.REQUIRED_NO_SLOT : Rule.WANT_NO_SLOT;
        };
    }

    private static boolean timingMet(TripConditions.Want want, Visit v, LocalDate startDate, String country) {
        if (want.timing() == null) return true;
        double[] at = CandidateGeo.coordsOf(v.place());
        if (at == null) return true;
        LocalDate date = startDate.plusDays(v.step().dayNumber() - 1);
        if ("sunrise".equals(want.timing()) || want.text().contains("일출")) {
            return v.start() <= SolarClock.sunrise(date, at[0], at[1], country) + SUNRISE_SLACK;
        }
        return !"evening".equals(want.timing())
                || v.start() >= SolarClock.sunset(date, at[0], at[1], country) - EVENING_EARLY;
    }

    private static void cityDays(TripConditions conditions, Map<Integer, List<Visit>> days,
                                 List<ItineraryRules.City> cities, List<Violation> out) {
        if (conditions.cityDays() == null || conditions.cityDays().isEmpty()) return;
        if (cities.size() < 2) return;
        Map<String, Integer> actual = new HashMap<>();
        for (List<Visit> day : days.values()) {
            Map<String, Integer> votes = new HashMap<>();
            for (Visit v : activities(day)) {
                double[] at = CandidateGeo.coordsOf(v.place());
                if (at == null) continue;
                ItineraryRules.City near = cities.stream().min(Comparator.comparingDouble(
                        c -> GeoUtils.haversine(at, new double[]{c.lat(), c.lng()}))).orElse(null);
                if (near != null) votes.merge(near.nameKo(), 1, Integer::sum);
            }
            votes.entrySet().stream().max(Map.Entry.comparingByValue())
                    .ifPresent(e -> actual.merge(e.getKey(), 1, Integer::sum));
        }
        for (Map.Entry<String, Integer> want : conditions.cityDays().entrySet()) {
            int got = actual.getOrDefault(want.getKey(), 0);
            if (got < want.getValue()) {
                out.add(new Violation(Rule.CITY_DAYS, 0, want.getKey() + " " + want.getValue() + "일 요청에 실제 " + got
                        + "일", List.of(want.getKey(), String.valueOf(want.getValue()), String.valueOf(got)),
                        "도시 간 이동 날짜와 귀가 시각을 다시 맞춘다"));
            }
        }
    }

    private static void mealsBackToBack(List<Visit> day, int d, List<Violation> out) {
        List<Visit> meals = activities(day).stream().filter(Visit::meal).toList();
        for (int i = 1; i < meals.size(); i++) {
            if (meals.get(i).start() - meals.get(i - 1).end() < MEAL_GAP) {
                out.add(new Violation(Rule.MEALS_BACK_TO_BACK, d,
                        d + "일차 " + meals.get(i - 1).name() + " 뒤 바로 " + meals.get(i).name()));
            }
        }
    }

    /**
     * 페이스가 정한 하루 관광 수에 못 미치거나, 여유롭게가 아닌데 활동 사이가 길게 비면 알린다. 도착일·출발일은 반나절이라
     * 관광 수를 세지 않는다.
     */
    private static void pace(List<Visit> day, int d, boolean first, boolean last, TripConditions conditions,
                             List<Violation> out) {
        if (conditions.pace() == null) return;
        List<Visit> acts = activities(day);
        long sights = acts.stream().filter(Visit::sight).count();
        int[] range = ScheduleTuning.sightsPerDay(conditions.pace());
        if (!first && !last && sights < range[0]) {
            out.add(new Violation(Rule.PACE_SHORT, d, d + "일차 관광 " + sights + "곳(페이스 기준 " + range[0] + "~"
                    + range[1] + "곳)", List.of(), "그날 권역의 장소를 더 넣는다"));
        }
        if ("relaxed".equals(conditions.pace())) return;
        for (int i = 1; i < acts.size(); i++) {
            Integer leg = acts.get(i).step().transportationDuration();
            int gap = acts.get(i).start() - acts.get(i - 1).end() - (leg == null ? 0 : leg);
            if (gap >= LONG_GAP) {
                out.add(new Violation(Rule.LONG_GAP, d, d + "일차 " + acts.get(i - 1).step().endTime() + "~"
                        + acts.get(i).step().startTime() + " " + gap + "분 빔", List.of(), "그 사이에 근처 장소를 넣는다"));
            }
        }
    }

    private static void movement(List<Visit> day, int d, TripConditions conditions, List<Violation> out) {
        int total = 0;
        for (Visit v : day) {
            Integer leg = v.step().transportationDuration();
            String mode = Objects.toString(v.step().transportationMode(), "");
            if (leg == null || List.of("TRAIN", "FLIGHT").contains(mode)) continue;
            total += leg;
            if (leg > LONG_LEG) {
                out.add(new Violation(Rule.LONG_LEG, d, v.name() + "까지 " + mode + " " + leg + "분"));
            }
        }
        boolean transit = !"car".equals(conditions.transport());
        int limit = conditions.lowMobility() ? LOW_MOBILITY_DAILY_TRANSIT : DAILY_TRANSIT;
        if (transit && total > limit) {
            out.add(new Violation(Rule.DAILY_TRANSIT, d, d + "일차 이동만 " + total + "분", List.of(),
                    "숙소를 그날 동선 가까이 두거나 권역을 하나로 줄인다"));
        }
        if (conditions.lowMobility()) {
            for (Visit v : activities(day)) {
                if (STRENUOUS.matcher(Objects.toString(v.place().category(), "") + " " + v.name()).find()
                        && v.end() - v.start() >= STRENUOUS_MINUTES) {
                    out.add(new Violation(Rule.STRENUOUS, d, "걷기 힘든 동행에게 " + v.name() + " "
                            + (v.end() - v.start()) + "분"));
                }
            }
        }
    }

    private static void departure(List<Visit> day, int d, TripConditions conditions, String country,
                                  List<Violation> out) {
        Visit end = day.get(day.size() - 1);
        PlaceCandidate hub = hubOrNull(end.place());
        int deadline = TripHubs.departureDeadline(conditions.departure(), hub, drivesHome(hub, conditions), country);
        // 허브까지의 이동은 추정이라 여유를 더한 도착으로 본다. 빠듯한 일정을 지킬 수 있다고 확정하지 않는다.
        int slack = hub == null ? 0 : TripHubs.estimatedLegSlack(end.step().transportationDuration());
        if (end.start() + slack > deadline) {
            out.add(new Violation(Rule.DEPARTURE_MISSED, d, "출발 " + conditions.departure() + "인데 " + end.name()
                    + " " + end.step().startTime() + " 도착(추정 이동 여유 " + slack + "분)", List.of(end.name())));
        }
    }

    private static void duplicates(Map<Integer, List<Visit>> days, Map<Long, PlaceFactsReader.PlaceFact> facts,
                                   List<String> cityNames, List<Violation> out) {
        Map<String, List<Integer>> seen = new HashMap<>();
        for (Map.Entry<Integer, List<Visit>> e : days.entrySet()) {
            for (Visit v : activities(e.getValue())) {
                if (v.meal() || v.role() == CandidatePolicy.Role.CAFE) continue;
                PlaceFactsReader.PlaceFact f = facts.get(v.place().placeId());
                String name = f != null && f.partOf() != null && !f.partOf().isBlank() ? f.partOf() : v.name();
                String k = DraftPlaceResolver.queryKey(WantMatcher.BRACKETS.matcher(name).replaceAll(" "), cityNames);
                List<Integer> where = seen.computeIfAbsent(k, x -> new ArrayList<>());
                if (!where.isEmpty()) {
                    out.add(new Violation(Rule.DUPLICATE, e.getKey(),
                            v.name() + "은(는) " + where.get(0) + "일차에 간 곳과 같음", List.of(v.name())));
                }
                where.add(e.getKey());
            }
        }
    }

    private static void avoid(TripConditions conditions, Map<Integer, List<Visit>> days, List<Violation> out) {
        if (conditions.avoid() == null || conditions.avoid().isEmpty()) return;
        PlaceRestrictions restrictions = PlaceRestrictions.of(conditions.avoid());
        for (Map.Entry<Integer, List<Visit>> e : days.entrySet()) {
            for (Visit v : activities(e.getValue())) {
                // 사용자가 직접 고른 곳은 피할 조건에 걸려도 짓는 쪽(TripExclusions)이 남긴다.
                if (conditions.chosenPlaceIds().contains(v.place().placeId())) continue;
                var why = restrictions.violatedBy(v.place());
                if (why != null) {
                    out.add(new Violation(Rule.AVOID, e.getKey(), v.name() + ": 피할 " + why, List.of(v.name())));
                }
            }
        }
    }
}
