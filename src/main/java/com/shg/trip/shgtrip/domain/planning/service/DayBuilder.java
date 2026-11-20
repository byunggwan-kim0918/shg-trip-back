package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;

/**
 * 하루를 시간표 한 번으로 짓는다. 장소마다 도착 시각을 실제 이동으로 계산하고, 그 시각에 갈 수 있는지
 * (영업·휴관일·입장 마감·해 지기 전 야외·숙소 도착 기한)를 그 자리에서 판단한다. 갈 수 없으면 그날 예비 장소로 바꾸고,
 * 바꿀 곳이 없으면 뺀다. 고르는 쪽과 확정하는 쪽이 다른 시간 모델을 쓰면 한쪽이 넣은 장소를 다른 쪽이 뺀다.
 */
@lombok.extern.slf4j.Slf4j
final class DayBuilder {

    static final int LUNCH_EARLIEST = 11 * 60;
    static final int DINNER_EARLIEST = 17 * 60 + 30;
    static final int LUNCH_LATEST_START = 13 * 60 + 30;
    /** 점심이 이보다 더 늦어지면 점심이 아니라 저녁 앞의 한 끼가 되어 끼니가 겹친다. */
    static final int LUNCH_GRACE = 60;
    static final int DINNER_LATEST_START = 19 * 60 + 30;
    /** 야경은 해 진 직후부터 볼 만하다. */
    static final int NIGHT_AFTER_SUNSET = -10;
    /** 해 진 뒤 이만큼 지나도록 야외에 있으면 어둠 속이다. */
    static final int OUTDOOR_AFTER_SUNSET = 15;
    /** 문 열기를 이만큼까지는 기다린다. 더 길면 다른 장소로 바꾼다. */
    static final int MAX_OPENING_WAIT = 40;
    /** 끼니·저녁 일정 앞 빈 시간이 이보다 길면 예비 장소를 넣는다. */
    static final int GAP_TO_FILL = 75;
    /** 입장 마감 직전이라도 이만큼은 볼 수 있어야 넣는다. */
    static final int MIN_VISIT = 40;
    static final int MEAL_MINUTES = 70;
    static final int CAFE_MINUTES = 50;
    static final int NIGHT_MINUTES = 60;
    static final int LODGING_MINUTES = 30;
    static final int HUB_MINUTES = 30;
    /** 하루 끝까지 이만큼 넘게 남으면 예비 장소로 채운다. */
    static final int FILL_TAIL = 90;
    static final int SUNRISE_LEAD = 30;
    static final int SUNRISE_MINUTES = 60;
    /** 부모님·어린아이와 걷는 한 구간의 상한. 더 멀면 대중교통·택시다. */
    static final double SLOW_WALK_KM = 0.8;
    /** 같은 장소로 보는 거리. 이름이 서로를 담거나 앞 두 글자가 같을 때만 쓴다. */
    static final double SAME_SPOT_KM = 0.3;
    /** 이름과 상관없이 같은 곳으로 보는 거리(용두산공원과 부산타워, 이월드와 83타워). */
    static final double SAME_GROUNDS_KM = 0.12;
    /** 대중교통 여행에서 이 거리까지는 택시로 본다. 버스를 기다리면 걷는 것보다 늦는 거리다. */
    static final double TAXI_KM = 3.0;
    /** 시내버스 속도로 보는 거리. 그보다 먼 구간은 급행·간선 속도로 본다. */
    static final double BUS_CITY_KM = 10.0;
    /** 영업시간을 모르는 박물관·궁의 관람 시간. 겨울(11~2월)은 한 시간 일찍 닫고, 입장은 닫기 한 시간 전까지다. */
    static final int OPENS = 9 * 60;
    static final int CLOSES = 18 * 60;
    static final int WINTER_CLOSES = 17 * 60;
    static final int LAST_ENTRY = 60;
    /** 요청 장소를 해 지기 이만큼 전부터는 야경 의도로 보고 해 진 뒤까지 둔다. */
    static final int PROTECTED_DUSK = 60;
    /** 여유로운 여행에서 한 곳에 머무는 상한. */
    static final int MAX_STAY = 180;

    private static final Map<String, Integer> SLOT_ORDER = Map.of(
            "morning", 0, "lunch", 1, "afternoon", 2, "dinner", 3, "evening", 4);

    private DayBuilder() {
    }

    /**
     * 그날 들를 곳.
     *
     * @param kind    초안의 종류(sight·meal·cafe·night…)
     * @param protect 요청한 장소. 시간이 모자라면 요청하지 않은 장소를 먼저 뺀다
     */
    record Stop(PlaceCandidate place, String kind, String slot, List<PlaceCandidate> alternatives, boolean protect) {

        Stop(PlaceCandidate place, String kind, String slot, List<PlaceCandidate> alternatives) {
            this(place, kind, slot, alternatives, false);
        }

        boolean meal() {
            return "meal".equals(kind);
        }

        boolean evening() {
            return "evening".equals(slot) || "night".equals(kind);
        }

        boolean sunrise() {
            return "sunrise".equals(kind);
        }
    }

    /**
     * @param startMin   첫 장소로 출발하는 시각(분)
     * @param endMin     마지막 활동이 끝나야 하는 시각. 출발일은 출발 허브로 떠나야 하는 시각이다
     * @param lodgingBy  숙소에 들어가야 하는 시각. 숙소가 없는 날은 무시한다
     * @param startAt    출발 위치(전날 숙소·도착 허브). 스텝으로 넣지 않는다
     * @param arrivalHub 도착 허브. 있으면 첫 스텝으로 넣는다
     * @param endAt      그날 밤 숙소 또는 출발 허브. 마지막 스텝으로 넣는다
     * @param hardEnd    endMin을 끼니도 넘을 수 없는 날(출발일)
     * @param firstLeg   첫 장소까지의 이동을 정해 둔 경우(도시 간 이동). null이면 거리로 계산한다
     * @param slowWalk   걷기 힘든 동행. 걷는 구간을 짧게 잡는다
     * @param earlier    앞선 날에 넣은 장소. 같은 곳을 다시 넣지 않는다
     * @param maxSights  빈 시간을 채워 넣을 수 있는 하루 관광 수 상한. 페이스가 정한다
     * @param stayFactor 체류 시간 배수. 여유롭게는 한 곳에 오래, 빡빡하게는 짧게 머문다
     * @param lastLeg    숙소까지의 이동을 정해 둔 경우(저녁에 하는 도시 간 이동). null이면 거리로 계산한다
     * @param country    여행 나라. 명절 휴관과 입국 시간을 정한다
     */
    record Frame(int dayNumber, LocalDate date, int startMin, int endMin, int lodgingBy, int sunset, int sunrise,
                 PlaceCandidate startAt, PlaceCandidate arrivalHub, PlaceCandidate endAt, boolean endIsHub,
                 boolean hardEnd, TransportLeg firstLeg, boolean slowWalk, String transportPref,
                 List<Stop> spares, List<PlaceCandidate> earlier, int maxSights, double stayFactor,
                 TransportLeg lastLeg, String country) {

        Frame withLodging(PlaceCandidate newStartAt, PlaceCandidate newEndAt) {
            return new Frame(dayNumber, date, startMin, endMin, lodgingBy, sunset, sunrise, newStartAt, arrivalHub,
                    newEndAt, endIsHub, hardEnd, firstLeg, slowWalk, transportPref, spares, earlier, maxSights,
                    stayFactor, lastLeg, country);
        }

        Frame withSpares(List<Stop> newSpares, List<PlaceCandidate> newEarlier) {
            return new Frame(dayNumber, date, startMin, endMin, lodgingBy, sunset, sunrise, startAt, arrivalHub,
                    endAt, endIsHub, hardEnd, firstLeg, slowWalk, transportPref, newSpares, newEarlier, maxSights,
                    stayFactor, lastLeg, country);
        }

        /** 도착 허브에서 빠져나오는 시간. 국제선은 입국 심사와 짐 찾기가 더 걸린다. */
        int arrivalHubMinutes() {
            if (arrivalHub == null) return 0;
            return TripCalendar.isKorea(country) || !TripHubs.isAirport(arrivalHub) ? HUB_MINUTES
                    : ScheduleTuning.ARRIVAL_HUB_MINUTES;
        }
    }

    /** @param placed 그날 넣은 장소(숙소·허브 제외). stops는 같은 순서의 정류지다 */
    record Built(List<StepData> steps, List<String> dropped, List<PlaceCandidate> placed, List<Stop> stops) {
    }

    /** 요청 장소를 살리려고 다른 장소를 빼며 다시 짓는 횟수 상한. */
    private static final int MAX_REBUILDS = 8;

    static Built build(Frame f, List<Stop> stops) {
        return build(f, stops, false);
    }

    /** @param keepOrder 주어진 순서대로 돈다. 다 지은 일정을 고친 뒤 시각만 다시 맞출 때 쓴다 */
    static Built build(Frame f, List<Stop> stops, boolean keepOrder) {
        stops = lunchAsDinner(f, stops);
        Set<Long> excluded = new HashSet<>();
        List<String> finalDropped = new ArrayList<>();
        Attempt best = null;
        List<String> bestDropped = null;
        for (int round = 0; ; round++) {
            List<String> dropped = new ArrayList<>(finalDropped);
            Attempt a = attempt(f, stops, excluded, dropped, keepOrder);
            // 자리를 양보해도 요청 장소가 들어가지 않으면(마지막 날 저녁에 둔 야경) 양보 전 일정을 쓴다.
            if (a.lateBy <= 0 && (best == null || a.lostProtected.size() < best.lostProtected.size())) {
                best = a;
                bestDropped = dropped;
            }
            Stop victim = null;
            String reason = null;
            if (a.lateBy > 0 && !a.placed.isEmpty()) {
                victim = lastUnprotected(a.placed);
                if (victim == null) victim = a.placed.get(a.placed.size() - 1);
                reason = "숙소 도착 늦음";
            } else if (!a.lostProtected.isEmpty() && round < MAX_REBUILDS) {
                victim = lastUnprotected(a.placed.subList(0, a.firstLossAt));
                reason = "요청 장소에 자리 양보";
            }
            if (victim == null || round >= MAX_REBUILDS + 2) {
                if (a.lateBy <= 0 && best != null) {
                    a = best;
                    dropped = bestDropped;
                }
                return new Built(a.steps, dropped, a.placed.stream().map(Stop::place).toList(), a.placed);
            }
            excluded.add(victim.place().placeId());
            finalDropped.add(victim.place().name() + "(" + reason + ")");
        }
    }

    /**
     * 점심 뒤에 시작하는 하루(오후 도착)는 점심 칸 끼니를 저녁으로 옮긴다. 저녁이 이미 있으면 요청한 끼니를 남긴다.
     */
    private static List<Stop> lunchAsDinner(Frame f, List<Stop> stops) {
        int begin = f.startMin() + f.arrivalHubMinutes();
        if (begin <= LUNCH_LATEST_START) return stops;
        List<Stop> out = new ArrayList<>();
        for (Stop s : stops) {
            out.add(s.meal() && "lunch".equals(s.slot())
                    ? new Stop(s.place(), s.kind(), "dinner", s.alternatives(), s.protect()) : s);
        }
        boolean askedDinner = out.stream().anyMatch(s -> s.meal() && "dinner".equals(s.slot()) && s.protect());
        Set<String> seen = new HashSet<>();
        out.removeIf(s -> s.meal() && "dinner".equals(s.slot())
                && (askedDinner && !s.protect() || !seen.add("dinner")));
        return out;
    }

    private static Stop lastUnprotected(List<Stop> placed) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Stop s = placed.get(i);
            if (!s.protect() && !s.meal()) return s;
        }
        return null;
    }

    /** @param lostProtected 시간이 모자라 빠진 요청 장소 */
    private record Attempt(List<StepData> steps, List<Stop> placed, int lateBy, List<Stop> lostProtected,
                           int firstLossAt) {
    }

    private static Attempt attempt(Frame f, List<Stop> stops, Set<Long> excluded, List<String> dropped,
                                   boolean keepOrder) {
        DayState day = new DayState(new SelectionOutput.DayPlan(f.dayNumber(), null, List.of(), null, null));
        DayOfWeek dow = f.date().getDayOfWeek();
        LinkedList<Stop> spares = new LinkedList<>(f.spares() == null ? List.of() : f.spares());
        spares.removeIf(s -> excluded.contains(s.place().placeId()));
        List<Stop> queue = keepOrder ? new ArrayList<>(stops)
                : ordered(stops, f.startAt() != null ? f.startAt() : f.arrivalHub());
        queue.removeIf(s -> excluded.contains(s.place().placeId()));
        List<PlaceCandidate> seen = new ArrayList<>(f.earlier() == null ? List.of() : f.earlier());
        Set<Long> used = new HashSet<>();
        seen.forEach(c -> used.add(c.placeId()));

        List<StepData> steps = new ArrayList<>();
        List<Stop> placed = new ArrayList<>();
        List<Stop> lostProtected = new ArrayList<>();
        int firstLossAt = -1;
        int t = f.startMin();
        PlaceCandidate prev = f.startAt();
        if (f.arrivalHub() != null) {
            steps.add(DayTiming.buildStep(day, f.arrivalHub(), t, t + f.arrivalHubMinutes(), null, List.of()));
            t += f.arrivalHubMinutes();
            prev = f.arrivalHub();
        }
        for (int i = 0; i < queue.size(); i++) {
            Stop stop = queue.get(i);
            TransportLeg override = placed.isEmpty() && f.arrivalHub() == null ? f.firstLeg() : null;
            if (stop.place().placeId() != null && used.contains(stop.place().placeId())) {
                // 앞 끼니의 대안이나 앞날에 쓴 식당이 다시 끼니로 오면 대안으로 바꾼다. 그냥 넘기면 끼니가 기록 없이 빠진다.
                Stop alt = stop.meal() ? mealAlternative(f, dow, prev, stop, t, used, excluded, override) : null;
                if (alt == null) {
                    if (stop.meal()) {
                        dropped.add(stop.place().name() + "(이미 쓴 식당)");
                        if (stop.protect()) {
                            if (lostProtected.isEmpty()) firstLossAt = placed.size();
                            lostProtected.add(stop);
                        }
                    }
                    continue;
                }
                queue.set(i, alt);
                stop = alt;
            }
            if (stop.place().placeId() != null) used.add(stop.place().placeId());
            if (!stop.meal() && !stop.protect() && sameSpotAsAny(stop.place(), seen)) {
                dropped.add(stop.place().name() + "(이미 간 곳)");
                continue;
            }
            Slot slot = fit(f, dow, prev, stop, t, override);
            if (slot != null && (stop.meal() || stop.evening()) && slot.start() - slot.arrive() > GAP_TO_FILL
                    && sights(placed) + sights(queue.subList(i, queue.size())) < f.maxSights()) {
                Stop filler = pickSpare(f, dow, prev, t, spares, used, seen, slot.start(), override);
                if (filler != null) {
                    queue.add(i, filler);
                    used.remove(stop.place().placeId());
                    i--;
                    continue;
                }
            }
            if (slot == null) {
                String why = whyNot(f, dow, prev, stop, t);
                log.debug("day={} 못 넣음 {} at {} ({})", f.dayNumber(), stop.place().name(),
                        ScheduleTimes.formatMinutes(t), why);
                dropped.add(stop.place().name() + "(" + why + ")");
                Stop sub = stop.meal() ? mealAlternative(f, dow, prev, stop, t, used, excluded, override)
                        : pickSpare(f, dow, prev, t, spares, used, seen, Integer.MAX_VALUE, override);
                // 요청한 음식의 대안은 그 음식을 내는 곳만 고르므로 대안이 들어가면 요청을 지킨 것이다.
                boolean kept = stop.meal() && sub != null;
                if (stop.protect() && !kept && !"휴무일".equals(why) && !"명절 휴관".equals(why)) {
                    if (lostProtected.isEmpty()) firstLossAt = placed.size();
                    lostProtected.add(stop);
                }
                if (sub != null) {
                    queue.add(i + 1, sub);
                }
                continue;
            }
            if (!stop.meal() && !stop.protect() && pushesMealOut(queue, i, slot)) {
                dropped.add(stop.place().name() + "(끼니 시간 침범)");
                continue;
            }
            if (slot.end() > f.endMin() && (f.hardEnd() || !(stop.meal() && slot.start() <= DINNER_EARLIEST + 60))) {
                dropped.add(stop.place().name() + "(하루 끝 시각 초과)");
                log.debug("day={} 끝 초과 {} {}~{} end={}", f.dayNumber(), stop.place().name(),
                        ScheduleTimes.formatMinutes(slot.start()), ScheduleTimes.formatMinutes(slot.end()),
                        ScheduleTimes.formatMinutes(f.endMin()));
                if (stop.protect()) {
                    if (lostProtected.isEmpty()) firstLossAt = placed.size();
                    lostProtected.add(stop);
                }
                continue;
            }
            log.debug("day={} 배치 {} {}~{} (도착 {})", f.dayNumber(), stop.place().name(),
                    ScheduleTimes.formatMinutes(slot.start()), ScheduleTimes.formatMinutes(slot.end()),
                    ScheduleTimes.formatMinutes(slot.arrive()));
            steps.add(DayTiming.buildStep(day, stop.place(), slot.start(), slot.end(), slot.leg(),
                    alternativesOf(stop)));
            placed.add(stop);
            seen.add(stop.place());
            t = slot.end();
            prev = stop.place();
        }

        // 마지막 장소 뒤로 하루 끝까지 시간이 남으면 예비 장소로 채운다. 출발일 오후가 통째로 비는 일정이 된다.
        while (f.endMin() - t >= FILL_TAIL && t <= DINNER_LATEST_START && sights(placed) < f.maxSights()) {
            Stop filler = pickSpare(f, dow, prev, t, spares, used, seen, f.endMin(), null);
            if (filler == null) break;
            Slot slot = fit(f, dow, prev, filler, t, null);
            if (slot == null || slot.end() > f.endMin()) break;
            used.add(filler.place().placeId());
            steps.add(DayTiming.buildStep(day, filler.place(), slot.start(), slot.end(), slot.leg(), List.of()));
            placed.add(filler);
            seen.add(filler.place());
            t = slot.end();
            prev = filler.place();
        }

        int lateBy = 0;
        if (f.endAt() != null) {
            TransportLeg leg = f.lastLeg() != null ? f.lastLeg()
                    : placed.isEmpty() && f.arrivalHub() == null && f.firstLeg() != null ? f.firstLeg()
                    : legOf(prev, f.endAt(), f.transportPref(), f.slowWalk());
            int arrive = t + (leg == null ? 0 : leg.durationMin());
            int limit = f.endIsHub() ? f.endMin() + TripHubs.HUB_TRAVEL_MINUTES : f.lodgingBy();
            lateBy = arrive - limit;
            int stay = f.endIsHub() ? HUB_MINUTES : LODGING_MINUTES;
            steps.add(DayTiming.buildStep(day, f.endAt(), arrive, arrive + stay, leg, List.of()));
        }
        // 떠나기 전에 쓸 시간이 없는 출발일은 전날 숙소에서 나서는 것으로 둔다. 스텝이 없는 날은 저장 관문이 거부한다.
        if (steps.isEmpty() && f.startAt() != null) {
            steps.add(DayTiming.buildStep(day, f.startAt(), f.startMin(), f.startMin() + LODGING_MINUTES, null,
                    List.of()));
        }
        return new Attempt(steps, placed, lateBy, lostProtected, Math.max(0, firstLossAt));
    }

    private record Slot(int arrive, int start, int end, TransportLeg leg) {
    }

    /**
     * 뒤에 점심·저녁이 남았는데 이 장소가 끼니 시간 끝을 넘겨 끝나면 이 장소를 뺀다. 관광을 다 넣고 끼니를 빼면
     * 점심 없이 오후를 보내거나 저녁 없이 숙소로 들어간다.
     */
    private static boolean pushesMealOut(List<Stop> queue, int index, Slot slot) {
        for (int j = index + 1; j < queue.size(); j++) {
            Stop next = queue.get(j);
            if (!next.meal()) continue;
            if ("lunch".equals(next.slot()) && slot.end() > LUNCH_LATEST_START) return true;
            if ("dinner".equals(next.slot()) && slot.end() > DINNER_LATEST_START) return true;
            return false;
        }
        return false;
    }

    /** 그 시각에 갈 수 있으면 도착·시작·끝 시각, 아니면 null. */
    private static Slot fit(Frame f, DayOfWeek dow, PlaceCandidate prev, Stop stop, int t, TransportLeg override) {
        PlaceCandidate c = stop.place();
        TransportLeg leg = override != null ? override : legOf(prev, c, f.transportPref(), f.slowWalk());
        int arrive = t + (leg == null ? 0 : leg.durationMin());
        int start = arrive;
        if (stop.sunrise()) {
            int s = Math.max(arrive, f.sunrise() - SUNRISE_LEAD);
            return s > f.sunrise() + SUNRISE_LEAD ? null : new Slot(arrive, s, s + SUNRISE_MINUTES, leg);
        }
        if (!stop.evening() && !stop.protect() && isBridge(c)) return null;
        if ("lunch".equals(stop.slot()) && stop.meal()) start = Math.max(start, LUNCH_EARLIEST);
        if ("lunch".equals(stop.slot()) && stop.meal() && start > LUNCH_LATEST_START + LUNCH_GRACE) return null;
        if ("dinner".equals(stop.slot()) && stop.meal()) start = Math.max(start, DINNER_EARLIEST);
        if (stop.evening() && !stop.meal()) start = Math.max(start, f.sunset() + NIGHT_AFTER_SUNSET);
        int duration = minutesOf(stop, f.stayFactor());

        if (TripCalendar.museumsClosed(f.country(), f.date()) && isMuseum(c)) {
            return null;
        }
        String hours = DayTiming.openingHoursOf(c);
        List<int[]> ranges = OpeningHoursText.rangesFor(hours, dow);
        if (ranges == null && isMuseumOrPalace(c)) {
            ranges = List.of(new int[]{OPENS, closesAt(f.date()) - LAST_ENTRY});
        }
        if (ranges != null) {
            if (ranges.isEmpty()) return null;
            int earliest = start;
            int[] open = ranges.stream().filter(r -> r[1] > earliest + Math.min(duration, MIN_VISIT))
                    .min(Comparator.comparingInt(r -> r[0])).orElse(null);
            if (open == null) return null;
            int s = Math.max(start, open[0]);
            if (s - start > MAX_OPENING_WAIT && !stop.meal()) return null;
            int e = Math.min(s + duration, open[1]);
            if (e - s < Math.min(duration, MIN_VISIT)) return null;
            return daylight(f, stop, new Slot(arrive, s, e, leg));
        }
        return daylight(f, stop, new Slot(arrive, start, start + duration, leg));
    }

    /** 야외 낮 명소는 해 진 뒤까지 머무르지 않는다. 시작이 이미 늦었으면 넣지 않는다. */
    private static Slot daylight(Frame f, Stop stop, Slot slot) {
        if (stop.evening() || stop.meal()) return slot;
        // 요청한 장소를 저녁에 두는 것은 대개 야경 의도다(월정교·돌산공원).
        if (stop.protect() && slot.start() >= f.sunset() - PROTECTED_DUSK) return slot;
        if (DayTiming.timePreference(stop.place()) != TimePreference.DAYTIME) return slot;
        int darkAt = f.sunset() + OUTDOOR_AFTER_SUNSET;
        if (slot.start() + MIN_VISIT > darkAt) return null;
        return slot.end() > darkAt ? new Slot(slot.arrive(), slot.start(), darkAt, slot.leg()) : slot;
    }

    private static String whyNot(Frame f, DayOfWeek dow, PlaceCandidate prev, Stop stop, int t) {
        PlaceCandidate c = stop.place();
        if (stop.sunrise()) return "일출 시각 지남";
        if (stop.meal() && "lunch".equals(stop.slot())) return "점심 시간 지남";
        if (TripCalendar.museumsClosed(f.country(), f.date()) && isMuseum(c)) {
            return "명절 휴관";
        }
        List<int[]> ranges = OpeningHoursText.rangesFor(DayTiming.openingHoursOf(c), dow);
        if (ranges != null && ranges.isEmpty()) return "휴무일";
        if (ranges != null) return "영업시간 밖";
        return "해 진 뒤 야외";
    }

    /** 끼니 식당이 그 시각에 맞지 않으면 함께 골라 둔 대안 식당 가운데 들어가는 곳. 없으면 끼니가 빠진다. */
    private static Stop mealAlternative(Frame f, DayOfWeek dow, PlaceCandidate prev, Stop stop, int t, Set<Long> used,
                                        Set<Long> excluded, TransportLeg override) {
        for (PlaceCandidate c : stop.alternatives() == null ? List.<PlaceCandidate>of() : stop.alternatives()) {
            if (c.placeId() != null && (used.contains(c.placeId()) || excluded.contains(c.placeId()))) continue;
            Stop alt = new Stop(c, stop.kind(), stop.slot(), List.of(), stop.protect());
            if (fit(f, dow, prev, alt, t, override) != null) return alt;
        }
        return null;
    }

    private static Stop pickSpare(Frame f, DayOfWeek dow, PlaceCandidate prev, int t, List<Stop> spares,
                                  Set<Long> used, List<PlaceCandidate> seen, int mustEndBy, TransportLeg override) {
        Stop best = null;
        double bestKm = Double.MAX_VALUE;
        for (Stop s : spares) {
            if (s.place().placeId() != null && used.contains(s.place().placeId())) continue;
            if (sameSpotAsAny(s.place(), seen)) continue;
            Slot slot = fit(f, dow, prev, s, t, override);
            if (slot == null || slot.end() > mustEndBy || slot.start() - slot.arrive() > MAX_OPENING_WAIT) continue;
            double km = distanceKm(prev, s.place());
            if (km < bestKm) {
                bestKm = km;
                best = s;
            }
        }
        if (best != null) spares.remove(best);
        return best;
    }

    /** 끼니 칸 순서를 지키고, 같은 칸 안에서는 가까운 곳부터 돈다. */
    static List<Stop> ordered(List<Stop> stops, PlaceCandidate from) {
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingInt(DayBuilder::slotRank));
        List<Stop> out = new ArrayList<>();
        PlaceCandidate cursor = from;
        int i = 0;
        while (i < sorted.size()) {
            int slot = slotRank(sorted.get(i));
            List<Stop> group = new ArrayList<>();
            while (i < sorted.size() && slotRank(sorted.get(i)) == slot) {
                group.add(sorted.get(i++));
            }
            while (!group.isEmpty()) {
                PlaceCandidate c = cursor;
                Stop next = c == null ? group.get(0)
                        : group.stream().min(Comparator.comparingDouble(s -> distanceKm(c, s.place()))).orElseThrow();
                group.remove(next);
                out.add(next);
                cursor = next.place();
            }
        }
        return out;
    }

    private static int slotRank(Stop s) {
        return s.sunrise() ? -1 : s.slot() == null ? 2 : SLOT_ORDER.getOrDefault(s.slot(), 2);
    }

    static int minutesOf(Stop stop, double stayFactor) {
        return switch (stop.kind() == null ? "" : stop.kind()) {
            case "meal" -> MEAL_MINUTES;
            case "cafe" -> CAFE_MINUTES;
            case "night" -> NIGHT_MINUTES;
            case "market", "shop" -> (int) Math.round(60 * stayFactor);
            default -> Math.max(45, Math.min(MAX_STAY,
                    (int) Math.round(Math.min(150, DayTiming.resolveVisitMinutes(stop.place())) * stayFactor)));
        };
    }

    /** 낮 관광 수. 페이스의 하루 관광 수는 저녁 일정(야경·야시장)을 따로 센다. */
    private static int sights(List<Stop> stops) {
        int n = 0;
        for (Stop s : stops) if (!s.meal() && !"cafe".equals(s.kind()) && !s.evening()) n++;
        return n;
    }

    private static List<AlternativeData> alternativesOf(Stop stop) {
        if (stop.alternatives() == null) return List.of();
        return stop.alternatives().stream().limit(2)
                .map(c -> new AlternativeData(c.name(), c.address(), c.category(), c.region(), c.country(), null,
                        CostEstimator.estimateCost(c), c.placeId(), c.nameKo()))
                .toList();
    }

    /**
     * 이동수단마다 걷는 한계를 따로 둔다. 도보 여행자의 먼 구간을 차 속도로 잡으면 하루 도보가 비현실적으로 길어진다.
     */
    static TransportLeg legOf(PlaceCandidate from, PlaceCandidate to, String transportPref, boolean slowWalk) {
        if (from == null || to == null) return null;
        double[] a = coordsOf(from);
        double[] b = coordsOf(to);
        if (a == null || b == null) return null;
        double km = GeoUtils.haversine(a, b) * GeoUtils.ROAD_DISTANCE_FACTOR;
        String pref = transportPref == null ? "any" : transportPref;
        double walkLimit = switch (pref) {
            case "walk" -> slowWalk ? SLOW_WALK_KM : 2.0;
            case "car" -> 0.8;
            default -> slowWalk ? SLOW_WALK_KM : 1.2;
        };
        BigDecimal distance = BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP);
        if (km <= walkLimit) {
            return new TransportLeg("WALK", Math.max(3, (int) Math.ceil(km * 15)), distance, BigDecimal.ZERO);
        }
        if ("car".equals(pref)) {
            double hours = km <= 20 ? km / 35 : 20.0 / 35 + (km - 20) / 70;
            return new TransportLeg("CAR", (int) Math.ceil(10 + hours * 60), distance,
                    BigDecimal.valueOf(Math.round(km * 300)));
        }
        if (km <= TAXI_KM) {
            return new TransportLeg("TAXI", (int) Math.ceil(5 + km / 25 * 60), distance,
                    BigDecimal.valueOf(4800 + Math.round(Math.max(0, km - 1.6) * 800)));
        }
        // 먼 구간은 정류장이 드문 급행·간선이라 시내 구간보다 빠르다.
        double hours = km <= BUS_CITY_KM ? km / 20 : BUS_CITY_KM / 20 + (km - BUS_CITY_KM) / 35;
        int minutes = (int) Math.ceil(12 + hours * 60);
        return new TransportLeg("BUS", minutes, distance, BigDecimal.valueOf(1500));
    }

    static int closesAt(LocalDate date) {
        int m = date.getMonthValue();
        return m >= 11 || m <= 2 ? WINTER_CLOSES : CLOSES;
    }

    static boolean isMuseumOrPalace(PlaceCandidate c) {
        String name = java.util.Objects.toString(c.displayName(), "");
        return isMuseum(c) || name.endsWith("궁") || name.contains("궁 (") || name.contains("고궁") || name.contains("향교")
                || name.contains("서원");
    }

    /** 다리는 야경으로만 본다. 낮에 다리를 관광지로 한 시간 넘게 두면 볼 것이 없다. */
    static boolean isBridge(PlaceCandidate c) {
        String name = java.util.Objects.toString(c.displayName(), "");
        String category = c.category() == null ? "" : c.category().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith("대교") || category.contains("bridge");
    }

    /** 이름만 다른 같은 장소(첨성대/경주 첨성대, 경포호/경포호(철새도래지)). */
    static boolean sameSpotAsAny(PlaceCandidate c, List<PlaceCandidate> seen) {
        String k = DraftPlaceResolver.key(c.displayName());
        for (PlaceCandidate s : seen) {
            if (c.placeId() != null && c.placeId().equals(s.placeId())) return true;
            if (distanceKm(c, s) > SAME_SPOT_KM) continue;
            if (CandidatePolicy.roleOf(s) == CandidatePolicy.Role.MEAL) continue;
            if (distanceKm(c, s) <= SAME_GROUNDS_KM) return true;
            String o = DraftPlaceResolver.key(s.displayName());
            if (k.isEmpty() || o.isEmpty()) continue;
            if (k.contains(o) || o.contains(k) || (k.length() >= 2 && o.length() >= 2 && k.substring(0, 2).equals(o.substring(0, 2)))) {
                return true;
            }
        }
        return false;
    }

    /** 명절 당일 휴관이 흔한 박물관·미술관·과학관·기념관. */
    static boolean isMuseum(PlaceCandidate c) {
        String category = c.category() == null ? "" : c.category().toLowerCase(java.util.Locale.ROOT);
        String name = java.util.Objects.toString(c.displayName(), "");
        return category.contains("museum") || category.contains("gallery")
                || name.contains("박물관") || name.contains("미술관") || name.contains("과학관") || name.contains("기념관");
    }

    static double distanceKm(PlaceCandidate a, PlaceCandidate b) {
        double[] x = coordsOf(a);
        double[] y = coordsOf(b);
        return x == null || y == null ? 0 : GeoUtils.haversine(x, y);
    }
}
