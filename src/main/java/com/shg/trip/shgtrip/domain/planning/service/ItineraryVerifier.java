package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 완성된 일정이 여행 조건과 사실을 지키는지 검사한다. 고치지 않는다.
 */
@Component
@Profile("eval")
@RequiredArgsConstructor
public class ItineraryVerifier {

    public enum Severity { FATAL, WARN }

    /** @param fix 무엇을 바꾸면 성립하는지. 모르면 null */
    public record Violation(String code, Severity severity, int day, String message, String fix) {
    }

    /** 하루 이동(대중교통·도보)이 이보다 길면 걷기 힘든 동행에게는 하루가 이동으로 끝난다. */
    private static final int LOW_MOBILITY_DAILY_TRANSIT = 200;
    private static final int DAILY_TRANSIT = 270;
    /** 도시 안 한 구간이 이보다 길면 일정이 권역을 벗어났다. */
    private static final int LONG_LEG = 75;
    /** 앞 끼니가 끝나고 이보다 빨리 다음 끼니를 시작하면 연달아 먹는 것이다. */
    private static final int MEAL_GAP = 80;
    /** 출발 허브 도착 뒤 남길 시간. 공항은 수속·반납, 역은 타는 시간이다. */
    private static final int AIRPORT_MARGIN = 90;
    private static final int STATION_MARGIN = 20;
    private static final int SUNRISE_SLACK = 20;
    /** 같은 식당으로 보는 이름 뒤 덧붙은 글자 수("진로집본점"). */
    private static final int BRANCH_SUFFIX_CHARS = 3;
    /** 빡빡하게·보통 여행에서 활동 사이가 이보다 비면 하루의 취지가 깨진 것으로 본다. */
    private static final int LONG_GAP = 150;
    private static final int EVENING_EARLY = 30;
    private static final java.util.Set<String> TIME_WORDS = java.util.Set.of("일출", "일몰", "야경", "야간", "산책",
            "구경", "투어", "밤바다");
    private static final Pattern LOCALITY = Pattern.compile(".+[동읍면리]");
    private static final Pattern BRACKETS = Pattern.compile("[(\\[（【].*?[)\\]）】]");
    /** 저녁을 겸하는 밤 일정. 일정을 짓는 쪽과 같은 기준이다. */
    private static final Pattern FOOD_NIGHT = Pattern.compile("야시장|포차");
    private static final Pattern STRENUOUS = Pattern.compile("Hiking Trail|Mountain|Trail|산$|봉$|오름$|등산");
    /** 음식 종류가 아니라 조리법·끼니를 가리키는 말. 이 말만 맞는 식당은 요청한 음식을 낸다고 볼 수 없다. */
    private static final java.util.Set<String> GENERIC_DISH = java.util.Set.of("구이", "찌개", "요리", "식사", "정식",
            "맛집");

    private final PlaceFactsReader factsReader;

    public List<Violation> verify(TripConditions conditions, LocalDate startDate, int dayCount,
                                  List<StepData> steps, List<PlaceCandidate> candidates, List<String> cityNames) {
        Map<Long, PlaceCandidate> byId = new HashMap<>();
        for (PlaceCandidate c : candidates) if (c.placeId() != null) byId.put(c.placeId(), c);
        Map<Long, PlaceFactsReader.PlaceFact> facts = factsReader.find(byId.keySet());
        Map<Integer, List<Visit>> days = new TreeMap<>();
        for (StepData s : steps) {
            PlaceCandidate c = s.place() == null ? null : byId.get(s.place().placeId());
            Integer start = ScheduleTimes.toMinutesOrNull(s.startTime());
            Integer end = ScheduleTimes.toMinutesOrNull(s.endTime());
            if (c == null || start == null || end == null) continue;
            days.computeIfAbsent(s.dayNumber(), k -> new ArrayList<>()).add(new Visit(s, c, start, end));
        }
        List<Violation> out = new ArrayList<>();
        wants(conditions, days, startDate, facts, out);
        cityDays(conditions, days, cityNames, out);
        for (int d = 1; d <= dayCount; d++) {
            List<Visit> day = days.getOrDefault(d, List.of());
            LocalDate date = startDate.plusDays(d - 1);
            boolean first = d == 1;
            boolean last = d == dayCount;
            boolean lateArrival = first && conditions.arrival() != null && conditions.arrival().getHour() >= 18;
            if (activities(day).isEmpty() && !lateArrival) {
                out.add(new Violation("EMPTY_DAY", Severity.FATAL, d, d + "일차에 일정이 없음", null));
                continue;
            }
            if (activities(day).isEmpty()) continue;
            if (!last && !day.get(day.size() - 1).stayOrHub()) {
                out.add(new Violation("NO_LODGING", Severity.FATAL, d, d + "일차 밤 숙소가 없음", null));
            }
            meals(day, d, first, last, conditions, out);
            pace(day, d, first, last, conditions, out);
            openings(day, d, date, facts, out);
            movement(day, d, conditions, out);
            if (last && conditions.departure() != null) departure(day, d, conditions, out);
        }
        duplicates(days, facts, cityNames, out);
        avoid(conditions, days, out);
        return out;
    }

    private record Visit(StepData step, PlaceCandidate place, int start, int end) {
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
            return Objects.toString(place.name(), "");
        }
    }

    private static List<Visit> activities(List<Visit> day) {
        return day.stream().filter(v -> !v.stayOrHub()).toList();
    }

    private void wants(TripConditions conditions, Map<Integer, List<Visit>> days, LocalDate startDate,
                       Map<Long, PlaceFactsReader.PlaceFact> facts, List<Violation> out) {
        for (TripConditions.Want want : conditions.wants()) {
            List<Visit> hits = new ArrayList<>();
            for (Map.Entry<Integer, List<Visit>> e : days.entrySet()) {
                for (Visit v : activities(e.getValue())) {
                    if (matches(want, v) || partOf(want, facts.get(v.place().placeId()))) hits.add(v);
                }
            }
            // 같은 장소를 낮에도 지나가면 시각 조건을 지킨 방문을 찾는다.
            Visit hit = hits.stream().filter(v -> timingMet(want, v, startDate)).findFirst()
                    .orElse(hits.isEmpty() ? null : hits.get(0));
            if (hit == null) {
                out.add(new Violation("WANT_MISSING", Severity.FATAL, 0, "요청한 '" + want.text() + "'이(가) 없음",
                        "'" + want.text() + "'을(를) 넣을 날짜와 시간대를 정한다"));
                continue;
            }
            String timing = want.timing();
            if (timing == null) continue;
            LocalDate date = startDate.plusDays(hit.step().dayNumber() - 1);
            double[] at = CandidateGeo.coordsOf(hit.place());
            if (at == null) continue;
            int sunrise = SolarClock.sunrise(date, at[0], at[1]);
            int sunset = SolarClock.sunset(date, at[0], at[1]);
            boolean sunriseWant = "sunrise".equals(timing) || want.text().contains("일출");
            if (sunriseWant && hit.start() > sunrise + SUNRISE_SLACK) {
                out.add(new Violation("WANT_TIME", Severity.FATAL, hit.step().dayNumber(),
                        "'" + want.text() + "'을(를) 일출(" + ScheduleTimes.formatMinutes(sunrise) + ")이 아닌 "
                                + hit.step().startTime() + "에 감",
                        "전날 밤을 그 근처에서 묵고 일출 전에 도착한다"));
            } else if ("evening".equals(timing) && hit.start() < sunset - EVENING_EARLY) {
                out.add(new Violation("WANT_TIME", Severity.FATAL, hit.step().dayNumber(),
                        "'" + want.text() + "'을(를) 해 지기 전 " + hit.step().startTime() + "에 감", "해 진 뒤로 옮긴다"));
            }
        }
    }

    private static boolean matches(TripConditions.Want want, Visit v) {
        if (want.food() != null) return v.eatery() && serves(want.food(), want.satisfy(), v.place());
        if (matches(want.text(), v)) return true;
        for (String s : want.satisfy() == null ? List.<String>of() : want.satisfy()) {
            if (matches(s, v)) return true;
        }
        return false;
    }

    /** 요청한 곳 안의 장소(엑스포과학공원의 한빛탑)에 갔으면 그곳에 간 것이다. */
    private static boolean partOf(TripConditions.Want want, PlaceFactsReader.PlaceFact fact) {
        if (want.food() != null || fact == null || fact.partOf() == null || fact.partOf().isBlank()) return false;
        String parent = key(fact.partOf());
        return parent.length() >= 2 && (key(want.text()).contains(parent) || parent.contains(key(want.text())));
    }

    private static boolean timingMet(TripConditions.Want want, Visit v, LocalDate startDate) {
        if (want.timing() == null) return true;
        double[] at = CandidateGeo.coordsOf(v.place());
        if (at == null) return true;
        LocalDate date = startDate.plusDays(v.step().dayNumber() - 1);
        if ("sunrise".equals(want.timing()) || want.text().contains("일출")) {
            return v.start() <= SolarClock.sunrise(date, at[0], at[1]) + SUNRISE_SLACK;
        }
        return !"evening".equals(want.timing()) || v.start() >= SolarClock.sunset(date, at[0], at[1]) - EVENING_EARLY;
    }

    /** 장소 이름이나 식당 메뉴에 요청한 말이 들어 있는지. 일출·야경 같은 시간 말은 떼고 본다. */
    static boolean matches(String wantText, Visit v) {
        // "성산일출봉 일출"의 뒤쪽 '일출'만 시각 말이다. 이름 속 '일출'(일출봉)은 남긴다.
        String cleaned = java.util.Arrays.stream(wantText.trim().split("\\s+"))
                .filter(w -> !TIME_WORDS.contains(w)).collect(java.util.stream.Collectors.joining(" "));
        String want = key(cleaned);
        if (want.isEmpty()) return false;
        String name = key(BRACKETS.matcher(v.name()).replaceAll(""));
        if (name.contains(want) || (want.length() >= 3 && want.contains(name) && name.length() >= 3)) return true;
        // "고소동 벽화마을"과 "고소동 천사벽화마을"처럼 낱말이 모두 이름에 들어 있으면 같은 곳이다.
        List<String> words = java.util.Arrays.stream(cleaned.trim().split("\\s+")).map(ItineraryVerifier::key)
                .filter(w -> w.length() >= 2).toList();
        if (words.size() >= 2 && words.stream().allMatch(name::contains)) return true;
        // 앞에 붙은 동네 이름은 장소 이름에 없을 수 있다("고소동 벽화마을"과 "여수천사벽화마을").
        List<String> named = words.stream().filter(w -> !LOCALITY.matcher(w).matches()).toList();
        if (words.size() >= 2 && named.size() < words.size() && !named.isEmpty()
                && String.join("", named).length() >= 3 && named.stream().allMatch(name::contains)) return true;
        return v.meal() && serves(cleaned, v);
    }

    /**
     * 식당 이름·메뉴·음식 종류에 그 음식이 있는지. 일정에 붙은 이름표나 모델이 적은 식당 이름은 보지 않는다. 피순대를
     * 찾아 넣은 칸에 통닭집이 들어가도 이름표는 피순대다.
     */
    private static boolean serves(String food, Visit v) {
        return serves(food, v.place());
    }

    /**
     * 메뉴로 확인되거나, 메뉴 정보가 없는 식당이 그 음식으로 이름난 곳(모델이 적은 식당)과 이름이 같으면 요청 음식을 낸다고
     * 본다. 메뉴가 있는데 그 음식이 없으면 이름이 같아도 아니다.
     */
    static boolean serves(String food, List<String> named, PlaceCandidate c) {
        if (serves(food, c)) return true;
        if (named == null || c.attributes() != null && c.attributes().official() != null
                && !c.attributes().official().isEmpty()) return false;
        String bare = DraftPlaceResolver.bareKey(c.name());
        return named.stream().map(DraftPlaceResolver::bareKey).anyMatch(n -> n.length() >= 2
                && (bare.equals(n) || bare.startsWith(n) && bare.length() - n.length() <= BRANCH_SUFFIX_CHARS));
    }

    static boolean serves(String food, PlaceCandidate c) {
        String[] words = Objects.toString(food, "").trim().split("\\s+");
        // "모슬포 고등어회"처럼 앞에 붙은 지명은 식당 이름에 없어도 된다. 음식 이름은 마지막 낱말이다.
        return servesDish(key(food), c) || words.length > 1 && servesDish(key(words[words.length - 1]), c);
    }

    private static boolean servesDish(String dish, PlaceCandidate c) {
        if (dish.length() < 2) return false;
        if (GENERIC_DISH.contains(dish)) return false;
        String menu = c.attributes() == null || c.attributes().official() == null ? ""
                : String.join(" ", c.attributes().official().values());
        String text = key(String.join(" ", Objects.toString(c.name(), ""), Objects.toString(c.cuisine(), ""),
                c.tags() == null ? "" : String.join(" ", c.tags()), menu));
        return text.contains(dish);
    }

    private void cityDays(TripConditions conditions, Map<Integer, List<Visit>> days, List<String> cityNames,
                          List<Violation> out) {
        if (conditions.cityDays() == null || conditions.cityDays().isEmpty()) return;
        List<Destination> cities = cityNames.stream().map(DomesticCities::find)
                .flatMap(java.util.Optional::stream).toList();
        if (cities.size() < 2) return;
        Map<String, Integer> actual = new HashMap<>();
        for (List<Visit> day : days.values()) {
            Map<String, Integer> votes = new HashMap<>();
            for (Visit v : activities(day)) {
                double[] at = CandidateGeo.coordsOf(v.place());
                if (at == null) continue;
                Destination near = cities.stream().min(java.util.Comparator.comparingDouble(
                        c -> GeoUtils.haversine(at, new double[]{c.lat(), c.lng()}))).orElse(null);
                if (near != null) votes.merge(near.nameKo(), 1, Integer::sum);
            }
            votes.entrySet().stream().max(Map.Entry.comparingByValue())
                    .ifPresent(e -> actual.merge(e.getKey(), 1, Integer::sum));
        }
        for (Map.Entry<String, Integer> want : conditions.cityDays().entrySet()) {
            int got = actual.getOrDefault(want.getKey(), 0);
            if (got < want.getValue()) {
                out.add(new Violation("CITY_DAYS", Severity.FATAL, 0,
                        want.getKey() + " " + want.getValue() + "일 요청에 실제 " + got + "일",
                        "도시 간 이동 날짜와 귀가 시각을 다시 맞춘다"));
            }
        }
    }

    private void meals(List<Visit> day, int d, boolean first, boolean last, TripConditions conditions,
                       List<Violation> out) {
        List<Visit> acts = activities(day);
        int startOfDay = acts.get(0).start();
        int endOfDay = acts.get(acts.size() - 1).end();
        List<Visit> meals = acts.stream().filter(Visit::meal).toList();
        boolean lunch = meals.stream().anyMatch(m -> m.start() >= 11 * 60 && m.start() <= 14 * 60 + 30);
        boolean dinner = acts.stream().anyMatch(m -> (m.meal() || FOOD_NIGHT.matcher(m.name()).find())
                && m.start() >= 17 * 60 && m.start() <= 20 * 60 + 30);
        if (!lunch && startOfDay <= 12 * 60 && endOfDay >= 14 * 60) {
            out.add(new Violation("NO_LUNCH", last ? Severity.WARN : Severity.FATAL, d, d + "일차 점심이 없음", null));
        }
        if (!dinner && !last && !(first && startOfDay >= 18 * 60)) {
            out.add(new Violation("NO_DINNER", Severity.FATAL, d, d + "일차 저녁이 없음", null));
        }
        for (int i = 1; i < meals.size(); i++) {
            if (meals.get(i).start() - meals.get(i - 1).end() < MEAL_GAP) {
                out.add(new Violation("MEALS_BACK_TO_BACK", Severity.FATAL, d,
                        d + "일차 " + meals.get(i - 1).name() + " 뒤 바로 " + meals.get(i).name(), null));
            }
        }
    }

    /**
     * 페이스가 정한 하루 관광 수에 못 미치거나, 여유롭게가 아닌데 활동 사이가 길게 비면 알린다. 도착일·출발일은 반나절이라
     * 관광 수를 세지 않는다.
     */
    private void pace(List<Visit> day, int d, boolean first, boolean last, TripConditions conditions,
                      List<Violation> out) {
        if (conditions.pace() == null) return;
        List<Visit> acts = activities(day);
        long sights = acts.stream().filter(Visit::sight).count();
        int[] range = ScheduleTuning.sightsPerDay(conditions.pace());
        if (!first && !last && sights < range[0]) {
            out.add(new Violation("PACE_SHORT", Severity.WARN, d, d + "일차 관광 " + sights + "곳(페이스 기준 "
                    + range[0] + "~" + range[1] + "곳)", "그날 권역의 장소를 더 넣는다"));
        }
        if ("relaxed".equals(conditions.pace())) return;
        for (int i = 1; i < acts.size(); i++) {
            Integer leg = acts.get(i).step().transportationDuration();
            int gap = acts.get(i).start() - acts.get(i - 1).end() - (leg == null ? 0 : leg);
            if (gap >= LONG_GAP) {
                out.add(new Violation("LONG_GAP", Severity.WARN, d, d + "일차 " + acts.get(i - 1).step().endTime()
                        + "~" + acts.get(i).step().startTime() + " " + gap + "분 빔", "그 사이에 근처 장소를 넣는다"));
            }
        }
    }

    private void openings(List<Visit> day, int d, LocalDate date, Map<Long, PlaceFactsReader.PlaceFact> facts,
                          List<Violation> out) {
        DayOfWeek dow = date.getDayOfWeek();
        for (Visit v : activities(day)) {
            PlaceFactsReader.PlaceFact f = facts.get(v.place().placeId());
            if (f != null && Boolean.FALSE.equals(f.reachable())) {
                out.add(new Violation("UNREACHABLE", Severity.FATAL, d, v.name() + "은(는) 일반 관광객이 갈 수 없음", null));
            }
            if (KoreanHolidays.museumsClosed(date) && DayBuilder.isMuseum(v.place())) {
                out.add(new Violation("CLOSED", Severity.FATAL, d, v.name() + " 명절 휴관", null));
                continue;
            }
            String hours = DayTiming.openingHoursOf(v.place());
            if (OpeningHoursText.isClosedOnDay(hours, dow)) {
                out.add(new Violation("CLOSED", Severity.FATAL, d, v.name() + " 휴무일", null));
            } else if (!OpeningHoursText.isOpenDuring(hours, dow, v.start(), v.end())) {
                out.add(new Violation("CLOSED", Severity.FATAL, d, v.name() + " 영업시간 밖(" + v.step().startTime() + ")",
                        null));
            }
        }
    }

    private void movement(List<Visit> day, int d, TripConditions conditions, List<Violation> out) {
        int total = 0;
        for (Visit v : day) {
            Integer leg = v.step().transportationDuration();
            String mode = Objects.toString(v.step().transportationMode(), "");
            if (leg == null || List.of("TRAIN", "FLIGHT").contains(mode)) continue;
            total += leg;
            if (leg > LONG_LEG) {
                out.add(new Violation("LONG_LEG", Severity.WARN, d, v.name() + "까지 " + mode + " " + leg + "분", null));
            }
        }
        boolean transit = !"car".equals(conditions.transport());
        int limit = conditions.lowMobility() ? LOW_MOBILITY_DAILY_TRANSIT : DAILY_TRANSIT;
        if (transit && total > limit) {
            out.add(new Violation("DAILY_TRANSIT", Severity.FATAL, d, d + "일차 이동만 " + total + "분",
                    "숙소를 그날 동선 가까이 두거나 권역을 하나로 줄인다"));
        }
        if (conditions.lowMobility()) {
            for (Visit v : activities(day)) {
                if (STRENUOUS.matcher(Objects.toString(v.place().category(), "") + " " + v.name()).find()
                        && v.end() - v.start() >= 90) {
                    out.add(new Violation("STRENUOUS", Severity.FATAL, d, "걷기 힘든 동행에게 " + v.name() + " "
                            + (v.end() - v.start()) + "분", null));
                }
            }
        }
    }

    private void departure(List<Visit> day, int d, TripConditions conditions, List<Violation> out) {
        Visit end = day.get(day.size() - 1);
        int deadline = conditions.departure().getHour() * 60 + conditions.departure().getMinute();
        boolean airport = end.stayOrHub() && end.name().contains("공항");
        int margin = !end.stayOrHub() ? 0 : airport ? AIRPORT_MARGIN : STATION_MARGIN;
        if (end.start() > deadline - margin) {
            out.add(new Violation("DEPARTURE", Severity.FATAL, d, "출발 " + conditions.departure() + "인데 "
                    + end.name() + " " + end.step().startTime() + " 도착", null));
        }
    }

    private void duplicates(Map<Integer, List<Visit>> days, Map<Long, PlaceFactsReader.PlaceFact> facts,
                            List<String> cityNames, List<Violation> out) {
        Map<String, List<Integer>> seen = new HashMap<>();
        for (Map.Entry<Integer, List<Visit>> e : days.entrySet()) {
            for (Visit v : activities(e.getValue())) {
                if (v.meal() || CandidatePolicy.roleOf(v.place()) == CandidatePolicy.Role.CAFE) continue;
                PlaceFactsReader.PlaceFact f = facts.get(v.place().placeId());
                String name = f != null && f.partOf() != null && !f.partOf().isBlank() ? f.partOf() : v.name();
                String k = DraftPlaceResolver.queryKey(BRACKETS.matcher(name).replaceAll(" "), cityNames);
                List<Integer> where = seen.computeIfAbsent(k, x -> new ArrayList<>());
                if (!where.isEmpty()) {
                    boolean sameDay = where.contains(e.getKey());
                    out.add(new Violation("DUPLICATE", sameDay ? Severity.FATAL : Severity.WARN, e.getKey(),
                            v.name() + "은(는) " + where.get(0) + "일차에 간 곳과 같음", null));
                }
                where.add(e.getKey());
            }
        }
    }

    private void avoid(TripConditions conditions, Map<Integer, List<Visit>> days, List<Violation> out) {
        if (conditions.avoid() == null || conditions.avoid().isEmpty()) return;
        PlaceRestrictions restrictions = PlaceRestrictions.of(conditions.avoid());
        for (Map.Entry<Integer, List<Visit>> e : days.entrySet()) {
            for (Visit v : activities(e.getValue())) {
                var why = restrictions.violatedBy(v.place());
                if (why != null) {
                    out.add(new Violation("AVOID", Severity.FATAL, e.getKey(), v.name() + ": 피할 " + why, null));
                }
            }
        }
    }

    private static String key(String s) {
        return Objects.toString(s, "").toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]", "");
    }
}
