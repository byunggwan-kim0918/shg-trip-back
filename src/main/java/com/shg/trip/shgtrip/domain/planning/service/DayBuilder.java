package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.DraftItineraryPlanner.MissCause;
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

    static final String DEPARTURE_TOO_SOON = "출발 허브까지 시간 부족";
    static final String CLOSED_DAY = "휴무일";
    static final String HOLIDAY_CLOSED = "명절 휴관";
    static final String OUTSIDE_HOURS = "영업시간 밖";
    static final String LONG_OPENING_WAIT = "개점까지 오래 기다림";
    static final String LUNCH_PASSED = "점심 시간 지남";
    static final String SUNRISE_PASSED = "일출 시각 지남";
    static final String PAST_DAY_END = "하루 끝 시각 초과";
    static final String LATE_FOR_LODGING = "숙소 도착 늦음";
    /**
     * 덜 장소가 요청 장소뿐이면 숙소 도착을 이만큼까지 늦춰 요청 장소를 지킨다. 도시를 옮기는 날 숙소 기한(23:00)과 같은 폭이다.
     * 더 늦으면 요청 장소를 빼고 넣지 못한 까닭을 남긴다.
     */
    static final int LATE_LODGING_TOLERANCE_MINUTES = 60;
    static final String YIELDED = "요청 장소에 자리 양보";
    static final String AFTER_DARK = "해 진 뒤 야외";
    static final String NO_ROUTE = "갈 수 없는 구간";
    static final String NO_TRANSIT = "그 시각 대중교통 없음";
    static final String NO_COORDS = "위치 모름";

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
     * @param firstLeg   새 도시 쪽으로 넘어가는 구간을 정해 둔 경우(아침 도시 간 이동). null이면 거리로 계산한다
     * @param slowWalk   걷기 힘든 동행. 걷는 구간을 짧게 잡는다
     * @param earlier    앞선 날에 넣은 장소. 같은 곳을 다시 넣지 않는다
     * @param maxSights  빈 시간을 채워 넣을 수 있는 하루 관광 수 상한. 페이스가 정한다
     * @param stayFactor 체류 시간 배수. 여유롭게는 한 곳에 오래, 빡빡하게는 짧게 머문다
     * @param lastLeg    숙소까지의 이동을 정해 둔 경우(저녁에 하는 도시 간 이동). null이면 거리로 계산한다
     * @param country    여행 나라. 명절 휴관과 입국 시간을 정한다
     * @param hubDeadline 출발 허브에 닿아야 하는 시각. endIsHub인 날만 쓴다
     * @param routes     경로 엔진에 물어 본 구간({@link #hopKey}). 있으면 거리 추정과 정해 둔 도시 간 구간보다 먼저 쓴다
     */
    record Frame(int dayNumber, LocalDate date, int startMin, int endMin, int lodgingBy, int sunset, int sunrise,
                 PlaceCandidate startAt, PlaceCandidate arrivalHub, PlaceCandidate endAt, boolean endIsHub,
                 boolean hardEnd, TransportLeg firstLeg, boolean slowWalk, String transportPref,
                 List<Stop> spares, List<PlaceCandidate> earlier, int maxSights, double stayFactor,
                 TransportLeg lastLeg, String country, int hubDeadline, DraftItineraryPlanner.Layout layout,
                 Map<String, TransportLeg> routes) {

        Frame {
            layout = layout == null ? DraftItineraryPlanner.Layout.NONE : layout;
            routes = routes == null ? Map.of() : Map.copyOf(routes);
        }

        Frame(int dayNumber, LocalDate date, int startMin, int endMin, int lodgingBy, int sunset, int sunrise,
              PlaceCandidate startAt, PlaceCandidate arrivalHub, PlaceCandidate endAt, boolean endIsHub,
              boolean hardEnd, TransportLeg firstLeg, boolean slowWalk, String transportPref,
              List<Stop> spares, List<PlaceCandidate> earlier, int maxSights, double stayFactor,
              TransportLeg lastLeg, String country, int hubDeadline, DraftItineraryPlanner.Layout layout) {
            this(dayNumber, date, startMin, endMin, lodgingBy, sunset, sunrise, startAt, arrivalHub, endAt, endIsHub,
                    hardEnd, firstLeg, slowWalk, transportPref, spares, earlier, maxSights, stayFactor, lastLeg,
                    country, hubDeadline, layout, Map.of());
        }

        Frame(int dayNumber, LocalDate date, int startMin, int endMin, int lodgingBy, int sunset, int sunrise,
              PlaceCandidate startAt, PlaceCandidate arrivalHub, PlaceCandidate endAt, boolean endIsHub,
              boolean hardEnd, TransportLeg firstLeg, boolean slowWalk, String transportPref,
              List<Stop> spares, List<PlaceCandidate> earlier, int maxSights, double stayFactor,
              TransportLeg lastLeg, String country, int hubDeadline) {
            this(dayNumber, date, startMin, endMin, lodgingBy, sunset, sunrise, startAt, arrivalHub, endAt, endIsHub,
                    hardEnd, firstLeg, slowWalk, transportPref, spares, earlier, maxSights, stayFactor, lastLeg,
                    country, hubDeadline, DraftItineraryPlanner.Layout.NONE);
        }

        Frame withLodging(PlaceCandidate newStartAt, PlaceCandidate newEndAt) {
            return new Frame(dayNumber, date, startMin, endMin, lodgingBy, sunset, sunrise, newStartAt, arrivalHub,
                    newEndAt, endIsHub, hardEnd, firstLeg, slowWalk, transportPref, spares, earlier, maxSights,
                    stayFactor, lastLeg, country, hubDeadline, layout, routes);
        }

        Frame withSpares(List<Stop> newSpares, List<PlaceCandidate> newEarlier) {
            return new Frame(dayNumber, date, startMin, endMin, lodgingBy, sunset, sunrise, startAt, arrivalHub,
                    endAt, endIsHub, hardEnd, firstLeg, slowWalk, transportPref, newSpares, newEarlier, maxSights,
                    stayFactor, lastLeg, country, hubDeadline, layout, routes);
        }

        Frame withRoutes(Map<String, TransportLeg> newRoutes) {
            return new Frame(dayNumber, date, startMin, endMin, lodgingBy, sunset, sunrise, startAt, arrivalHub,
                    endAt, endIsHub, hardEnd, firstLeg, slowWalk, transportPref, spares, earlier, maxSights,
                    stayFactor, lastLeg, country, hubDeadline, layout, newRoutes);
        }

        /** 경로 엔진에 물어 본 구간. 묻지 않았으면 null이다. */
        TransportLeg routed(PlaceCandidate from, PlaceCandidate to) {
            if (routes.isEmpty()) return null;
            String key = hopKey(from, to);
            return key == null ? null : routes.get(key);
        }

        /**
         * 이 시각에 떠나면 고른 수단으로 갈 수 없는 구간. 갈 수 없다는 답은 물어 본 시각 둘레에만 맞아, 그보다 벗어나 떠나면
         * 추정으로 짓고 그 시각으로 다시 묻는다.
         */
        boolean unreachableAt(TransportLeg leg, int departMin) {
            if (leg == null || !leg.unreachable() || leg.route().departAt() == null) return false;
            java.time.OffsetDateTime at = java.time.OffsetDateTime.parse(leg.route().departAt());
            long asked = java.time.temporal.ChronoUnit.DAYS.between(date, at.toLocalDate()) * 1440
                    + at.getHour() * 60L + at.getMinute();
            return Math.abs(asked - departMin) <= UNREACHABLE_WINDOW_MINUTES;
        }

        /** 구간을 떠나는 곳이 든 지역의 이동 방법. 같은 날에도 렌터카 지역과 대중교통 지역이 섞인다. */
        String modeFrom(PlaceCandidate origin) {
            return origin == null ? transportPref : layout.modeAt(coordsOf(origin), transportPref);
        }

        /**
         * 두 곳 사이 구간. 말한 지역 둘 사이를 차 없이 오가면 도시 간 이동으로 잰다. 시내 버스 속도로 재면 당일치기
         * (삿포로에서 오타루)가 실제의 두 배 가까이 걸린다.
         */
        TransportLeg leg(PlaceCandidate from, PlaceCandidate to) {
            TransportLeg routed = routed(from, to);
            if (routed != null) return routed;
            return layoutLeg(layout, from, to, modeFrom(from), slowWalk);
        }

        /**
         * 그날 일정이 끝나는 시각. 출발 허브로 끝나는 날의 endMin은 숙소에서 허브로 떠날 시각이라, 검사처럼 허브에 닿는
         * 시각을 그날 끝으로 봐야 끼니를 기대하는 날이 맞는다.
         */
        int dayEnd() {
            return endIsHub ? hubDeadline : endMin;
        }

        /** 도착 허브에서 빠져나오는 시간. 국제선은 입국 심사와 짐 찾기가 더 걸린다. */
        int arrivalHubMinutes() {
            if (arrivalHub == null) return 0;
            return TripCalendar.isKorea(country) || !TripHubs.isAirport(arrivalHub) ? HUB_MINUTES
                    : ScheduleTuning.ARRIVAL_HUB_MINUTES;
        }
    }

    /**
     * @param placed 그날 넣은 장소(숙소·허브 제외). stops는 같은 순서의 정류지다
     * @param hops   일정에 들어간 이동. 경로 엔진에는 이 구간만 묻는다
     */
    record Built(List<StepData> steps, List<Drop> drops, List<PlaceCandidate> placed, List<Stop> stops,
                 List<Hop> hops) {

        Built(List<StepData> steps, List<Drop> drops, List<PlaceCandidate> placed, List<Stop> stops) {
            this(steps, drops, placed, stops, List.of());
        }

        List<String> dropped() {
            return drops.stream().map(Drop::toString).toList();
        }

        /** 넣지 못한 요청 장소(끼니 제외)와 이유. 같은 장소가 여러 번 빠졌으면 마지막 이유다. */
        List<Drop> missed() {
            Set<Long> kept = new HashSet<>();
            placed.forEach(p -> kept.add(p.placeId()));
            Map<Long, Drop> out = new java.util.LinkedHashMap<>();
            for (Drop d : drops) {
                Long id = d.stop().place().placeId();
                if (d.stop().protect() && !d.stop().meal() && id != null && !kept.contains(id)) out.put(id, d);
            }
            return List.copyOf(out.values());
        }
    }

    /**
     * 일정에 들어간 이동 한 번.
     *
     * @param departMin 앞 장소를 떠나는 시각(분)
     * @param leg       이 일정을 지을 때 쓴 구간
     * @param step      이 구간으로 도착하는 스텝의 순번(그날 steps 안)
     */
    record Hop(PlaceCandidate from, PlaceCandidate to, int departMin, TransportLeg leg, int step) {}

    /** 구간 열쇠. 숙소·허브는 id가 없을 수 있어 좌표로 잇는다. 좌표가 없으면 null이다. */
    static String hopKey(PlaceCandidate from, PlaceCandidate to) {
        double[] a = from == null ? null : coordsOf(from);
        double[] b = to == null ? null : coordsOf(to);
        if (a == null || b == null) return null;
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", a[0], a[1], b[0], b[1]);
    }

    /** 그날 넣지 못한 곳과 이유. */
    record Drop(Stop stop, String reason) {
        @Override
        public String toString() {
            return stop.place().name() + "(" + reason + ")";
        }

        MissCause cause() {
            return switch (reason) {
                case DEPARTURE_TOO_SOON -> MissCause.DEPARTURE;
                case CLOSED_DAY, HOLIDAY_CLOSED -> MissCause.CLOSED;
                case OUTSIDE_HOURS, LONG_OPENING_WAIT, LUNCH_PASSED, SUNRISE_PASSED -> MissCause.HOURS;
                case PAST_DAY_END, LATE_FOR_LODGING, YIELDED -> MissCause.TIME;
                case NO_TRANSIT -> MissCause.TRANSIT;
                case AFTER_DARK -> MissCause.DAYLIGHT;
                case NO_ROUTE, NO_COORDS -> MissCause.ROUTE;
                default -> MissCause.OTHER;
            };
        }
    }

    /** 요청 장소를 살리려고 다른 장소를 빼며 다시 짓는 횟수 상한. */
    private static final int MAX_REBUILDS = 8;
    /** 편이 없는 마지막 구간을 풀려고 덜어 내는 장소 수 상한. */
    static final int MAX_UNREACHABLE_DROPS = 3;
    /** 갈 수 없다는 답을 그대로 보는 출발 시각 폭(분). 장소 하나를 덜면 출발이 이보다 크게 당겨진다. */
    static final int UNREACHABLE_WINDOW_MINUTES = 30;

    /**
     * 도는 순서와 덜 좋은 배치를 빼는 기준을 바꿔 하루를 여러 번 짓고, 요청 장소를 가장 덜 잃고 숙소에 늦지 않는 안 가운데
     * {@link DayScore} 감점이 가장 작은 안을 고른다.
     */
    static Built build(Frame f, List<Stop> stops) {
        List<Stop> meals = lunchAsDinner(f, stops);
        List<List<Stop>> orders = new ArrayList<>();
        orders.add(ordered(meals, f.startAt() != null ? f.startAt() : f.arrivalHub()));
        orders.add(bySlot(meals));
        if (f.endAt() != null) orders.add(ordered(meals, f.endAt()));
        orders.add(protectedFirst(meals));
        Variant best = null;
        for (List<Stop> order : orders) {
            for (boolean strictSoft : new boolean[]{true, false}) {
                Variant v = buildOrder(f, order, true, strictSoft);
                v = new Variant(v.built(), v.lostProtected(), v.late(), DayScore.penalty(f, meals, v.built()));
                if (best == null || v.betterThan(best, f.endIsHub())) best = v;
            }
        }
        return best.built();
    }

    /**
     * @param keepOrder 주어진 순서대로 돈다. 다 지은 일정을 고친 뒤 시각만 다시 맞출 때 쓴다. 그 순서로는 요청 장소가
     *                  빠지면 요청 장소를 앞으로 옮긴 안과 견준다
     */
    static Built build(Frame f, List<Stop> stops, boolean keepOrder) {
        List<Stop> meals = lunchAsDinner(f, stops);
        Variant kept = buildOrder(f, meals, keepOrder, true);
        if (!keepOrder || kept.lostProtected() == 0) return kept.built();
        Variant moved = buildOrder(f, protectedFirst(meals), true, true);
        return moved.betterThan(kept, f.endIsHub()) ? moved.built() : kept.built();
    }

    /**
     * 고른 장소와 순서는 두고 실제 이동 시간으로 시각만 다시 맞춘다. 늦어진 도착 때문에 영업시간을 넘기는 곳은 이때 빠지고,
     * 먼저 지을 때 빠진 곳의 이유는 그대로 남긴다. 그 순서로 요청 장소가 빠지면 요청 장소를 앞으로 옮긴 안과 견준다.
     */
    static Built retimed(Frame f, Built prior) {
        Variant best = null;
        for (boolean strictSoft : new boolean[]{true, false}) {
            Variant v = buildOrder(f, prior.stops(), true, strictSoft);
            v = new Variant(v.built(), v.lostProtected(), v.late(), DayScore.penalty(f, prior.stops(), v.built()));
            if (best == null || v.betterThan(best, f.endIsHub())) best = v;
        }
        if (best.lostProtected() > 0) {
            Variant moved = buildOrder(f, protectedFirst(prior.stops()), true, true);
            moved = new Variant(moved.built(), moved.lostProtected(), moved.late(),
                    DayScore.penalty(f, prior.stops(), moved.built()));
            if (moved.betterThan(best, f.endIsHub())) best = moved;
        }
        Built b = best.built();
        java.util.LinkedHashSet<Drop> drops = new java.util.LinkedHashSet<>(prior.drops());
        drops.addAll(b.drops());
        return new Built(b.steps(), List.copyOf(drops), b.placed(), b.stops(), b.hops());
    }

    /** @param penalty 덜 좋은 정도. 지을 때만 매긴다 */
    private record Variant(Built built, int lostProtected, boolean late, double penalty) {
        /** @param toHub 출발 허브로 끝나는 날. 늦으면 떠나지 못해 요청 장소보다 먼저 본다 */
        boolean betterThan(Variant o, boolean toHub) {
            if (toHub && late != o.late) return !late;
            if (lostProtected != o.lostProtected) return lostProtected < o.lostProtected;
            if (late != o.late) return !late;
            return penalty < o.penalty;
        }
    }

    /**
     * 요청 장소를 끼니보다 먼저 돈다. 끼니 칸 순서를 따르면 출발이 이른 날 점심 뒤에 둔 요청 장소가 그날 끝을 넘겨 빠진다.
     */
    private static List<Stop> protectedFirst(List<Stop> stops) {
        List<Stop> out = new ArrayList<>(stops.stream().filter(s -> s.protect() && !s.meal()).toList());
        stops.stream().filter(s -> !(s.protect() && !s.meal())).forEach(out::add);
        return out;
    }

    /** 끼니 칸 순서만 지키고 같은 칸 안에서는 초안이 적은 순서대로 돈다. */
    private static List<Stop> bySlot(List<Stop> stops) {
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingInt(DayBuilder::slotRank));
        return sorted;
    }

    /**
     * @param strictSoft 끼니 시간을 밀어내거나 개점을 오래 기다리는 장소를 바로 뺀다. 끄면 넣어 두고 점수로 견준다
     */
    private static Variant buildOrder(Frame f, List<Stop> stops, boolean keepOrder, boolean strictSoft) {
        Set<Long> excluded = new HashSet<>();
        List<Drop> finalDropped = new ArrayList<>();
        Attempt best = null;
        List<Drop> bestDropped = null;
        int bestLost = 0;
        int unreachableDrops = 0;
        Attempt lastUnreachable = null;
        List<Drop> lastUnreachableDropped = null;
        int lastUnreachableLost = 0;
        for (int round = 0; ; round++) {
            List<Drop> dropped = new ArrayList<>(finalDropped);
            // 늦어서 빼낸 요청 장소도 잃은 것으로 센다. 세지 않으면 자리를 양보시킨 뒤 그 요청 장소마저 뺀 안이 더 나아 보인다.
            int lostAsVictims = (int) finalDropped.stream().filter(d -> d.stop().protect()).count();
            Attempt a = attempt(f, stops, excluded, dropped, keepOrder, strictSoft);
            int lost = a.lostProtected.size() + lostAsVictims;
            // 덜어도 마지막 구간을 떠나는 시각이 당겨지지 않으면(저녁 식당 뒤) 뺀 것을 되돌리고 충돌로 남긴다.
            if (a.endUnreachable && lastUnreachable != null && a.endDepartMin >= lastUnreachable.endDepartMin
                    && best == null) {
                return new Variant(new Built(lastUnreachable.steps, lastUnreachableDropped,
                        lastUnreachable.placed.stream().map(Stop::place).toList(), lastUnreachable.placed,
                        lastUnreachable.hops), lastUnreachableLost, true, 0);
            }
            if (a.endUnreachable) {
                lastUnreachable = a;
                lastUnreachableDropped = dropped;
                lastUnreachableLost = lost;
            }
            // 자리를 양보해도 요청 장소가 들어가지 않으면(마지막 날 저녁에 둔 야경) 양보 전 일정을 쓴다.
            if (a.lateBy <= 0 && (best == null || lost < bestLost)) {
                best = a;
                bestDropped = dropped;
                bestLost = lost;
            }
            Stop victim = null;
            String reason = null;
            if (a.lateBy > 0 && !a.placed.isEmpty()) {
                victim = lastUnprotected(a.placed);
                // 마지막 구간에 편이 없는 것은 시간이 모자란 것과 달라 요청 장소를 빼서 풀지 않는다.
                if (victim == null && !a.endUnreachable) {
                    Stop last = a.placed.get(a.placed.size() - 1);
                    // 저녁에 도시를 옮기는 날은 늦추면 탈 편을 놓친다.
                    boolean keepRequest = last.protect() && !f.endIsHub() && f.lastLeg() == null
                            && a.lateBy <= LATE_LODGING_TOLERANCE_MINUTES;
                    victim = keepRequest ? null : last;
                }
                // 덜어도 마지막 구간이 풀리지 않으면 더 빼지 않고 충돌로 남긴다.
                if (a.endUnreachable && ++unreachableDrops > MAX_UNREACHABLE_DROPS) victim = null;
                reason = a.endUnreachable ? NO_TRANSIT : LATE_FOR_LODGING;
            } else if (!a.lostProtected.isEmpty() && round < MAX_REBUILDS) {
                victim = lastUnprotected(a.placed.subList(0, a.firstLossAt));
                reason = YIELDED;
            }
            if (victim == null || round >= MAX_REBUILDS + 2) {
                if (a.lateBy <= 0 && best != null) {
                    a = best;
                    dropped = bestDropped;
                    lost = bestLost;
                }
                return new Variant(new Built(a.steps, dropped, a.placed.stream().map(Stop::place).toList(), a.placed,
                        a.hops), lost, a.lateBy > 0, 0);
            }
            excluded.add(victim.place().placeId());
            finalDropped.add(new Drop(victim, reason));
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

    /**
     * @param lostProtected  시간이 모자라 빠진 요청 장소
     * @param endUnreachable 숙소·출발 허브로 가는 마지막 구간에 그 시각 편이 없다
     * @param endDepartMin   마지막 구간을 떠나는 시각. 마지막 구간이 없으면 -1
     */
    private record Attempt(List<StepData> steps, List<Stop> placed, int lateBy, List<Stop> lostProtected,
                           int firstLossAt, List<Hop> hops, boolean endUnreachable, int endDepartMin) {
    }

    private static Attempt attempt(Frame f, List<Stop> stops, Set<Long> excluded, List<Drop> dropped,
                                   boolean keepOrder, boolean strictSoft) {
        DayState day = new DayState(new SelectionOutput.DayPlan(f.dayNumber(), null, List.of(), null, null));
        DayOfWeek dow = f.date().getDayOfWeek();
        LinkedList<Stop> spares = new LinkedList<>(f.spares() == null ? List.of() : f.spares());
        spares.removeIf(s -> excluded.contains(s.place().placeId()));
        List<Stop> queue = keepOrder ? new ArrayList<>(stops)
                : ordered(stops, f.startAt() != null ? f.startAt() : f.arrivalHub());
        queue.removeIf(s -> excluded.contains(s.place().placeId()));
        // 좌표가 없는 곳은 오가는 시간을 알 수 없어, 넣으면 그 앞뒤 구간이 모두 이동 없이 이어진다.
        for (Stop s : queue) if (coordsOf(s.place()) == null) dropped.add(new Drop(s, NO_COORDS));
        queue.removeIf(s -> coordsOf(s.place()) == null);
        spares.removeIf(s -> coordsOf(s.place()) == null);
        List<PlaceCandidate> seen = new ArrayList<>(f.earlier() == null ? List.of() : f.earlier());
        Set<Long> used = new HashSet<>();
        seen.forEach(c -> used.add(c.placeId()));

        List<StepData> steps = new ArrayList<>();
        List<Hop> hops = new ArrayList<>();
        List<Stop> placed = new ArrayList<>();
        List<Stop> lostProtected = new ArrayList<>();
        int firstLossAt = -1;
        int t = f.startMin();
        PlaceCandidate prev = f.startAt();
        boolean crossed = f.firstLeg() == null || f.arrivalHub() != null;
        if (f.arrivalHub() != null) {
            steps.add(DayTiming.buildStep(day, f.arrivalHub(), t, t + f.arrivalHubMinutes(), null, List.of()));
            t += f.arrivalHubMinutes();
            prev = f.arrivalHub();
        }
        for (int i = 0; i < queue.size(); i++) {
            Stop stop = queue.get(i);
            TransportLeg override = crossed ? null : f.firstLeg();
            if (stop.place().placeId() != null && used.contains(stop.place().placeId())) {
                // 앞 끼니의 대안이나 앞날에 쓴 식당이 다시 끼니로 오면 대안으로 바꾼다. 그냥 넘기면 끼니가 기록 없이 빠진다.
                Stop alt = stop.meal() ? mealAlternative(f, dow, prev, stop, t, used, excluded, override) : null;
                if (alt == null) {
                    if (stop.meal()) {
                        dropped.add(new Drop(stop, "이미 쓴 식당"));
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
                dropped.add(new Drop(stop, "이미 간 곳"));
                continue;
            }
            Slot slot = fit(f, dow, prev, stop, t, override, strictSoft);
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
                dropped.add(new Drop(stop, why));
                Stop sub = stop.meal() ? mealAlternative(f, dow, prev, stop, t, used, excluded, override)
                        : pickSpare(f, dow, prev, t, spares, used, seen, Integer.MAX_VALUE, override);
                // 요청한 음식의 대안은 그 음식을 내는 곳만 고르므로 대안이 들어가면 요청을 지킨 것이다.
                boolean kept = stop.meal() && sub != null;
                if (stop.protect() && !kept && !CLOSED_DAY.equals(why) && !HOLIDAY_CLOSED.equals(why)) {
                    if (lostProtected.isEmpty()) firstLossAt = placed.size();
                    lostProtected.add(stop);
                }
                if (sub != null) {
                    queue.add(i + 1, sub);
                }
                continue;
            }
            if (strictSoft && !stop.meal() && !stop.protect() && pushesMealOut(f, queue, i, slot)) {
                dropped.add(new Drop(stop, "끼니 시간 침범"));
                continue;
            }
            if (slot.end() > endLimit(f, stop.place())
                    && (f.hardEnd() || !(stop.meal() && slot.start() <= DINNER_EARLIEST + 60))) {
                dropped.add(new Drop(stop, PAST_DAY_END));
                log.debug("day={} 끝 초과 {} {}~{} end={}", f.dayNumber(), stop.place().name(),
                        ScheduleTimes.formatMinutes(slot.start()), ScheduleTimes.formatMinutes(slot.end()),
                        ScheduleTimes.formatMinutes(endLimit(f, stop.place())));
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
            if (prev != null && slot.leg() != null) {
                hops.add(new Hop(prev, stop.place(), t, slot.leg(), steps.size() - 1));
            }
            placed.add(stop);
            seen.add(stop.place());
            t = slot.end();
            prev = stop.place();
            crossed |= towardNewCity(f, stop.place());
        }

        // 마지막 장소 뒤로 하루 끝까지 시간이 남으면 예비 장소로 채운다. 출발일 오후가 통째로 비는 일정이 된다.
        while (endLimit(f, prev) - t >= FILL_TAIL && t <= DINNER_LATEST_START && sights(placed) < f.maxSights()) {
            TransportLeg override = crossed ? null : f.firstLeg();
            Stop filler = pickSpare(f, dow, prev, t, spares, used, seen,
                    f.endIsHub() ? Integer.MAX_VALUE : f.endMin(), override);
            if (filler == null) break;
            Slot slot = fit(f, dow, prev, filler, t, override);
            if (slot == null || slot.end() > endLimit(f, filler.place())) break;
            used.add(filler.place().placeId());
            steps.add(DayTiming.buildStep(day, filler.place(), slot.start(), slot.end(), slot.leg(), List.of()));
            if (prev != null && slot.leg() != null) {
                hops.add(new Hop(prev, filler.place(), t, slot.leg(), steps.size() - 1));
            }
            placed.add(filler);
            seen.add(filler.place());
            t = slot.end();
            prev = filler.place();
            crossed |= towardNewCity(f, filler.place());
        }

        int lateBy = 0;
        boolean endUnreachable = false;
        int endDepartMin = f.endAt() == null ? -1 : t;
        if (f.endAt() != null) {
            TransportLeg routed = f.routed(prev, f.endAt());
            TransportLeg leg = routed != null ? routed
                    : f.lastLeg() != null ? f.lastLeg()
                    : !crossed ? f.firstLeg()
                    : f.leg(prev, f.endAt());
            Integer legMinutes = leg == null ? null : leg.durationMin();
            // 허브까지 구간을 모르면 0분이 아니라 기본 몫으로 본다. 0분이면 출발 허브에 바로 닿는 일정이 된다.
            int arrive = t + (legMinutes != null ? legMinutes : f.endIsHub() ? TripHubs.HUB_TRAVEL_MINUTES : 0);
            int limit = f.endIsHub()
                    ? f.hubDeadline() - TripHubs.legSlack(legMinutes, leg == null ? null : leg.route())
                    : f.lodgingBy();
            lateBy = arrive - limit;
            // 편이 없는 마지막 구간은 늦은 것으로 보아 앞 장소를 덜어 더 이른 시각에 떠나게 한다.
            endUnreachable = f.unreachableAt(leg, t);
            if (endUnreachable) lateBy = Math.max(lateBy, 1);
            int stay = f.endIsHub() ? HUB_MINUTES : LODGING_MINUTES;
            steps.add(DayTiming.buildStep(day, f.endAt(), arrive, arrive + stay, leg, List.of()));
            if (prev != null && leg != null) hops.add(new Hop(prev, f.endAt(), t, leg, steps.size() - 1));
        }
        // 떠나기 전에 쓸 시간이 없는 출발일은 전날 숙소에서 나서는 것으로 둔다. 스텝이 없는 날은 저장 관문이 거부한다.
        if (steps.isEmpty() && f.startAt() != null) {
            steps.add(DayTiming.buildStep(day, f.startAt(), f.startMin(), f.startMin() + LODGING_MINUTES, null,
                    List.of()));
        }
        return new Attempt(steps, placed, lateBy, lostProtected, Math.max(0, firstLossAt), hops, endUnreachable,
                endDepartMin);
    }

    private record Slot(int arrive, int start, int end, TransportLeg leg) {
    }

    /**
     * 뒤에 점심·저녁이 남았는데 이 장소가 끼니 시간 끝을 넘겨 끝나면 이 장소를 뺀다. 관광을 다 넣고 끼니를 빼면
     * 점심 없이 오후를 보내거나 저녁 없이 숙소로 들어간다.
     */
    private static boolean pushesMealOut(Frame f, List<Stop> queue, int index, Slot slot) {
        for (int j = index + 1; j < queue.size(); j++) {
            Stop next = queue.get(j);
            if (!next.meal()) continue;
            // 출발일은 끼니도 그 식당에서 허브로 떠날 시각 전에 끝나야 한다.
            int mealBy = endLimit(f, next.place()) - MEAL_MINUTES;
            // 바로 다음이 식당이면 식당까지 가는 시간까지 넣어야 끼니 시작이 검사 기준(LUNCH_END)을 넘는지 맞게 본다.
            TransportLeg toMeal = j == index + 1 ? f.leg(queue.get(index).place(), next.place()) : null;
            int mealAt = slot.end() + (toMeal == null ? 0 : toMeal.durationMin());
            if ("lunch".equals(next.slot()) && mealAt > Math.min(LUNCH_LATEST_START, mealBy)) return true;
            if ("dinner".equals(next.slot()) && mealAt > Math.min(DINNER_LATEST_START, mealBy)) return true;
            return false;
        }
        return false;
    }

    private static Slot fit(Frame f, DayOfWeek dow, PlaceCandidate prev, Stop stop, int t, TransportLeg override) {
        return fit(f, dow, prev, stop, t, override, true);
    }

    /**
     * 그 시각에 갈 수 있으면 도착·시작·끝 시각, 아니면 null. 출발 허브로 끝나는 날은 그곳에서 허브로 떠나 기한에 닿을
     * 때만 넣는다.
     *
     * @param strictSoft 개점을 오래 기다려야 하는 장소를 넣지 않는다
     */
    private static Slot fit(Frame f, DayOfWeek dow, PlaceCandidate prev, Stop stop, int t, TransportLeg override,
                            boolean strictSoft) {
        Slot slot = fitInDay(f, dow, prev, stop, t, override, strictSoft);
        if (slot == null || !f.endIsHub()) return slot;
        return slot.end() <= endLimit(f, stop.place()) ? slot : null;
    }

    /**
     * 그곳에서 활동을 마쳐야 하는 시각. 출발 허브로 끝나는 날은 그곳에서 허브까지의 구간으로 잰다. 허브까지 기본 몫으로
     * 잡으면 공항이 먼 곳의 마지막 장소가 출발편을 놓친다.
     */
    static int endLimit(Frame f, PlaceCandidate at) {
        if (!f.endIsHub()) {
            // 저녁에 도시를 옮기는 날은 하루 끝을 도시 간 이동 추정으로 잡아 두었다. 실제 경로를 받으면 그 시간으로 다시 잡는다.
            TransportLeg routed = f.lastLeg() == null ? null : f.routed(at, f.endAt());
            if (routed == null || routed.unreachable()) return f.endMin();
            return f.endMin() + f.lastLeg().durationMin() - routed.durationMin();
        }
        TransportLeg leg = f.leg(at, f.endAt());
        return TripHubs.latestLeaveOn(f.hubDeadline(), leg);
    }

    /**
     * 아침에 도시를 옮기는 날 그곳이 새 도시 쪽인지. 옛 도시 쪽 장소(가는 길의 명소·점심)까지는 하루 구간으로 가고, 새 도시
     * 쪽으로 넘어가는 첫 구간이 도시 간 이동이다. 첫 장소에 도시 간 이동을 붙이면 옛 도시 쪽 장소가 빠진다.
     */
    static boolean towardNewCity(Frame f, PlaceCandidate c) {
        if (f.startAt() == null || f.endAt() == null) return true;
        return distanceKm(c, f.endAt()) <= distanceKm(c, f.startAt());
    }

    private static Slot fitInDay(Frame f, DayOfWeek dow, PlaceCandidate prev, Stop stop, int t,
                                 TransportLeg override, boolean strictSoft) {
        PlaceCandidate c = stop.place();
        TransportLeg routed = f.routed(prev, c);
        if (f.unreachableAt(routed, t)) return null;
        TransportLeg leg = routed != null ? routed
                : override != null && towardNewCity(f, c) ? override
                : f.leg(prev, c);
        if (leg == null && prev != null) return null;
        int arrive = t + (leg == null ? 0 : leg.durationMin());
        int start = arrive;
        if (stop.sunrise()) {
            int s = Math.max(arrive, f.sunrise() - SUNRISE_LEAD);
            if (s > f.sunrise() + SUNRISE_LEAD) return null;
            // 이름에 '일출'이 든 시설(일출랜드)은 일출 전에 문을 열지 않는다.
            List<int[]> open = OpeningHoursText.rangesFor(DayTiming.openingHoursOf(c), dow);
            if (open != null && open.stream().noneMatch(r -> r[0] <= s && s + SUNRISE_MINUTES <= r[1])) return null;
            return new Slot(arrive, s, s + SUNRISE_MINUTES, leg);
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
            if (strictSoft && s - start > MAX_OPENING_WAIT && !stop.meal()) return null;
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
        if (prev != null && f.unreachableAt(f.routed(prev, c), t)) return NO_TRANSIT;
        if (prev != null && f.leg(prev, c) == null) return NO_ROUTE;
        Slot inDay = f.endIsHub() ? fitInDay(f, dow, prev, stop, t, null, false) : null;
        if (inDay != null && inDay.end() > endLimit(f, c)) return DEPARTURE_TOO_SOON;
        if (stop.sunrise()) return SUNRISE_PASSED;
        if (stop.meal() && "lunch".equals(stop.slot())) return LUNCH_PASSED;
        if (!stop.evening() && !stop.protect() && isBridge(c)) return "낮에는 다리를 넣지 않음";
        if (TripCalendar.museumsClosed(f.country(), f.date()) && isMuseum(c)) {
            return HOLIDAY_CLOSED;
        }
        List<int[]> ranges = OpeningHoursText.rangesFor(DayTiming.openingHoursOf(c), dow);
        if (ranges != null && ranges.isEmpty()) return CLOSED_DAY;
        if (ranges != null && ranges.stream().anyMatch(r -> r[0] > t + MAX_OPENING_WAIT)) return LONG_OPENING_WAIT;
        if (ranges != null) return OUTSIDE_HOURS;
        return AFTER_DARK;
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
            if (best == null || km < bestKm) {
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
            default -> CandidateTraits.isThemePark(stop.place()) ? DayTiming.resolveVisitMinutes(stop.place())
                    : Math.max(45, Math.min(MAX_STAY,
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
     *
     * @return 좌표가 없거나 하루 안에 오갈 거리가 아니면 null. 도시를 옮기는 구간은 {@link IntercityLegs}가 잰다
     */
    /**
     * 말한 지역 둘 사이를 차 없이 오가는 구간은 도시 간 이동으로 잰다. 시내 버스 속도로 재면 당일치기(삿포로에서 오타루)가
     * 실제의 두 배 가까이 걸린다. 도시 안 구역(스스키노)이 낀 구간은 시내 구간이다.
     */
    static TransportLeg layoutLeg(DraftItineraryPlanner.Layout layout, PlaceCandidate from, PlaceCandidate to,
                                  String mode, boolean slowWalk) {
        TransportLeg local = legOf(from, to, mode, slowWalk);
        if (local == null || !"BUS".equals(local.mode())) return local;
        double[] a = coordsOf(from);
        double[] b = coordsOf(to);
        Destination fromArea = layout.areaAt(a);
        Destination toArea = layout.areaAt(b);
        if (fromArea == null || toArea == null || fromArea.equals(toArea)
                || DraftRequestFactory.isDistrict(fromArea) || DraftRequestFactory.isDistrict(toArea)) return local;
        IntercityLegs.IntercityLeg between = IntercityLegs.legBetween(
                new TripCities.City(fromArea.name(), fromArea.nameKo(), fromArea.country(), a[0], a[1]),
                new TripCities.City(toArea.name(), toArea.nameKo(), toArea.country(), b[0], b[1]), mode);
        return between.minutes() >= local.durationMin() ? local
                : new TransportLeg(between.mode(), between.minutes(), local.distanceKm(), between.costWon());
    }

    static TransportLeg legOf(PlaceCandidate from, PlaceCandidate to, String transportPref, boolean slowWalk) {
        if (from == null || to == null) return null;
        double[] a = coordsOf(from);
        double[] b = coordsOf(to);
        if (a == null || b == null) return null;
        boolean hubTransfer = PlaceCategoryConstants.isOverseasHub(from.country(), from.category())
                || PlaceCategoryConstants.isOverseasHub(to.country(), to.category());
        if (GeoUtils.haversine(a, b) > ScheduleTuning.maxLegKm(hubTransfer)) return null;
        double km = GeoUtils.haversine(a, b) * GeoUtils.ROAD_DISTANCE_FACTOR;
        String pref = transportPref == null ? "any" : transportPref;
        double walkLimit = switch (pref) {
            case "walk" -> slowWalk ? SLOW_WALK_KM : 2.0;
            case "car", "taxi" -> 0.8;
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
        // 대중교통으로만 다니는 지역은 가까운 구간도 택시로 잇지 않는다. 택시로 다니는 지역은 먼 구간도 택시다.
        if ("taxi".equals(pref) || km <= TAXI_KM && !"transit".equals(pref)) return taxiLeg(km, distance);
        // 먼 구간은 정류장이 드문 급행·간선이라 시내 구간보다 빠르다.
        double hours = km <= BUS_CITY_KM ? km / 20 : BUS_CITY_KM / 20 + (km - BUS_CITY_KM) / 35;
        int minutes = (int) Math.ceil(12 + hours * 60);
        return new TransportLeg("BUS", minutes, distance, BigDecimal.valueOf(1500));
    }

    /** 도로 km로 낸 택시 구간 추정. */
    static TransportLeg taxiLeg(BigDecimal roadKm) {
        return taxiLeg(roadKm == null ? 0 : roadKm.doubleValue(), roadKm);
    }

    private static TransportLeg taxiLeg(double km, BigDecimal shownKm) {
        return new TransportLeg("TAXI", (int) Math.ceil(5 + km / 25 * 60), shownKm,
                BigDecimal.valueOf(4800 + Math.round(Math.max(0, km - 1.6) * 800)));
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
        String k = PlaceNames.exactKey(c.displayName());
        for (PlaceCandidate s : seen) {
            if (c.placeId() != null && c.placeId().equals(s.placeId())) return true;
            if (distanceKm(c, s) > SAME_SPOT_KM) continue;
            if (CandidatePolicy.roleOf(s) == CandidatePolicy.Role.MEAL) continue;
            if (distanceKm(c, s) <= SAME_GROUNDS_KM) return true;
            String o = PlaceNames.exactKey(s.displayName());
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

    /** 좌표를 모르면 아주 먼 곳으로 본다. 0으로 보면 좌표 없는 곳이 아무 곳과 같은 자리가 된다. */
    static double distanceKm(PlaceCandidate a, PlaceCandidate b) {
        double[] x = coordsOf(a);
        double[] y = coordsOf(b);
        return x == null || y == null ? Double.MAX_VALUE : GeoUtils.haversine(x, y);
    }
}
