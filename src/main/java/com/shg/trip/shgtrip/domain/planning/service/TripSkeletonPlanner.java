package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용자가 직접 요구한 것에서 먼저 지켜야 할 뼈대를 정한다. 초안이 날짜에 두지 않은 요구를 넣고, 시각이 있는 요구는 전날 밤
 * 숙소 범위를, 도시를 옮기는 날은 옮긴 뒤 그 도시에서 보낼 시간이 남는지를 본다. 모델이 추천한 장소는 다루지 않는다.
 */
@Component
@Profile("eval")
@RequiredArgsConstructor
public class TripSkeletonPlanner {

    /** 첫차 시각 가정. 실제 시각표가 들어오기 전까지 일출 전 이동은 이 시각 이후에만 버스로 본다. */
    static final int FIRST_BUS = 6 * 60;
    /** 일출 전에 걸어서 닿을 거리. 대중교통 여행에서 첫차 전 일출이면 숙소가 이 안에 있어야 한다. */
    static final double SUNRISE_WALK_KM = 1.5;
    static final double SUNRISE_BUS_KM = 10;
    static final double SUNRISE_CAR_KM = 25;
    /** 일출 장소에 미리 닿아 있어야 하는 시간. 입장과 오르막을 걷는 몫이다. */
    static final int SUNRISE_LEAD = 30;
    static final double EVENING_TRANSIT_KM = 6;
    /** 도시 간 이동 뒤 그 도시에서 보낼 시간이 이보다 짧으면 그날을 그 도시의 하루로 볼 수 없다. */
    static final int MIN_CITY_MINUTES = 150;
    private static final int AIRPORT_BUFFER = 90;
    private static final int STATION_BUFFER = 20;
    private static final int DAY_START = 9 * 60 + 30;
    private static final int DAY_END = 21 * 60;
    /** 출발일에 쓸 시간이 이보다 짧으면 전날 밤을 출발 허브 가까이에 둔다. */
    private static final int SHORT_LAST_DAY = 240;
    private static final double DEPARTURE_LODGING_KM = 8;
    /** 숙소 권역 이름으로 찾은 곳이 도시 중심에서 이보다 멀면 다른 곳의 같은 이름이다. */
    private static final double CITY_KM = 30;
    private static final Pattern CITY_DAYS = Pattern.compile("^\\s*(\\d+)\\s*일");
    private static final Pattern LOW_WALKING = Pattern.compile("걷기\\s*(힘들|어려|불편)|다리가\\s*불편|휠체어|거동");

    private final DraftPlaceResolver resolver;

    /**
     * @param request   요구를 모두 날짜에 둔 초안과 숙소 범위·저녁 도시 이동을 담은 요청
     * @param conflicts 지금 조건으로는 지킬 수 없는 요구와 그 이유. 여행자에게 그대로 보인다
     * @param notes     코드가 고친 내용. 여행자에게 보이지 않는다
     */
    public record Skeleton(DraftItineraryPlanner.Request request, List<String> conflicts, List<String> notes,
                           TripConditions conditions) {
    }

    public Skeleton build(DraftItineraryPlanner.Request base) {
        VectorEnrichedInput input = base.input();
        int dayCount = (int) (input.endDate().toEpochDay() - input.startDate().toEpochDay()) + 1;
        List<String> conflicts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        DraftPlan draft = withAllWants(base.draft(), dayCount, base.cityNames(), notes);

        Map<Integer, DraftItineraryPlanner.LodgingNeed> needs = new HashMap<>();
        Map<String, DraftPlan.Want> wants = new LinkedHashMap<>();
        for (DraftPlan.Want w : nz(draft.wants())) if (w.id() != null) wants.put(w.id(), w);
        for (DraftPlan.Day day : nz(draft.days())) {
            for (DraftPlan.Item item : nz(day.items())) {
                DraftPlan.Want w = wants.get(item.want());
                if (w != null) timeNeed(base, w, day.day(), needs, conflicts);
            }
        }
        Set<Integer> transfers = eveningTransfers(base, draft, dayCount, conflicts);
        departureNeed(base, draft, dayCount, needs);
        return new Skeleton(base.with(draft, needs, transfers), conflicts, notes,
                conditions(base, wants.values(), base.cityNames()));
    }

    /** 초안이 어느 날에도 두지 않은 요구를 그 도시의 첫 온전한 날에 넣는다. 빠뜨린 요구는 일정에서 조용히 사라진다. */
    static DraftPlan withAllWants(DraftPlan draft, int dayCount, List<String> cityNames, List<String> notes) {
        Set<String> placed = new HashSet<>();
        for (DraftPlan.Day day : nz(draft.days())) {
            for (DraftPlan.Item item : nz(day.items())) if (item.want() != null) placed.add(item.want());
        }
        List<DraftPlan.Day> days = new ArrayList<>(nz(draft.days()));
        for (DraftPlan.Want w : nz(draft.wants())) {
            if (w.id() == null || "city".equals(w.kind()) || placed.contains(w.id())) continue;
            int at = dayFor(w, days, dayCount, cityNames);
            DraftPlan.Day day = days.stream().filter(x -> x.day() == at).findFirst().orElse(null);
            if (day == null) continue;
            List<DraftPlan.Item> items = new ArrayList<>(nz(day.items()));
            items.add(itemFor(w, day.area()));
            days.set(days.indexOf(day), new DraftPlan.Day(day.day(), day.area(), day.lodgingArea(), items,
                    day.spares()));
            notes.add(at + "일차에 초안이 빠뜨린 요구를 넣음: " + w.text());
        }
        return new DraftPlan(draft.concept(), draft.wants(), days);
    }

    private static int dayFor(DraftPlan.Want w, List<DraftPlan.Day> days, int dayCount, List<String> cityNames) {
        String text = Objects.toString(w.text(), "") + " " + Objects.toString(w.place(), "");
        String city = cityNames.stream().filter(text::contains).findFirst().orElse(null);
        int fallback = "sunrise".equals(w.time()) ? Math.min(2, dayCount) : dayCount >= 3 ? 2 : 1;
        if (city == null) return fallback;
        for (DraftPlan.Day day : days) {
            boolean full = day.day() > 1 && day.day() < dayCount || dayCount <= 2;
            if (full && Objects.toString(day.area(), "").contains(city)) return day.day();
        }
        return fallback;
    }

    private static DraftPlan.Item itemFor(DraftPlan.Want w, String area) {
        String first = nz(w.satisfy()).stream().findFirst().orElse(null);
        boolean evening = "evening".equals(w.time()) || "night".equals(w.time());
        if ("food".equals(w.kind())) {
            return new DraftPlan.Item(evening ? "dinner" : "lunch", "meal",
                    w.food() != null ? w.food() : w.text(), area, false, w.id(), null, null);
        }
        String name = w.place() != null ? w.place() : first != null ? first : w.text();
        String slot = "sunrise".equals(w.time()) || "morning".equals(w.time()) ? "morning"
                : evening ? "evening" : "afternoon";
        return new DraftPlan.Item(slot, "sight", name, area, true, w.id(), null, null);
    }

    /** 시각이 있는 요구는 그 시각에 닿을 수 있는 곳에 전날 묵어야 한다. */
    private void timeNeed(DraftItineraryPlanner.Request base, DraftPlan.Want w, int d,
                          Map<Integer, DraftItineraryPlanner.LodgingNeed> needs, List<String> conflicts) {
        String time = w.time();
        if (time == null || "food".equals(w.kind())) return;
        String name = w.place() != null ? w.place() : nz(w.satisfy()).stream().findFirst().orElse(null);
        double[] at = name == null ? null : placeCenter(name, base);
        if (at == null) return;
        String transport = Objects.toString(base.input().transportPref(), "any");
        if ("sunrise".equals(time)) {
            LocalDate date = base.input().startDate().plusDays(d - 1);
            int sunrise = SolarClock.sunrise(date, at[0], at[1]);
            if (d == 1) {
                int arrival = base.arrival() == null ? Integer.MAX_VALUE
                        : base.arrival().getHour() * 60 + base.arrival().getMinute();
                if (arrival > sunrise - SUNRISE_LEAD) {
                    conflicts.add("'" + w.text() + "'은(는) 첫날 도착이 일출(" + ScheduleTimes.formatMinutes(sunrise)
                            + ") 뒤라 첫날에는 볼 수 없습니다. 둘째 날 이후로 옮겨야 합니다.");
                }
                return;
            }
            double maxKm;
            String reason;
            if ("car".equals(transport)) {
                maxKm = SUNRISE_CAR_KM;
                reason = "일출 전 차로 닿을 거리";
            } else if (sunrise - SUNRISE_LEAD >= FIRST_BUS + 30) {
                maxKm = SUNRISE_BUS_KM;
                reason = "첫차로 일출 전에 닿을 거리";
            } else {
                maxKm = SUNRISE_WALK_KM;
                reason = "일출(" + ScheduleTimes.formatMinutes(sunrise) + ")이 첫차 전이라 걸어서 닿을 거리";
            }
            needs.put(d - 1, new DraftItineraryPlanner.LodgingNeed(at, maxKm, true, "'" + w.text() + "' " + reason));
        } else if (("evening".equals(time) || "night".equals(time)) && !"car".equals(transport)
                && !needs.containsKey(d)) {
            needs.put(d, new DraftItineraryPlanner.LodgingNeed(at, EVENING_TRANSIT_KM, false,
                    "'" + w.text() + "' 뒤 막차 전에 숙소로 돌아올 거리"));
        }
    }

    /**
     * 도시를 옮기는 날 아침에 옮기면 그 도시에서 보낼 시간이 모자라거나, 초안이 전날 밤 숙소를 다음 도시에 두었으면 전날
     * 저녁에 옮긴다.
     */
    private Set<Integer> eveningTransfers(DraftItineraryPlanner.Request base, DraftPlan draft, int dayCount,
                                          List<String> conflicts) {
        Set<Integer> out = new HashSet<>();
        List<Destination> cities = base.cityNames().stream().map(DomesticCities::find)
                .flatMap(java.util.Optional::stream).toList();
        if (cities.size() < 2) return out;
        Destination prev = null;
        for (int d = 1; d <= dayCount; d++) {
            DraftPlan.Day day = dayOf(draft, d);
            // 권역에 도시 이름이 없는 날("영도·감천")은 앞날과 같은 도시다. 여정은 요청한 도시 순서로 시작한다.
            Destination city = cityOf(cities, day == null ? null : day.area());
            if (city == null) city = prev != null ? prev : cities.get(0);
            if (prev != null && prev != city) {
                CityBlockPlanner.IntercityLeg leg = CityBlockPlanner.legBetween(city(prev), city(city),
                        base.input().transportPref());
                int end = DAY_END;
                if (d == dayCount && base.departure() != null) {
                    int buffer = "FLIGHT".equals(leg.mode()) || "제주".equals(city.nameKo()) ? AIRPORT_BUFFER
                            : STATION_BUFFER;
                    end = base.departure().getHour() * 60 + base.departure().getMinute() - buffer;
                }
                int left = end - (DAY_START + leg.minutes());
                DraftPlan.Day before = dayOf(draft, d - 1);
                boolean lodgesAhead = before != null && lodgesIn(before.lodgingArea(), city, base);
                if (left < MIN_CITY_MINUTES || lodgesAhead) {
                    out.add(d - 1);
                    int eveningLeft = end - DAY_START;
                    if (eveningLeft < MIN_CITY_MINUTES) {
                        conflicts.add(d + "일차 " + city.nameKo() + "에서 보낼 시간이 " + Math.max(0, eveningLeft)
                                + "분뿐이라 " + city.nameKo() + " 일정이 성립하지 않습니다. "
                                + (d == dayCount ? "출발 시각을 늦추거나 " : "") + city.nameKo() + " 날짜를 앞으로 옮겨야 합니다.");
                    }
                }
            }
            prev = city;
        }
        return out;
    }

    /**
     * 비행기로 떠나는 날 쓸 시간이 짧으면 전날 밤을 공항 가까이에 묵는다. 그날 권역에 묵으면 출발 전 이동만 하다 끝난다.
     * 역은 시내에 있어 하룻밤 숙소를 옮기는 비용이 더 크다.
     */
    private void departureNeed(DraftItineraryPlanner.Request base, DraftPlan draft, int dayCount,
                               Map<Integer, DraftItineraryPlanner.LodgingNeed> needs) {
        if (base.departure() == null || dayCount < 2 || needs.containsKey(dayCount - 1)) return;
        List<Destination> cities = base.cityNames().stream().map(DomesticCities::find)
                .flatMap(java.util.Optional::stream).toList();
        if (cities.isEmpty()) return;
        DraftPlan.Day last = dayOf(draft, dayCount);
        Destination city = cityOf(cities, last == null ? null : last.area());
        if (city == null) city = cities.get(cities.size() - 1);
        String hub = DraftItineraryPlanner.mainHub(city.nameKo());
        if (hub == null || !hub.contains("공항")) return;
        int left = base.departure().getHour() * 60 + base.departure().getMinute() - AIRPORT_BUFFER - DAY_START;
        if (left >= SHORT_LAST_DAY) return;
        DraftPlaceResolver.Resolution r = resolver.resolve(hub, "transit", base.input().regions(), List.of(),
                new double[]{city.lat(), city.lng()});
        if (r.place() == null) return;
        needs.put(dayCount - 1, new DraftItineraryPlanner.LodgingNeed(DraftPlaceResolver.coords(r.place()),
                DEPARTURE_LODGING_KM, false, "출발일 시간이 짧아 " + hub + " 가까이"));
    }

    private boolean lodgesIn(String lodgingArea, Destination city, DraftItineraryPlanner.Request base) {
        if (lodgingArea == null || lodgingArea.isBlank()) return false;
        if (lodgingArea.contains(city.nameKo())) return true;
        double[] at = placeCenter(lodgingArea.replaceAll("근처|주변|인근|일대|숙소", " ").trim(), base);
        return at != null && com.shg.trip.shgtrip.global.util.GeoUtils.haversine(at,
                new double[]{city.lat(), city.lng()}) <= CITY_KM;
    }

    /** 운영의 최종 검사가 쓰는 조건. 사용자가 직접 요구한 것만 담는다. */
    private static TripConditions conditions(DraftItineraryPlanner.Request base, java.util.Collection<DraftPlan.Want> wants,
                                             List<String> cityNames) {
        List<TripConditions.Want> out = new ArrayList<>();
        Map<String, Integer> cityDays = new LinkedHashMap<>();
        for (DraftPlan.Want w : wants) {
            if ("city".equals(w.kind())) {
                cityDays.putAll(cityDaysOf(w.text(), cityNames));
                continue;
            }
            List<String> satisfy = new ArrayList<>(nz(w.satisfy()));
            if (w.place() != null) satisfy.add(w.place());
            String food = "food".equals(w.kind()) ? (w.food() != null ? w.food() : w.text()) : null;
            out.add(new TripConditions.Want(w.text(), timing(w.time()), satisfy, food));
        }
        VectorEnrichedInput input = base.input();
        boolean low = input.companionType() == VectorEnrichedInput.CompanionType.SENIOR
                || LOW_WALKING.matcher(Objects.toString(input.description(), "")).find();
        List<PlaceRestriction> avoid = input.restrictions() == null ? List.of() : input.restrictions();
        return new TripConditions(out, cityDays, avoid, low, transportOf(input), base.arrival(), base.departure(),
                input.pace());
    }

    /** "서울 1일, 전주 3일"에서 도시별 날 수. 숫자가 없는 도시는 정하지 않은 것으로 본다. */
    static Map<String, Integer> cityDaysOf(String text, List<String> cityNames) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String city : cityNames) {
            int at = text.indexOf(city);
            if (at < 0) continue;
            Matcher m = CITY_DAYS.matcher(text.substring(at + city.length()));
            if (m.find()) out.put(city, Integer.parseInt(m.group(1)));
        }
        return out;
    }

    static String timing(String time) {
        if (time == null) return null;
        return switch (time) {
            case "sunrise" -> "sunrise";
            case "evening", "night" -> "evening";
            default -> null;
        };
    }

    static String transportOf(VectorEnrichedInput input) {
        String t = Objects.toString(input.transportPref(), "any");
        return "car".equals(t) ? "car" : "walk".equals(t) ? "walk" : "transit";
    }

    private double[] placeCenter(String name, DraftItineraryPlanner.Request base) {
        DraftPlaceResolver.Resolution r = resolver.resolve(name, "sight", base.input().regions(), base.cityNames(),
                null);
        Place p = r.place();
        return p == null ? null : DraftPlaceResolver.coords(p);
    }

    private static Destination cityOf(List<Destination> cities, String area) {
        if (area == null) return null;
        return cities.stream().filter(c -> area.contains(c.nameKo())).findFirst().orElse(null);
    }

    private static TripCities.City city(Destination d) {
        return new TripCities.City(d.name(), d.nameKo(), "KR", d.lat(), d.lng());
    }

    private static DraftPlan.Day dayOf(DraftPlan draft, int d) {
        for (DraftPlan.Day day : nz(draft.days())) if (day.day() == d) return day;
        return null;
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
