package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.planning.dto.DraftEdit;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput.CompanionType;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants.SearchRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;

/**
 * 장소 이름으로 쓴 초안을 일정으로 만든다. 이름은 DB에 잇고, 이름이 아닌 끼니·카페와 숙소는 그날 동선 근처에서 고르고,
 * 하루마다 {@link DayBuilder}로 시각을 정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftItineraryPlanner {

    /** 이름 없는 끼니·카페를 찾는 반경. 도보는 걸어갈 만한 거리, 차는 한 동네를 넘는 거리. */
    private static final double WALK_SEARCH_KM = 1.5;
    private static final double TRANSIT_SEARCH_KM = 3.0;
    private static final double CAR_SEARCH_KM = 4.0;
    private static final double LODGING_SEARCH_KM = 6.0;
    private static final int LODGING_LIMIT = 20;
    /** 도시 하나를 덮는 반경. 동선 가까운 숙소를 모두 놓쳤을 때 마지막으로 찾는 범위다. */
    private static final double CITY_WIDE_LODGING_KM = 30.0;
    private static final int SEARCH_LIMIT = 40;
    private static final LocalTime DEFAULT_START = LocalTime.of(9, 30);
    private static final LocalTime SLOW_START = LocalTime.of(10, 0);
    private static final LocalTime LODGING_BY = LocalTime.of(22, 0);
    private static final LocalTime SLOW_LODGING_BY = LocalTime.of(21, 30);
    /** 출발 시각을 모르는 마지막 날의 끝. 사람이 짠 일정은 이른 오후에 출발지 근처에서 끝난다. */
    static final LocalTime LAST_DAY_END = LocalTime.of(17, 0);
    private static final int ARRIVAL_LEAD = 30;
    /** 점심을 넣을 수 있는 가장 늦은 하루 시작. */
    private static final int LUNCH_LATEST = 13 * 60 + 30;
    /** 음식 이름이 든 식당은 근처 반경의 이 배수까지 찾는다. 요청한 음식이 동선에서 조금 먼 것은 감수한다. */
    private static final double DISH_RADIUS_FACTOR = 2.0;
    /** 끼니를 찾는 반경을 넓히는 최대 배수. */
    private static final int MAX_MEAL_WIDEN = 4;
    /** 요청한 음식을 찾는 거리. 한 도시 안이면 다 닿는다. */
    private static final double WANTED_DISH_KM = 15.0;
    /** 숙소를 옮기는 거리. 하루만 옮기면 짐을 들고 오가는 비용이 이동 절약보다 커서 두 밤 이상 묵을 곳만 옮긴다. */
    private static final double LODGING_SPLIT_KM = 10;
    private static final double CAR_LODGING_SPLIT_KM = 25;
    private static final int MIN_NIGHTS_TO_MOVE = 2;
    /** 일반 여행자가 묵기 어렵거나 동선에서 외진 숙소. */
    private static final java.util.regex.Pattern REMOTE_LODGING = java.util.regex.Pattern.compile(
            "골프텔|골프장|컨트리클럽|CC|연수원|수련원|기숙사|교육원");
    /** 숙소 분류로 적재된 구역 이름("보문관광단지"). 예약할 수 있는 숙소가 아니다. */
    private static final java.util.regex.Pattern LODGING_DISTRICT = java.util.regex.Pattern.compile(
            "관광단지$|관광특구$|단지$|휴양지$|지구$");
    private static final java.util.regex.Pattern NIGHT_WORDS = java.util.regex.Pattern.compile(
            "야경|야시장|야간|밤바다|포차|빅오쇼|BIG-O|불꽃|야행");
    /** 어두우면 볼 것이 없는 자연 야외. 케이블카·전망대·다리처럼 밤에 가는 명소는 넣지 않는다. */
    private static final java.util.regex.Pattern NATURE_OUTDOOR = java.util.regex.Pattern.compile(
            "Beach|Park|Mountain|Trail|Island|Waterfall|Garden|Forest|Volcano|Nature Preserve");
    /** 저녁을 겸하는 밤 일정. */
    private static final java.util.regex.Pattern FOOD_NIGHT = java.util.regex.Pattern.compile("야시장|포차");

    /** 식당으로 적재됐지만 한 끼가 아닌 간식·포장 가게. 요청한 음식이 아니면 끼니 칸에 두지 않는다. */
    private static final java.util.regex.Pattern SNACK_SHOP = java.util.regex.Pattern.compile(
            "꽈배기|떡집|모찌|닭강정|도넛|도나스|호떡|십원빵|고로케|핫도그|마카롱|와플|붕어빵|젤라또|만쥬|어묵");

    /** 현지 식당·카페를 찾는 요청에서 전국 체인은 뒤로 미룬다. */
    private static final java.util.regex.Pattern CHAIN = java.util.regex.Pattern.compile(
            "스타벅스|투썸|엔젤리너스|이디야|메가(MGC)?커피|빽다방|공차|할리스|파스쿠찌|커피빈|탐앤탐스|폴바셋|컴포즈|설빙|배스킨|던킨|"
                    + "롯데리아|맥도날드|버거킹|맘스터치|BHC|BBQ|교촌|bhc|starbucks|twosome");

    private final DraftPlaceResolver resolver;
    private final VectorSearchQueryService vectorSearch;
    private final CandidateSupplement candidateSupplement;
    private final PlaceFactsReader factsReader;
    private final TripHubs hubs;

    /**
     * @param cities           여행 도시. 비어 있지 않고, 첫 도시에서 여행을 시작한다
     * @param arrival          첫날 도착 시각. null이면 평소 시작 시각
     * @param departure        마지막 날 출발 시각. null이면 이른 오후에 끝낸다
     * @param arrivalHub       요청이 정한 도착 허브. null이면 첫 도시의 주 허브다
     * @param departureHub     요청이 정한 출발 허브. null이면 마지막 도시의 주 허브다
     * @param mustDo           요청에 따로 적힌 꼭 할 것. 초안 요청 문장에 쓴다
     * @param userWants        요청 구조화 필드의 요구. 초안이 빠뜨려도 지킨다
     * @param pinned           요구 id마다 사용자가 고른 장소. 이름으로 다시 찾지 않는다
     * @param bookedLodging    예약한 숙소. 그 도시의 밤은 모두 여기서 묵는다. 없으면 null
     * @param lodgingNeeds     밤(1부터)마다 숙소가 있어야 하는 곳. 시각이 있는 요구에서 나온다
     * @param eveningTransfers 저녁에 다음 도시로 옮겨 그 도시에서 자는 날(1부터)
     */
    public record Request(VectorEnrichedInput input, DraftPlan draft, List<Destination> cities,
                          LocalTime arrival, LocalTime departure, PlaceCandidate arrivalHub,
                          PlaceCandidate departureHub, List<String> mustDo, List<UserWant> userWants,
                          Map<String, PlaceCandidate> pinned, PlaceCandidate bookedLodging, TripExclusions exclusions,
                          Map<Integer, LodgingNeed> lodgingNeeds, Set<Integer> eveningTransfers,
                          List<String> avoidPlaceNames) {

        public Request {
            if (cities == null || cities.isEmpty()) throw new IllegalArgumentException("여행 도시가 없다");
            cities = List.copyOf(cities);
            mustDo = mustDo == null ? List.of() : List.copyOf(mustDo);
            userWants = userWants == null ? List.of() : List.copyOf(userWants);
            pinned = pinned == null ? Map.of() : Map.copyOf(pinned);
            exclusions = exclusions == null ? TripExclusions.NONE : exclusions;
            lodgingNeeds = lodgingNeeds == null ? Map.of() : lodgingNeeds;
            eveningTransfers = eveningTransfers == null ? Set.of() : eveningTransfers;
            avoidPlaceNames = avoidPlaceNames == null ? List.of() : List.copyOf(avoidPlaceNames);
        }

        public Request withDraft(DraftPlan newDraft) {
            return new Request(input, newDraft, cities, arrival, departure, arrivalHub, departureHub, mustDo,
                    userWants, pinned, bookedLodging, exclusions, lodgingNeeds, eveningTransfers, avoidPlaceNames);
        }

        public Request with(DraftPlan newDraft, Map<String, PlaceCandidate> newPinned,
                            Map<Integer, LodgingNeed> needs, Set<Integer> transfers) {
            return new Request(input, newDraft, cities, arrival, departure, arrivalHub, departureHub, mustDo,
                    userWants, newPinned, bookedLodging, exclusions, needs, transfers, avoidPlaceNames);
        }

        /** 도시 한국어 이름. 초안 이름 앞의 도시명을 떼는 데 쓴다. */
        public List<String> cityNames() {
            return cities.stream().map(Destination::nameKo).toList();
        }

        public double[] cityCenter() {
            return new double[]{cities.get(0).lat(), cities.get(0).lng()};
        }

        public String country() {
            return cities.get(0).country();
        }
    }

    /**
     * 요청 구조화 필드(꼭 할 경험·고른 장소)의 요구.
     *
     * @param place  가야 하는 장소 이름이나 지역 낱말. 없으면 null
     * @param time   sunrise·morning·evening. 시각이 상관없으면 null
     * @param pinned 사용자가 고른 장소. 이름으로 찾을 때는 null
     */
    /** @param accepted 넣지 못한다는 것을 대화에서 듣고도 그대로 진행하기로 한 요구 */
    public record UserWant(String text, String place, String time, PlaceCandidate pinned, boolean accepted) {

        public UserWant(String text, String place, String time, PlaceCandidate pinned) {
            this(text, place, time, pinned, false);
        }
    }

    /**
     * 그 밤 숙소가 있어야 하는 범위.
     *
     * @param hard 지키지 못하면 일정이 성립하지 않는다(일출 전 걸어서 도착). 아니면 가까울수록 좋은 정도다
     */
    public record LodgingNeed(double[] center, double maxKm, boolean hard, String reason) {
    }

    /**
     * @param candidates 일정에 쓴 장소. index는 목록 위치(1부터)다
     * @param frames     날마다 지을 때 쓴 조건. 수정을 반영해 다시 지을 때 그대로 쓴다
     * @param placed     날마다 넣은 정류지(숙소·허브 제외)와 시작 시각
     * @param reasons    장소 id별로 그 경험을 넣은 이유. 일정에 남은 장소의 이유만 여행자에게 보인다
     */
    public record Result(List<StepData> steps, List<PlaceCandidate> candidates, List<String> notes,
                         List<DayBuilder.Frame> frames, List<List<Placed>> placed, Map<Long, String> reasons) {
    }

    record Placed(DayBuilder.Stop stop, int at) {
    }

    private static final String[] WEEKDAYS = {"월", "화", "수", "목", "금", "토", "일"};
    private static final Map<CompanionType, String> COMPANION_LABELS = Map.of(
            CompanionType.SOLO, "혼자", CompanionType.COUPLE, "커플", CompanionType.FRIENDS, "친구",
            CompanionType.FAMILY, "가족", CompanionType.SENIOR, "부모님", CompanionType.UNKNOWN, "미정");
    private static final Map<String, String> PACE_LABELS = Map.of(
            "tight", "빡빡하게", "normal", "보통", "relaxed", "여유롭게");
    private static final Map<String, String> TRANSPORT_LABELS = Map.of(
            "car", "자동차", "walk", "도보(먼 곳은 대중교통)", "any", "대중교통 또는 상관없음");

    /** 초안 요청 문장. 날짜마다 요일·공휴일·일몰 시각을 적어, 모델이 휴관일과 해 지는 시각을 보고 순서를 정하게 한다. */
    public static String prompt(Request req) {
        VectorEnrichedInput input = req.input();
        double[] center = req.cityCenter();
        LocalTime arrival = req.arrival();
        LocalTime departure = req.departure();
        StringBuilder sb = new StringBuilder();
        sb.append("여행지: ").append(input.destination()).append('\n');
        if (!TripCalendar.isKorea(req.country())) {
            sb.append("나라: ").append(Destinations.countryNameKo(req.country())).append('\n');
        }
        sb.append("날짜: ");
        for (LocalDate d = input.startDate(); !d.isAfter(input.endDate()); d = d.plusDays(1)) {
            if (!d.equals(input.startDate())) sb.append(" / ");
            String holiday = TripCalendar.holidayName(req.country(), d);
            int sunset = SolarClock.sunset(d, center[0], center[1], req.country());
            sb.append(d).append('(').append(WEEKDAYS[d.getDayOfWeek().getValue() - 1])
                    .append(holiday == null ? "" : ", " + holiday).append(") 일몰 ")
                    .append(ScheduleTimes.formatMinutes(sunset));
        }
        sb.append('\n');
        CompanionType companion = input.companionType() == null ? CompanionType.UNKNOWN : input.companionType();
        sb.append("동행: ").append(COMPANION_LABELS.get(companion));
        if (input.partySize() != null) sb.append(' ').append(input.partySize()).append("명");
        sb.append('\n');
        sb.append("페이스: ").append(PACE_LABELS.getOrDefault(Objects.toString(input.pace(), ""), "보통")).append('\n');
        String mix = FameMix.of(input.themes()).draftLine();
        if (mix != null) sb.append("장소 성향: ").append(mix).append('\n');
        sb.append("이동: ").append(TRANSPORT_LABELS.getOrDefault(Objects.toString(input.transportPref(), ""),
                "대중교통 또는 상관없음")).append('\n');
        if (arrival != null) sb.append("첫날 도착: ").append(arrival).append('\n');
        if (departure != null) sb.append("마지막 날 출발: ").append(departure).append('\n');
        if (input.description() != null && !input.description().isBlank()) {
            sb.append("요청: ").append(input.description()).append('\n');
        }
        if (input.restrictions() != null && !input.restrictions().isEmpty()) {
            sb.append("피할 것: ").append(input.restrictions()).append('\n');
        }
        if (!req.avoidPlaceNames().isEmpty()) {
            // 이름 하나가 줄을 바꿔 다른 프롬프트 항목처럼 보이지 않게 한다.
            sb.append("가지 않을 곳: ").append(String.join(", ", req.avoidPlaceNames().stream()
                    .map(n -> n.replaceAll("\\s+", " ").strip()).toList())).append('\n');
        }
        for (String m : req.mustDo()) sb.append("꼭 할 것: ").append(m).append('\n');
        return sb.toString();
    }

    /** 수정 단계가 읽는 시간표. 줄마다 시각, 장소, 분류, 앞 장소에서의 이동이다. */
    public static String timeline(Result result) {
        StringBuilder sb = new StringBuilder();
        int day = 0;
        for (StepData s : result.steps()) {
            if (s.dayNumber() != day) {
                day = s.dayNumber();
                sb.append('[').append(day).append("일차]");
                DayBuilder.Frame f = result.frames() == null || day > result.frames().size() ? null
                        : result.frames().get(day - 1);
                if (f != null && f.hardEnd()) {
                    sb.append(" 활동은 ").append(ScheduleTimes.formatMinutes(f.endMin())).append("까지 끝나야 함");
                }
                sb.append('\n');
            }
            String category = s.place().category() == null ? "" : s.place().category();
            int cut = category.lastIndexOf('>');
            sb.append(s.startTime()).append('-').append(s.endTime()).append(' ').append(s.place().displayName()).append(" | ")
                    .append(cut < 0 ? category : category.substring(cut + 1).trim());
            if (isStayOrHub(s)) sb.append(" | 숙소·역");
            if (s.transportationMode() != null) {
                sb.append(" | ").append(s.transportationMode()).append(' ').append(s.transportationDuration()).append("분");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean isStayOrHub(StepData s) {
        String c = s.place().category() == null ? "" : s.place().category().toLowerCase(Locale.ROOT);
        return c.contains("hotel") || c.contains("lodging") || c.contains("motel") || c.contains("resort")
                || c.contains("hostel") || c.contains("rental") || c.contains("transport hub") || c.contains("airport")
                || c.contains("rail station") || c.contains("bed and breakfast") || c.contains("inn");
    }

    public Result plan(Request req) {
        Registry registry = new Registry(factsReader, req.cityNames(), vectorSearch.nearSearch(req.input()));
        List<String> notes = new ArrayList<>();
        Map<Long, String> reasons = new HashMap<>();
        VectorEnrichedInput input = req.input();
        TripExclusions exclusions = req.exclusions();
        List<DraftPlan.Day> days = req.draft().days() == null ? List.of() : req.draft().days();
        Map<String, DraftPlan.Want> wants = wantsById(req.draft());
        int dayCount = (int) (input.endDate().toEpochDay() - input.startDate().toEpochDay()) + 1;
        boolean slow = slowCompanion(input.companionType());
        String pace = input.pace() == null ? "normal" : input.pace();
        Set<Integer> transfers = req.eveningTransfers();
        List<Destination> cities = req.cities();

        List<List<DayBuilder.Stop>> stopsByDay = new ArrayList<>();
        List<List<DayBuilder.Stop>> sparesByDay = new ArrayList<>();
        Set<String> tripSights = new HashSet<>();
        List<double[]> anchors = new ArrayList<>();
        MealLedger usedEateries = new MealLedger();
        for (int d = 1; d <= dayCount; d++) {
            usedEateries.on(d, input.startDate().plusDays(d - 1));
            DraftPlan.Day day = dayOf(days, d);
            List<DraftPlan.Item> items = day == null || day.items() == null ? List.of()
                    : day.items().stream().map(i -> mealSlot(timed(i, wants.get(i.want())))).toList();
            List<DraftPlan.Item> spareItems = day == null || day.spares() == null ? List.of() : day.spares();
            // 같은 이름 장소가 여럿(성심당 본점·지점)이면 그날 권역에 가까운 곳을 고른다.
            Map<DraftPlan.Item, PlaceCandidate> named = resolveNamed(items, wants, input, req, areaAnchor(day, req),
                    registry, notes, d);
            double[] anchor = centroid(named.values().stream()
                    .filter(c -> CandidatePolicy.roleOf(c) != CandidatePolicy.Role.MEAL).toList());
            if (anchor == null) anchor = centroid(named.values());
            // 장소가 하나도 연결되지 않은 날을 여행지 첫 도시로 두면 여러 도시 여행에서 없던 도시 이동이 생긴다.
            if (anchor == null) anchor = anchors.isEmpty() ? req.cityCenter() : anchors.get(anchors.size() - 1);
            anchors.add(anchor);
            List<DayBuilder.Stop> stops = new ArrayList<>();
            Set<DraftPlan.Item> usedSpares = new HashSet<>();
            PlaceCandidate last = null;
            for (DraftPlan.Item item : items) {
                DraftPlan.Want want = wants.get(item.want());
                boolean asked = want != null || requested(req, item.name(), null);
                PlaceCandidate place = named.get(item);
                List<PlaceCandidate> alternatives = List.of();
                if (isEatery(item) || isFoodWant(want)) {
                    String excluded = place == null ? null : exclusions.violatedBy(place);
                    if (excluded != null) {
                        notes.add(d + "일차 뺌: " + place.name() + "(" + excluded + ")");
                        place = null;
                    }
                    if (place == null) {
                        double[] near = last != null ? coordsOf(last) : anchor;
                        Dish dish = dishOf(item, want, req);
                        String kind = isFoodWant(want) ? "meal" : item.kind();
                        List<PlaceCandidate> picks = pickEatery(kind, dish, input, req, near, exclusions,
                                usedEateries, registry);
                        if (picks.isEmpty() && dish.required()) {
                            notes.add(d + "일차 요청 음식 '" + dish.text() + "'을(를) 내는 식당을 찾지 못함");
                            asked = false;
                            picks = pickEatery(kind, Dish.NONE, input, req, near, exclusions, usedEateries, registry);
                        }
                        if (!picks.isEmpty()) {
                            place = picks.get(0);
                            alternatives = picks.subList(1, picks.size());
                        }
                    }
                    if (place == null) {
                        notes.add(d + "일차 " + item.name() + ": 장소를 찾지 못함");
                        continue;
                    }
                    usedEateries.add(place);
                } else {
                    if (place == null) place = resolveOne(item, input, req, anchor, registry, notes, d);
                    asked = asked || place != null && requested(req, null, place.displayName());
                    String why = place == null ? "장소를 찾지 못함"
                            : whyNotSight(place, asked, exclusions, registry, tripSights);
                    if (why != null) {
                        notes.add(d + "일차 뺌: " + (place == null ? item.name() : place.name()) + "(" + why + ")");
                        place = asked ? null : sameExperience(item, spareItems, usedSpares, input, req, anchor,
                                exclusions, registry, tripSights, notes, d);
                        if (place == null) continue;
                        notes.add(d + "일차 같은 경험으로 바꿈: " + place.name());
                    }
                    tripSights.add(registry.canonical(place));
                }
                if (item.reason() != null && place.placeId() != null) reasons.putIfAbsent(place.placeId(), item.reason());
                stops.add(new DayBuilder.Stop(place, kindOf(item, place), slotOf(item, place), alternatives, asked));
                last = place;
            }
            dropDuplicateMeals(stops);
            stopsByDay.add(stops);

            List<DayBuilder.Stop> spares = new ArrayList<>();
            for (DraftPlan.Item spare : spareItems) {
                if (usedSpares.contains(spare)) continue;
                PlaceCandidate place = resolveOne(spare, input, req, anchor, registry, notes, d);
                if (place == null || exclusions.violatedBy(place) != null
                        || DraftPlaceResolver.notAVisit(place.name(), place.category())) continue;
                spares.add(new DayBuilder.Stop(place, kindOf(spare, place), slotOf(spare, place), List.of()));
                String reason = reasonOfExperience(items, spare);
                if (reason != null && place.placeId() != null) reasons.putIfAbsent(place.placeId(), reason);
            }
            spares.addAll(knownSightsNear(input, anchor, registry, exclusions, input.startDate().plusDays(d - 1),
                    req.country(), fillDirection(input, stops)));
            sparesByDay.add(spares);
        }
        // 예비 장소는 일정에 있는 곳의 다른 이름이나 관광지가 아닌 곳을 빼 둔다. 빈 시간을 채우며 같은 곳이 두 번 들어간다.
        for (List<DayBuilder.Stop> spares : sparesByDay) {
            Set<String> seen = new HashSet<>(tripSights);
            spares.removeIf(x -> registry.whyNotVisit(x.place(), seen) != null || !seen.add(registry.canonical(x.place())));
        }

        List<Destination> cityByDay = new ArrayList<>();
        for (int d = 1; d <= dayCount; d++) {
            Destination named = cityNamed(cities, dayOf(days, d));
            Destination near = nearestCity(cities, anchors.get(d - 1));
            // 그날 장소가 다른 도시의 동명 장소로 연결되면 좌표가 도시를 바꾼다. 도시 배분은 초안을 따른다.
            if (named != null && named != near) anchors.set(d - 1, new double[]{named.lat(), named.lng()});
            cityByDay.add(named != null ? named : near);
        }
        moveClosedRequests(stopsByDay, cityByDay, input.startDate(), req, notes);
        Map<Integer, PlaceCandidate> lodgingByNight = chooseLodging(dayCount, stopsByDay, anchors, cityByDay,
                transfers, input, registry, req.lodgingNeeds(), exclusions, notes);
        keepBookedLodging(lodgingByNight, req, cityByDay, transfers, registry);
        PlaceCandidate arrivalHubOf = servesCity(req.arrivalHub(), cityByDay.get(0), cities)
                ? registry.add(req.arrivalHub())
                : req.arrival() == null ? null : hubOf(cityByDay.get(0), input, registry);
        boolean drivesHome = drivesHome(input, cityByDay.get(dayCount - 1))
                && !servesCity(req.departureHub(), cityByDay.get(dayCount - 1), cities);
        PlaceCandidate departureHubOf = servesCity(req.departureHub(), cityByDay.get(dayCount - 1), cities)
                ? registry.add(req.departureHub())
                : req.departure() == null || drivesHome ? null
                : hubOf(cityByDay.get(dayCount - 1), input, registry);
        moveOffShortDepartureDay(stopsByDay, cityByDay, lodgingByNight.get(dayCount - 1), departureHubOf, drivesHome,
                req, input, notes);
        // 출발일은 출발지 근처에서 마친다. 그날 권역 예비 장소만 두면 출발 허브에서 먼 곳으로 오전을 채운다. 전날 밤을
        // 허브 가까이 묵으면 그날 권역이 멀어 숙소 근처 명소가 아니면 오전에 들를 곳이 없다.
        if (departureHubOf != null && coordsOf(departureHubOf) != null) {
            Set<String> seen = new HashSet<>(tripSights);
            sparesByDay.get(dayCount - 1).forEach(x -> seen.add(registry.canonical(x.place())));
            PlaceCandidate lastNight = dayCount > 1 ? lodgingByNight.get(dayCount - 1) : null;
            for (PlaceCandidate from : lastNight == null ? List.of(departureHubOf) : List.of(departureHubOf, lastNight)) {
                if (coordsOf(from) == null) continue;
                for (DayBuilder.Stop near : knownSightsNear(input, coordsOf(from), registry, exclusions,
                        input.startDate().plusDays(dayCount - 1), req.country(), MIN_DEPARTURE_FILL_VALUE,
                        fillDirection(input, stopsByDay.get(dayCount - 1)))) {
                    if (registry.whyNotVisit(near.place(), seen) == null && seen.add(registry.canonical(near.place()))) {
                        sparesByDay.get(dayCount - 1).add(near);
                    }
                }
            }
        }
        int maxSights = ScheduleTuning.sightsPerDay(pace)[1];
        double stayFactor = STAY_FACTOR.getOrDefault(pace, 1.0);
        List<StepData> steps = new ArrayList<>();
        List<PlaceCandidate> earlier = new ArrayList<>();
        List<DayBuilder.Frame> frames = new ArrayList<>();
        List<List<Placed>> placed = new ArrayList<>();
        for (int d = 1; d <= dayCount; d++) {
            LocalDate date = input.startDate().plusDays(d - 1);
            boolean first = d == 1;
            boolean last = d == dayCount;
            double[] anchor = anchors.get(d - 1);
            int sunset = SolarClock.sunset(date, anchor[0], anchor[1], req.country());
            int sunrise = SolarClock.sunrise(date, anchor[0], anchor[1], req.country());
            List<DayBuilder.Stop> stops = stopsByDay.get(d - 1);
            int start = minutes(slow ? SLOW_START : DEFAULT_START);
            PlaceCandidate arrivalHub = null;
            if (first && req.arrival() != null) {
                start = minutes(req.arrival());
                arrivalHub = arrivalHubOf;
                if (arrivalHub == null) start += ARRIVAL_LEAD;
            }
            TransportLeg transfer = null;
            if (!first && cityByDay.get(d - 1) != cityByDay.get(d - 2) && !transfers.contains(d - 1)) {
                transfer = intercity(cityByDay.get(d - 2), cityByDay.get(d - 1), input);
                if (transfer != null) {
                    notes.add(d + "일차 도시 이동: " + cityByDay.get(d - 2).nameKo() + " → "
                            + cityByDay.get(d - 1).nameKo() + " " + transfer.mode() + " " + transfer.durationMin() + "분");
                }
            }
            TransportLeg lastLeg = null;
            if (!last && transfers.contains(d) && cityByDay.get(d) != cityByDay.get(d - 1)) {
                lastLeg = intercity(cityByDay.get(d - 1), cityByDay.get(d), input);
                if (lastLeg != null) {
                    notes.add(d + "일차 저녁 도시 이동: " + cityByDay.get(d - 1).nameKo() + " → "
                            + cityByDay.get(d).nameKo() + " " + lastLeg.mode() + " " + lastLeg.durationMin() + "분");
                }
            }
            boolean sunriseDay = stops.stream().anyMatch(DayBuilder.Stop::sunrise);
            if (sunriseDay && !(first && req.arrival() != null)) start = sunrise - DayBuilder.SUNRISE_LEAD - 30;
            int lodgingBy = minutes(slow ? SLOW_LODGING_BY : LODGING_BY);
            int end = lodgingBy - 30;
            if (lastLeg != null) {
                lodgingBy = minutes(TRANSFER_LODGING_BY);
                end = lodgingBy - lastLeg.durationMin() - 30;
            }
            PlaceCandidate endAt = lodgingByNight.get(d);
            boolean endIsHub = false;
            if (last) {
                if (req.departure() != null) {
                    end = TripHubs.departureDeadline(req.departure(), departureHubOf, drivesHome, req.country())
                            - (drivesHome ? 0 : TripHubs.HUB_TRAVEL_MINUTES
                            + TripHubs.estimatedLegSlack(TripHubs.HUB_TRAVEL_MINUTES));
                    endAt = departureHubOf;
                    endIsHub = endAt != null;
                } else {
                    end = minutes(LAST_DAY_END);
                    endAt = null;
                }
                // 아침 출발은 평소 시작 시각에 나서면 출발 허브 기한을 넘겨 저장 관문이 거부한다.
                if (!(first && req.arrival() != null) && end < start + ScheduleTuning.EARLY_DEPARTURE_LEAD_MINUTES) {
                    start = Math.max(ScheduleTuning.EARLIEST_DAY_START_MINUTES,
                            end - ScheduleTuning.EARLY_DEPARTURE_LEAD_MINUTES);
                }
            }
            usedEateries.on(d, input.startDate().plusDays(d - 1));
            // 야시장·포차를 저녁으로 세어 끼니를 넣지 않은 뒤 지을 때 앞날과 겹쳐 빠지면 저녁이 없어진다.
            List<DayBuilder.Stop> fresh = new ArrayList<>();
            for (DayBuilder.Stop x : stops) {
                if (!x.meal() && !x.protect() && DayBuilder.sameSpotAsAny(x.place(), earlier)) {
                    notes.add(d + "일차 뺌: " + x.place().name() + "(이미 간 곳)");
                } else {
                    fresh.add(x);
                }
            }
            stops = fresh;
            // 그날 밤 숙소가 그날 권역에서 멀면(다음 날 일출) 저녁과 저녁 앞 빈 시간을 숙소 쪽에서 보낸다. 먼 길은 저녁 전에 간다.
            double[] lodging = endAt == null || endIsHub ? null : coordsOf(endAt);
            boolean farLodging = lodging != null && lastLeg == null && kmBetween(anchor, lodging) > FAR_LODGING_KM;
            if (farLodging) {
                double[] at = lodging;
                stops = new ArrayList<>(stops);
                stops.removeIf(x -> !x.protect() && (x.meal() && "dinner".equals(x.slot()) || x.evening())
                        && coordsOf(x.place()) != null && kmBetween(coordsOf(x.place()), at) > FAR_LODGING_KM);
                Set<String> seen = new HashSet<>(tripSights);
                sparesByDay.get(d - 1).forEach(x -> seen.add(registry.canonical(x.place())));
                for (DayBuilder.Stop near : knownSightsNear(input, lodging, registry, exclusions, date, req.country(),
                        fillDirection(input, stops))) {
                    if (registry.whyNotVisit(near.place(), seen) == null && seen.add(registry.canonical(near.place()))) {
                        sparesByDay.get(d - 1).add(near);
                    }
                }
            }
            stops = withMeals(stops, start, end, input, req, anchor, farLodging ? lodging : null, exclusions,
                    usedEateries, registry);
            PlaceCandidate startAt = first ? null : lodgingByNight.get(d - 1);
            DayBuilder.Frame frame = new DayBuilder.Frame(d, date, start, end, lodgingBy, sunset, sunrise, startAt,
                    arrivalHub, endAt, endIsHub, last, transfer, slow, input.transportPref(),
                    sparesNotVisited(sparesByDay.get(d - 1), earlier, registry),
                    List.copyOf(earlier), maxSights, stayFactor, lastLeg, req.country());
            DayBuilder.Built built = DayBuilder.build(frame, stops);
            built.dropped().forEach(x -> notes.add(frame.dayNumber() + "일차 뺌: " + x));
            earlier.addAll(built.placed());
            steps.addAll(built.steps());
            frames.add(frame);
            placed.add(placedOf(built));
        }
        return new Result(AlternativeBuilder.pruneUsedAndRenumber(steps), registry.list(), notes, frames, placed,
                reasons);
    }

    /**
     * 짓기의 같은 곳 판정은 가까운 행끼리만 이름을 봐서, 좌표가 떨어진 다른 행("엑스포공원"·"여수엑스포공원")은 저장 검사
     * ({@code TripConditionChecks.duplicates})에서야 중복이 된다. 그래서 그 검사와 같은 이름 키로 미리 거른다.
     */
    static List<DayBuilder.Stop> sparesNotVisited(List<DayBuilder.Stop> spares, List<PlaceCandidate> earlier,
                                                  Registry registry) {
        Set<String> visited = new HashSet<>();
        earlier.forEach(c -> visited.add(registry.canonical(c)));
        return spares.stream().filter(x -> !visited.contains(registry.canonical(x.place()))).toList();
    }

    /** 체류 시간 배수. 여유롭게는 한 곳을 오래 보고, 빡빡하게는 여러 곳을 짧게 본다. */
    private static final Map<String, Double> STAY_FACTOR = Map.of("relaxed", 1.25, "tight", 0.85);
    /** 그날 권역과 숙소가 이보다 멀면 저녁을 숙소 쪽에서 먹는다. 도시 안 권역 이동보다 먼 거리다. */
    private static final double FAR_LODGING_KM = 20;
    /** 저녁에 도시를 옮기는 날 새 도시 숙소에 들어가는 기한. */
    private static final LocalTime TRANSFER_LODGING_BY = LocalTime.of(23, 0);

    private static TransportLeg intercity(Destination from, Destination to, VectorEnrichedInput input) {
        if (from == null || to == null || from == to) return null;
        IntercityLegs.IntercityLeg leg = IntercityLegs.legBetween(city(from), city(to), input.transportPref());
        return new TransportLeg(leg.mode(), leg.minutes(), leg.distanceKm(), leg.costWon());
    }

    private static Map<String, DraftPlan.Want> wantsById(DraftPlan draft) {
        Map<String, DraftPlan.Want> out = new HashMap<>();
        if (draft.wants() != null) for (DraftPlan.Want w : draft.wants()) if (w.id() != null) out.put(w.id(), w);
        return out;
    }

    private static boolean isFoodWant(DraftPlan.Want want) {
        return want != null && "food".equals(want.kind());
    }

    /** 요구에 시각이 있으면 그 시각의 칸에 둔다. 일출은 해 뜨기 전에 도착하는 일정이 된다. */
    private static DraftPlan.Item timed(DraftPlan.Item item, DraftPlan.Want want) {
        if (want == null || want.time() == null) return item;
        boolean eatery = isEatery(item) || isFoodWant(want);
        String slot = item.slot();
        String kind = item.kind();
        switch (want.time()) {
            case "sunrise" -> {
                if (!eatery) {
                    slot = "morning";
                    kind = "sunrise";
                }
            }
            case "morning" -> {
                if (!eatery) slot = "morning";
            }
            case "evening", "night" -> {
                slot = eatery ? "dinner" : "evening";
                if (!eatery) kind = "night";
            }
            default -> {
            }
        }
        return new DraftPlan.Item(slot, kind, item.name(), item.area(), item.placeNamed(), item.want(),
                item.experience(), item.reason(), item.localNames());
    }

    /**
     * 끼니 칸에서 찾을 음식.
     *
     * @param wanted   요청한 음식이라 동선에서 조금 멀어도 찾아간다
     * @param required 이름·메뉴로 그 음식을 내는 것이 확인된 식당만 쓴다
     * @param named    그 음식으로 이름난 식당. 메뉴가 맞을 때만 쓴다
     * @param aliases  같은 음식의 다른 표기(현지어·한국에서 부르는 이름)
     */
    private record Dish(String text, boolean wanted, boolean required, List<String> named, List<String> aliases) {
        static final Dish NONE = new Dish("", false, false, List.of(), List.of());
    }

    private static Dish dishOf(DraftPlan.Item item, DraftPlan.Want want, Request req) {
        if (isFoodWant(want)) {
            String food = want.food() != null && !want.food().isBlank() ? want.food() : item.name();
            return new Dish(food, true, true, want.satisfy() == null ? List.of() : want.satisfy(), want.foodAliases());
        }
        if (item.placeNamed()) return new Dish(item.name(), false, false, List.of(), List.of());
        boolean wanted = requested(req, item.name(), null);
        return new Dish(item.name(), wanted, wanted, List.of(), List.of());
    }

    /** @return 관광지로 넣지 않을 이유. 넣어도 되면 null */
    private static String whyNotSight(PlaceCandidate place, boolean asked, TripExclusions exclusions,
                                      Registry registry, Set<String> tripSights) {
        String excluded = exclusions.violatedBy(place);
        if (excluded != null) return excluded;
        if (DraftPlaceResolver.notAVisit(place.name(), place.category())) return "방문할 곳 아님";
        String why = registry.whyNotVisit(place, tripSights);
        // 방문 가치는 모델 추정이라 요청한 장소는 관광지가 아니라는 판정만으로 빼지 않는다.
        return NOT_A_SIGHT.equals(why) && asked ? null : why;
    }

    /** 추천한 장소를 쓸 수 없으면 같은 경험으로 적힌 예비 장소로 바꾼다. 경험이 같아야 그날의 이유 문장이 맞다. */
    private PlaceCandidate sameExperience(DraftPlan.Item item, List<DraftPlan.Item> spares,
                                          Set<DraftPlan.Item> usedSpares, VectorEnrichedInput input, Request req,
                                          double[] anchor, TripExclusions exclusions, Registry registry,
                                          Set<String> tripSights, List<String> notes, int day) {
        String experience = PlaceNames.exactKey(item.experience());
        if (experience.isEmpty()) return null;
        for (DraftPlan.Item spare : spares) {
            if (usedSpares.contains(spare) || !experience.equals(PlaceNames.exactKey(spare.experience()))) continue;
            usedSpares.add(spare);
            PlaceCandidate place = resolveOne(spare, input, req, anchor, registry, notes, day);
            if (place != null && whyNotSight(place, false, exclusions, registry, tripSights) == null) return place;
        }
        return null;
    }

    private static String reasonOfExperience(List<DraftPlan.Item> items, DraftPlan.Item spare) {
        String experience = PlaceNames.exactKey(spare.experience());
        if (experience.isEmpty()) return null;
        return items.stream().filter(i -> i.reason() != null && experience.equals(PlaceNames.exactKey(i.experience())))
                .map(DraftPlan.Item::reason).findFirst().orElse(null);
    }

    /**
     * 날마다 여행자에게 보일 이유 문장. 일정에 남은 장소에 붙은 이유만 쓴다. 코드가 빼거나 바꾼 장소의 이유를 쓰면 일정과
     * 다른 말이 된다.
     */
    public static Map<Integer, List<String>> travelerNotes(Result result) {
        Map<Integer, List<String>> out = new java.util.TreeMap<>();
        for (StepData s : result.steps()) {
            String reason = s.place().placeId() == null ? null : result.reasons().get(s.place().placeId());
            if (reason == null) continue;
            List<String> day = out.computeIfAbsent(s.dayNumber(), k -> new ArrayList<>());
            if (!day.contains(reason) && day.size() < 2) day.add(reason);
        }
        return out;
    }

    /** 날마다 보일 이유({@link #travelerNotes})를 그 이유로 넣은 장소의 스텝 메모로 둔다. */
    public static List<StepData> withReasonNotes(Result result) {
        Map<Integer, List<String>> shown = travelerNotes(result);
        Set<String> used = new HashSet<>();
        List<StepData> out = new ArrayList<>(result.steps().size());
        for (StepData s : result.steps()) {
            String reason = s.place().placeId() == null ? null : result.reasons().get(s.place().placeId());
            boolean show = reason != null && shown.getOrDefault(s.dayNumber(), List.of()).contains(reason)
                    && used.add(s.dayNumber() + ":" + reason);
            out.add(show ? s.withNotes(reason) : s);
        }
        return out;
    }

    private static List<Placed> placedOf(DayBuilder.Built built) {
        Map<Long, Integer> startById = new HashMap<>();
        for (StepData s : built.steps()) {
            Integer m = ScheduleTimes.toMinutesOrNull(s.startTime());
            if (s.place().placeId() != null && m != null) startById.putIfAbsent(s.place().placeId(), m);
        }
        List<Placed> out = new ArrayList<>();
        for (DayBuilder.Stop stop : built.stops()) {
            out.add(new Placed(stop, startById.getOrDefault(stop.place().placeId(), 0)));
        }
        return out;
    }

    /** 시간대별로 새 장소를 놓을 기준 시각. 같은 시각이면 기존 장소 뒤에 놓인다. */
    private static final Map<String, Integer> SLOT_AT = Map.of(
            "morning", 10 * 60, "lunch", 12 * 60, "afternoon", 14 * 60 + 30, "dinner", 18 * 60, "evening", 20 * 60);

    /**
     * 다 지은 일정에 모델의 수정을 반영하고 시각과 이동만 다시 맞춘다. 수정하지 않은 장소의 순서는 그대로 두고 빈 시간도
     * 채우지 않는다. 다시 지으면 코드의 채우기가 모델이 뺀 장소를 되살린다.
     */
    public Result applyEdits(Request req, Result prior, List<DraftEdit> edits) {
        Registry registry = new Registry(factsReader, req.cityNames(), vectorSearch.nearSearch(req.input()));
        prior.candidates().forEach(registry::add);
        List<String> notes = new ArrayList<>(prior.notes());
        TripExclusions exclusions = req.exclusions();
        List<List<Placed>> days = new ArrayList<>();
        MealLedger used = new MealLedger();
        for (int d = 0; d < prior.placed().size(); d++) {
            used.on(d + 1, null);
            days.add(new ArrayList<>(prior.placed().get(d)));
            for (Placed p : prior.placed().get(d)) {
                if (p.stop().meal() || "cafe".equals(p.stop().kind())) used.add(p.stop().place());
            }
        }
        List<DayBuilder.Frame> fixedFrames = new ArrayList<>(prior.frames());
        Set<Long> removed = new HashSet<>();
        for (DraftEdit e : edits == null ? List.<DraftEdit>of() : edits) {
            String failed = isLodgingEdit(e, fixedFrames, req)
                    ? applyLodgingEdit(e, fixedFrames, req, registry)
                    : applyEdit(e, days, req, exclusions, used, registry, removed);
            notes.add((failed == null ? "수정함: " : "수정 못 함(" + failed + "): ") + describe(e));
        }
        List<StepData> steps = new ArrayList<>();
        List<PlaceCandidate> earlier = new ArrayList<>();
        List<DayBuilder.Frame> frames = new ArrayList<>();
        List<List<Placed>> placed = new ArrayList<>();
        for (int d = 0; d < days.size(); d++) {
            DayBuilder.Frame f = fixedFrames.get(d);
            List<Placed> day = days.get(d);
            int dayNumber = d + 1;
            day.removeIf(p -> {
                boolean repeat = !p.stop().meal() && !p.stop().protect()
                        && DayBuilder.sameSpotAsAny(p.stop().place(), earlier);
                if (repeat) notes.add(dayNumber + "일차 수정 뒤 뺌: " + p.stop().place().name() + "(이미 간 곳)");
                return repeat;
            });
            // 모델이 끼니를 빼거나 다른 날로 옮긴 날은 그날 동선 근처에서 끼니를 다시 넣는다.
            List<DayBuilder.Stop> current = day.stream().map(Placed::stop).toList();
            double[] anchor = centroid(current.stream().map(DayBuilder.Stop::place).toList());
            used.on(dayNumber, f.date());
            List<DayBuilder.Stop> withMeals = withMeals(current, f.startMin(), f.endMin(), req.input(), req,
                    anchor == null ? req.cityCenter() : anchor, null, exclusions, used, registry);
            for (DayBuilder.Stop meal : withMeals.subList(current.size(), withMeals.size())) {
                // 저녁은 야경·밤 일정보다 앞에 둔다. 다시 지을 때 정한 순서대로 돌기 때문이다.
                int when = "dinner".equals(meal.slot()) ? DayBuilder.DINNER_EARLIEST - 1 : SLOT_AT.get(meal.slot());
                day.add(new Placed(meal, when));
            }
            // 모델이 뺀 장소를 빼고 남은 예비 장소로 빈 시간을 채운다.
            List<DayBuilder.Stop> spares = f.spares().stream()
                    .filter(x -> !removed.contains(x.place().placeId())).toList();
            DayBuilder.Frame frame = f.withSpares(sparesNotVisited(spares, earlier, registry), List.copyOf(earlier));
            List<DayBuilder.Stop> stops = day.stream()
                    .sorted(Comparator.comparingInt(Placed::at)).map(Placed::stop).toList();
            DayBuilder.Built built = DayBuilder.build(frame, stops, true);
            built.dropped().forEach(x -> notes.add(frame.dayNumber() + "일차 수정 뒤 뺌: " + x));
            earlier.addAll(built.placed());
            steps.addAll(built.steps());
            frames.add(frame);
            placed.add(placedOf(built));
        }
        return new Result(AlternativeBuilder.pruneUsedAndRenumber(steps), registry.list(), notes, frames, placed,
                prior.reasons());
    }

    /** @return 반영하지 못한 이유. 반영했으면 null */
    private String applyEdit(DraftEdit e, List<List<Placed>> days, Request req, TripExclusions exclusions,
                             MealLedger used, Registry registry, Set<Long> removed) {
        if (e.day() == null || e.day() < 1 || e.day() > days.size()) return "날짜 없음";
        List<Placed> day = days.get(e.day() - 1);
        int at = indexOf(day, e.target(), req);
        if (at >= 0 && !"add".equals(e.op()) && !"move".equals(e.op())) removed.add(day.get(at).stop().place().placeId());
        String op = Objects.toString(e.op(), "");
        switch (op) {
            case "remove" -> {
                if (at < 0) return "일정에 없는 장소";
                day.remove(at);
                return null;
            }
            case "move" -> {
                if (at < 0) return "일정에 없는 장소";
                if (e.toDay() == null || e.toDay() < 1 || e.toDay() > days.size()) return "날짜 없음";
                Placed p = day.remove(at);
                String slot = e.slot() != null ? e.slot() : p.stop().slot();
                DayBuilder.Stop s = p.stop();
                days.get(e.toDay() - 1).add(new Placed(new DayBuilder.Stop(s.place(), s.kind(), slot,
                        s.alternatives(), s.protect()), SLOT_AT.getOrDefault(slot, SLOT_AT.get("afternoon"))));
                return null;
            }
            case "replace", "add" -> {
                boolean replace = "replace".equals(op);
                if (replace && at < 0) return "일정에 없는 장소";
                DayBuilder.Stop old = at < 0 ? null : day.get(at).stop();
                String kind = e.kind() != null ? e.kind() : old != null && replace ? old.kind() : "sight";
                String slot = e.slot() != null ? e.slot() : old != null ? old.slot() : "afternoon";
                double[] near = old != null ? coordsOf(old.place()) : centroid(day.stream()
                        .map(p -> p.stop().place()).toList());
                if (near == null) near = req.cityCenter();
                used.on(e.day(), req.input().startDate().plusDays(e.day() - 1));
                DraftPlan.Item item = mealSlot(new DraftPlan.Item(slot, kind, e.name(), null, e.placeNamed()));
                DayBuilder.Stop stop = stopFor(item, req, near, exclusions, used, registry);
                if (stop == null) return "장소를 찾지 못함";
                // 끼니를 넣는 편집은 그 칸의 끼니를 바꾸는 것이다. 그대로 더하면 점심을 두 번 먹는다.
                if (stop.meal()) {
                    Placed same = day.stream().filter(p -> p.stop().meal() && p.stop().slot().equals(item.slot())
                            && (old == null || p.stop() != old)).findFirst().orElse(null);
                    if (same != null) {
                        removed.add(same.stop().place().placeId());
                        int sameAt = day.indexOf(same);
                        if (at > sameAt) at--;
                        day.remove(same);
                    }
                }
                if (!isEatery(item)) {
                    Set<String> visited = new HashSet<>();
                    for (List<Placed> other : days) {
                        for (Placed p : other) {
                            if ((!replace || p.stop() != old) && !p.stop().meal()) {
                                visited.add(registry.canonical(p.stop().place()));
                            }
                        }
                    }
                    String why = registry.whyNotVisit(stop.place(), visited);
                    if (why != null) return why;
                }
                if (replace) {
                    day.set(at, new Placed(stop, day.get(at).at()));
                } else {
                    int when = old != null ? day.get(at).at() + 1 : SLOT_AT.getOrDefault(slot, SLOT_AT.get("afternoon"));
                    day.add(new Placed(stop, when));
                }
                return null;
            }
            default -> {
                return "알 수 없는 수정";
            }
        }
    }

    private static final java.util.regex.Pattern LODGING_WORDS = java.util.regex.Pattern.compile(
            "근처|주변|인근|쪽|숙소|호텔|숙박|게스트하우스|리조트|펜션");

    /** 수정 대상이 그날 밤 숙소이거나 전날 밤 숙소인지. */
    private static boolean isLodgingEdit(DraftEdit e, List<DayBuilder.Frame> frames, Request req) {
        if (!"replace".equals(e.op()) || e.day() == null || e.day() < 1 || e.day() > frames.size()) return false;
        return lodgingOf(frames.get(e.day() - 1), e.target(), req) != null;
    }

    private static PlaceCandidate lodgingOf(DayBuilder.Frame f, String target, Request req) {
        if (f.endAt() != null && !f.endIsHub() && sameName(f.endAt().name(), target, req)) return f.endAt();
        if (f.startAt() != null && sameName(f.startAt().name(), target, req)) return f.startAt();
        return null;
    }

    /**
     * 숙소를 바꾼다. 새 이름이 숙소로 연결되면 그곳에, 아니면 그 이름이 가리키는 동네의 숙소에 묵는다. 같은 숙소에 묵던
     * 밤은 모두 바꿔 연박을 유지한다.
     */
    private String applyLodgingEdit(DraftEdit e, List<DayBuilder.Frame> frames, Request req, Registry registry) {
        PlaceCandidate old = lodgingOf(frames.get(e.day() - 1), e.target(), req);
        double[] near = coordsOf(old);
        VectorEnrichedInput input = req.input();
        DraftPlaceResolver.Resolution r = resolver.resolve(e.name(), "lodging", input.regions(), req.cityNames(), near);
        PlaceCandidate chosen = r.place() == null ? null : registry.add(r.place());
        // 숙소로 찾지 못하면 연결기가 관광지까지 넓혀 찾는다. 그때는 그 동네의 숙소로 본다.
        if (chosen != null && (CandidatePolicy.roleOf(chosen) != CandidatePolicy.Role.LODGING
                || near != null && coordsOf(chosen) != null && kmBetween(near, coordsOf(chosen)) > CITY_KM)) {
            chosen = null;
        }
        if (chosen == null) {
            String area = LODGING_WORDS.matcher(Objects.toString(e.name(), "")).replaceAll(" ").trim();
            DraftPlaceResolver.Resolution a = resolver.resolve(area, "sight", input.regions(), req.cityNames(), near);
            double[] at = a.place() == null ? null : DraftPlaceResolver.coords(a.place());
            if (at != null && near != null && kmBetween(near, at) > CITY_KM) at = null;
            PlaceCandidate pick = at == null ? null
                    : pickLodging(registry, at, LODGING_SEARCH_KM, LODGING_LIMIT, input.companionType(),
                    req.exclusions());
            chosen = pick == null ? null : registry.add(pick);
        }
        if (chosen == null) return "숙소를 찾지 못함";
        for (int i = 0; i < frames.size(); i++) {
            DayBuilder.Frame f = frames.get(i);
            boolean end = f.endAt() != null && !f.endIsHub() && Objects.equals(f.endAt().placeId(), old.placeId());
            boolean start = f.startAt() != null && Objects.equals(f.startAt().placeId(), old.placeId());
            if (!end && !start) continue;
            frames.set(i, f.withLodging(start ? chosen : f.startAt(), end ? chosen : f.endAt()));
        }
        return null;
    }

    private DayBuilder.Stop stopFor(DraftPlan.Item item, Request req, double[] near, TripExclusions exclusions,
                                    MealLedger used, Registry registry) {
        VectorEnrichedInput input = req.input();
        if (isEatery(item) && !item.placeNamed()) {
            boolean wanted = requested(req, item.name(), null);
            Dish dish = new Dish(item.name(), wanted, wanted, List.of(), List.of());
            List<PlaceCandidate> picks = pickEatery(item.kind(), dish, input, req, near, exclusions, used, registry);
            if (picks.isEmpty()) return null;
            used.add(picks.get(0));
            return new DayBuilder.Stop(picks.get(0), item.kind(), item.slot(), picks.subList(1, picks.size()), true);
        }
        DraftPlaceResolver.Resolution r = resolver.resolveNamed(item.name(), item.localNames(), item.kind(),
                input.regions(), req.cityNames(), near);
        if (r.place() == null) return null;
        PlaceCandidate place = registry.add(r.place());
        if (exclusions.violatedBy(place) != null) return null;
        if (!isEatery(item) && DraftPlaceResolver.notAVisit(place.name(), place.category())) return null;
        if (isEatery(item)) used.add(place);
        // 모델이 고쳐 넣은 장소는 요청 장소처럼 지킨다. 코드의 판단 규칙은 영업시간·시각 같은 사실만 넘지 못한다.
        return new DayBuilder.Stop(place, kindOf(item, place), slotOf(item, place), List.of(), true);
    }

    /** 수정 대상 이름과 같은 장소. 모델은 일정에 적힌 이름을 옮겨 쓰지만 괄호·도시 이름을 빼기도 한다. */
    private static int indexOf(List<Placed> day, String target, Request req) {
        if (target == null || target.isBlank()) return -1;
        for (int i = 0; i < day.size(); i++) {
            PlaceCandidate p = day.get(i).stop().place();
            if (exactName(p.name(), target, req) || exactName(p.displayName(), target, req)) return i;
        }
        for (int i = 0; i < day.size(); i++) {
            PlaceCandidate p = day.get(i).stop().place();
            if (sameName(p.name(), target, req) || sameName(p.displayName(), target, req)) return i;
        }
        return -1;
    }

    private static boolean exactName(String name, String target, Request req) {
        return DraftPlaceResolver.queryKey(name, req.cityNames()).equals(DraftPlaceResolver.queryKey(target,
                req.cityNames())) || PlaceNames.bareKey(name).equals(PlaceNames.bareKey(target));
    }

    private static boolean sameName(String name, String target, Request req) {
        if (name == null || target == null || target.isBlank()) return false;
        if (exactName(name, target, req)) return true;
        String want = DraftPlaceResolver.queryKey(target, req.cityNames());
        String bare = PlaceNames.bareKey(name);
        return !want.isEmpty() && !bare.isEmpty() && (bare.contains(want) || want.contains(bare));
    }

    private static String describe(DraftEdit e) {
        return e.day() + "일차 " + e.op() + " " + Objects.toString(e.target(), "")
                + (e.name() == null ? "" : " → " + e.name())
                + (e.toDay() == null ? "" : " → " + e.toDay() + "일차");
    }

    /**
     * 점심·저녁 칸이 비어 있으면 그날 동선 근처 식당으로 채운다. 초안이 끼니를 빠뜨리거나 연결에 실패한 날 끼니 없이
     * 숙소로 들어가는 일정이 된다. 야시장·포차 같은 먹거리 저녁 일정이 있으면 저녁을 따로 넣지 않는다.
     *
     * @param dinnerAt 저녁을 찾을 곳. null이면 오후 동선 근처다
     */
    private List<DayBuilder.Stop> withMeals(List<DayBuilder.Stop> stops, int start, int end,
                                            VectorEnrichedInput input, Request req, double[] anchor,
                                            double[] dinnerAt, TripExclusions exclusions, MealLedger used,
                                            Registry registry) {
        List<DayBuilder.Stop> out = new ArrayList<>(stops);
        boolean hasLunch = stops.stream().anyMatch(s -> s.meal() && "lunch".equals(s.slot()));
        boolean hasDinner = stops.stream().anyMatch(s -> s.meal() && "dinner".equals(s.slot()))
                || stops.stream().anyMatch(s -> s.evening() && FOOD_NIGHT.matcher(s.place().displayName()).find());
        if (!hasLunch && start <= LUNCH_LATEST && end >= DayBuilder.LUNCH_EARLIEST + DayBuilder.MEAL_MINUTES) {
            double[] near = nearSlot(stops, "morning", anchor);
            List<PlaceCandidate> picks = pickEatery("meal", Dish.NONE, input, req, near, exclusions, used, registry);
            if (!picks.isEmpty()) {
                used.add(picks.get(0));
                out.add(new DayBuilder.Stop(picks.get(0), "meal", "lunch", picks.subList(1, picks.size())));
            }
        }
        if (!hasDinner && end >= DayBuilder.DINNER_EARLIEST + DayBuilder.MEAL_MINUTES) {
            double[] near = dinnerAt != null ? dinnerAt : nearSlot(stops, "afternoon", anchor);
            List<PlaceCandidate> picks = pickEatery("meal", Dish.NONE, input, req, near, exclusions, used, registry);
            if (!picks.isEmpty()) {
                used.add(picks.get(0));
                out.add(new DayBuilder.Stop(picks.get(0), "meal", "dinner", picks.subList(1, picks.size())));
            }
        }
        return out;
    }

    private static double[] nearSlot(List<DayBuilder.Stop> stops, String slot, double[] anchor) {
        for (int i = stops.size() - 1; i >= 0; i--) {
            if (slot.equals(stops.get(i).slot()) && coordsOf(stops.get(i).place()) != null) {
                return coordsOf(stops.get(i).place());
            }
        }
        return anchor;
    }

    /**
     * 요청 문장이나 꼭 할 것에 나온 장소인지. 이름 앞 세 글자 이상이 요청에 있으면 요청한 것으로 본다.
     */
    private static boolean requested(Request req, String itemName, String placeName) {
        String wanted = PlaceNames.exactKey(Objects.toString(req.input().description(), "")
                + String.join(" ", req.mustDo() == null ? List.of() : req.mustDo()));
        if (wanted.isEmpty()) return false;
        for (String name : List.of(Objects.toString(itemName, ""), Objects.toString(placeName, ""))) {
            if (TripExclusions.namesAvoided(name, req.avoidPlaceNames())) continue;
            String k = DraftPlaceResolver.queryKey(name, req.cityNames());
            if (k.length() >= 3 && (wanted.contains(k) || wanted.contains(k.substring(0, 3)))) return true;
        }
        return false;
    }

    /**
     * 요청 장소가 그날 쉬면 같은 도시의 연 날로 옮긴다. 날짜를 옮기지 않으면 그 장소가 일정에서 빠진다.
     * 낮 시간이 넉넉한 날 가운데 원래 날에 가까운 날을 고른다. 저녁 도착일로 옮기면 들어갈 시간이 없어 빠진다.
     */
    private static void moveClosedRequests(List<List<DayBuilder.Stop>> stopsByDay, List<Destination> cityByDay,
                                           LocalDate startDate, Request req, List<String> notes) {
        int dayCount = stopsByDay.size();
        for (int d = 0; d < dayCount; d++) {
            for (DayBuilder.Stop stop : List.copyOf(stopsByDay.get(d))) {
                if (!stop.protect() || !closedOn(stop.place(), startDate.plusDays(d), req.country())) continue;
                int from = d;
                java.util.Optional<Integer> to = java.util.stream.IntStream.range(0, dayCount).boxed()
                        .filter(o -> o != from && cityByDay.get(o) == cityByDay.get(from))
                        .filter(o -> !closedOn(stop.place(), startDate.plusDays(o), req.country()))
                        .min(Comparator.comparing((Integer o) -> daytimeMinutes(req, o, dayCount) < MIN_MOVE_DAYTIME)
                                .thenComparingInt(o -> Math.abs(o - from))
                                .thenComparingInt(o -> -daytimeMinutes(req, o, dayCount)));
                if (to.isEmpty()) continue;
                stopsByDay.get(d).remove(stop);
                stopsByDay.get(to.get()).add(stop);
                notes.add((d + 1) + "일차 " + stop.place().name() + ": 휴무라 " + (to.get() + 1) + "일차로 옮김");
            }
        }
    }

    /**
     * 출발일 오전이 요구 장소 한 곳을 볼 시간도 안 되면 그 요구를 같은 도시의 낮이 가장 긴 다른 날로 옮긴다. 그대로 두면
     * 짓는 단계가 그날 끝 시각에 걸려 요구를 뺀다.
     *
     * @param lastLodging 출발 전날 밤 숙소. 없으면 허브까지 이동을 기본값으로 본다
     */
    private static void moveOffShortDepartureDay(List<List<DayBuilder.Stop>> stopsByDay, List<Destination> cityByDay,
                                                 PlaceCandidate lastLodging, PlaceCandidate hub, boolean drivesHome,
                                                 Request req, VectorEnrichedInput input, List<String> notes) {
        int dayCount = stopsByDay.size();
        if (dayCount < 2 || req.departure() == null) return;
        int last = dayCount - 1;
        TransportLeg toHub = drivesHome ? null : DayBuilder.legOf(lastLodging, hub, input.transportPref(), false);
        int lead = drivesHome ? 0 : (toHub != null ? toHub.durationMin() : TripHubs.HUB_TRAVEL_MINUTES)
                + TripHubs.estimatedLegSlack(toHub == null ? null : toHub.durationMin());
        int window = TripHubs.departureDeadline(req.departure(), hub, drivesHome, req.country()) - lead
                - minutes(DEFAULT_START);
        for (DayBuilder.Stop stop : List.copyOf(stopsByDay.get(last))) {
            if (!stop.protect() || stop.meal()) continue;
            if (window >= DayTiming.resolveVisitMinutes(stop.place()) + DEPARTURE_DAY_SLACK) continue;
            java.util.Optional<Integer> to = java.util.stream.IntStream.range(0, last).boxed()
                    .filter(o -> cityByDay.get(o) == cityByDay.get(last))
                    .max(Comparator.comparingInt((Integer o) -> daytimeMinutes(req, o, dayCount)).thenComparingInt(o -> o));
            if (to.isEmpty()) continue;
            stopsByDay.get(last).remove(stop);
            stopsByDay.get(to.get()).add(stop);
            notes.add(dayCount + "일차 " + stop.place().name() + ": 출발 전 시간이 모자라 " + (to.get() + 1) + "일차로 옮김");
        }
    }

    /** 숙소에서 첫 장소까지 가는 한 구간만큼의 여유. */
    private static final int DEPARTURE_DAY_SLACK = 30;

    /** 관광 한 곳과 끼니 한 번이 들어가는 낮 시간. */
    private static final int MIN_MOVE_DAYTIME = 4 * 60;

    private static int daytimeMinutes(Request req, int dayIndex, int dayCount) {
        int start = dayIndex == 0 && req.arrival() != null ? minutes(req.arrival()) : minutes(DEFAULT_START);
        int end = dayIndex == dayCount - 1 && req.departure() != null ? minutes(req.departure()) : minutes(LODGING_BY);
        return end - start;
    }

    private static boolean closedOn(PlaceCandidate c, LocalDate date, String country) {
        if (TripCalendar.museumsClosed(country, date) && DayBuilder.isMuseum(c)) return true;
        return OpeningHoursText.isClosedOnDay(DayTiming.openingHoursOf(c), date.getDayOfWeek());
    }

    /** 같은 칸에 끼니가 둘이면 요청한 끼니를, 없으면 앞의 것을 남긴다. 저녁 두 번은 초안이 끼니를 다른 칸에 잘못 적은 경우다. */
    private static void dropDuplicateMeals(List<DayBuilder.Stop> stops) {
        Set<String> asked = new HashSet<>();
        for (DayBuilder.Stop s : stops) if (s.meal() && s.protect()) asked.add(s.slot());
        Set<String> seen = new HashSet<>();
        stops.removeIf(s -> s.meal() && (asked.contains(s.slot()) && !s.protect() || !seen.add(s.slot())));
    }

    /** 끼니는 점심·저녁 칸에만 둔다. 아침 칸 식사는 숙소 아침이 아니라 점심 바로 앞의 두 번째 식사가 된다. */
    private static DraftPlan.Item mealSlot(DraftPlan.Item item) {
        if (!"meal".equals(item.kind()) || "lunch".equals(item.slot()) || "dinner".equals(item.slot())) return item;
        String slot = "evening".equals(item.slot()) ? "dinner" : "lunch";
        return new DraftPlan.Item(slot, item.kind(), item.name(), item.area(), item.placeNamed(), item.want(),
                item.experience(), item.reason(), item.localNames());
    }

    /** 이름이 밤 일정을 가리키면 저녁 칸으로 옮긴다. 야간 공연·야시장·야경을 낮에 넣으면 볼 것이 없다. */
    private static String slotOf(DraftPlan.Item item, PlaceCandidate place) {
        if (isEatery(item)) return item.slot();
        if (eveningWant(item)) return "evening";
        if (NIGHT_WORDS.matcher(Objects.toString(item.name(), "")).find()
                || NIGHT_WORDS.matcher(Objects.toString(place.displayName(), "")).find()) return "evening";
        // 해변·공원 같은 낮 야외를 저녁 칸에 두면 해 진 뒤 어둠 속에 머문다. 해 지기 전 오후로 당긴다.
        if ("evening".equals(item.slot()) && NATURE_OUTDOOR.matcher(Objects.toString(place.category(), "")).find()) {
            return "afternoon";
        }
        return item.slot();
    }

    private static String kindOf(DraftPlan.Item item, PlaceCandidate place) {
        if (isEatery(item)) return item.kind();
        if (eveningWant(item)) return "night";
        if ("sunrise".equals(item.kind()) || isSunrise(item.name())) return "sunrise";
        if ("evening".equals(slotOf(item, place)) && !"market".equals(item.kind())
                && (!NATURE_OUTDOOR.matcher(Objects.toString(place.category(), "")).find()
                || NIGHT_WORDS.matcher(Objects.toString(item.name(), "")).find())) return "night";
        if ("night".equals(item.kind()) && NATURE_OUTDOOR.matcher(Objects.toString(place.category(), "")).find()
                && !NIGHT_WORDS.matcher(Objects.toString(item.name(), "")).find()) return "sight";
        return item.kind();
    }

    /** 사용자가 저녁에 하겠다고 한 요구는 산·공원으로 연결돼도 야경 의도라 오후로 당기지 않는다. */
    private static boolean eveningWant(DraftPlan.Item item) {
        return item.want() != null && "night".equals(item.kind());
    }

    /** "성산일출봉"처럼 이름에 든 '일출'은 일출 일정이 아니다. */
    private static boolean isSunrise(String name) {
        return Objects.toString(name, "").replace("일출봉", "").contains("일출");
    }

    private static boolean isEatery(DraftPlan.Item item) {
        return "meal".equals(item.kind()) || "cafe".equals(item.kind());
    }

    /**
     * 요청에 적힌 허브가 그날 도시의 허브인지. 입력 보강이 문장에서 허브를 추측하기도 해, 다른 도시 공항에서 첫날을 시작하는
     * 일정이 된다.
     */
    static boolean servesCity(PlaceCandidate hub, Destination city, List<Destination> cities) {
        double[] at = hub == null ? null : coordsOf(hub);
        return at != null && city != null && nearestCity(cities, at) == city;
    }

    /** 차로 온 국내 여행은 마지막 장소에서 바로 떠난다. 해외와 섬은 렌터카를 공항에 돌려주고 비행기를 탄다. */
    private boolean drivesHome(VectorEnrichedInput input, Destination lastCity) {
        return "car".equals(input.transportPref()) && lastCity != null && !hubs.flies(lastCity);
    }

    private PlaceCandidate hubOf(Destination city, VectorEnrichedInput input, Registry registry) {
        if (city == null) return null;
        Place hub = hubs.mainHub(city, input.regions());
        return hub == null ? null : registry.add(hub);
    }

    /** 초안이 그날 권역에 적은 도시. 여러 도시 여행에서만 뜻이 있다. */
    private static Destination cityNamed(List<Destination> cities, DraftPlan.Day day) {
        if (cities.size() < 2 || day == null || day.area() == null) return null;
        return cities.stream().filter(c -> day.area().contains(c.nameKo())).findFirst().orElse(null);
    }

    private static Destination nearestCity(List<Destination> cities, double[] at) {
        return cities.stream().min(Comparator.comparingDouble(c ->
                com.shg.trip.shgtrip.global.util.GeoUtils.haversine(at, new double[]{c.lat(), c.lng()}))).orElse(null);
    }

    private static TripCities.City city(Destination d) {
        return new TripCities.City(d.name(), d.nameKo(), d.country(), d.lat(), d.lng());
    }

    /** 이름으로 적은 항목을 잇는다. 요청한 음식은 이름이 아니라 메뉴로 찾으므로 건너뛴다. */
    private Map<DraftPlan.Item, PlaceCandidate> resolveNamed(List<DraftPlan.Item> items,
                                                              Map<String, DraftPlan.Want> wants,
                                                              VectorEnrichedInput input, Request req, double[] anchor,
                                                              Registry registry, List<String> notes, int day) {
        Map<DraftPlan.Item, PlaceCandidate> out = new LinkedHashMap<>();
        for (DraftPlan.Item item : items) {
            PlaceCandidate pin = item.want() == null ? null : req.pinned().get(item.want());
            if (pin != null) {
                out.put(item, registry.add(pin));
                continue;
            }
            if (!isPlaceKind(item) && !item.placeNamed() || isFoodWant(wants.get(item.want()))) continue;
            DraftPlan.Want want = wants.get(item.want());
            List<String> locals = new ArrayList<>(item.localNames());
            if (want != null) want.localNames().stream().filter(l -> !locals.contains(l)).forEach(locals::add);
            DraftPlaceResolver.Resolution r = resolver.resolveNamed(item.name(), locals, item.kind(),
                    input.regions(), req.cityNames(), anchor);
            // 초안이 요구를 채울 장소 이름을 데이터에 없는 이름으로 적으면 요구에 쓴 다른 이름으로 다시 찾는다.
            for (String other : want == null ? List.<String>of() : wantNames(want)) {
                if (r.place() != null) break;
                if (!other.equals(item.name())) r = resolver.resolveNamed(other, locals, item.kind(),
                        input.regions(), req.cityNames(), anchor);
            }
            if (r.place() != null) {
                noteLooseLink(item.name(), DraftPlaceResolver.shownName(r.place()), req, notes, day);
                out.put(item, registry.add(r.place()));
            }
        }
        return out;
    }

    private static List<String> wantNames(DraftPlan.Want want) {
        List<String> out = new ArrayList<>();
        if (want.place() != null) out.add(want.place());
        if (want.satisfy() != null) out.addAll(want.satisfy());
        if (want.text() != null) out.add(want.text());
        return out.stream().filter(n -> n != null && !n.isBlank()).distinct().toList();
    }

    private PlaceCandidate resolveOne(DraftPlan.Item item, VectorEnrichedInput input, Request req, double[] anchor,
                                      Registry registry, List<String> notes, int day) {
        if (!isPlaceKind(item) && !item.placeNamed()) return null;
        DraftPlaceResolver.Resolution r = resolver.resolveNamed(item.name(), item.localNames(), item.kind(),
                input.regions(), req.cityNames(), anchor);
        if (r.place() == null) {
            log.debug("초안 이름 미연결 day={} {} ({})", day, item.name(), r.status());
            return null;
        }
        noteLooseLink(item.name(), DraftPlaceResolver.shownName(r.place()), req, notes, day);
        return registry.add(r.place());
    }

    /** 초안 이름과 연결된 장소 이름이 다르면 수정 단계가 정식 이름으로 다시 쓰거나 다른 장소로 바꾸게 알린다. */
    private static void noteLooseLink(String asked, String linked, Request req, List<String> notes, int day) {
        String a = DraftPlaceResolver.queryKey(asked, req.cityNames());
        String b = DraftPlaceResolver.queryKey(linked, req.cityNames());
        String bare = PlaceNames.bareKey(linked);
        if (a.equals(b) || a.equals(bare) || bare.startsWith(a) && bare.length() - a.length() <= 2) return;
        notes.add(day + "일차 '" + asked + "'을(를) '" + linked + "'(으)로 연결함");
    }

    /**
     * 음식 종류에 맞는 식당을 근처에서 고른다. 이름에 음식 이름이 든 식당을 먼저 찾고, 없으면 근처 식당 가운데 음식 종류가
     * 맞는 곳, 그다음 이름난 곳이다. 같은 식당은 다시 쓰지 않고, 다른 도시 이름을 단 식당(경주의 "제주흑돼지")은 뺀다.
     * 요청한 음식이면 그 음식을 내는 것이 이름·메뉴로 확인된 식당만 쓴다.
     */
    private List<PlaceCandidate> pickEatery(String kind, Dish dish, VectorEnrichedInput input, Request req,
                                            double[] near, TripExclusions exclusions, MealLedger used,
                                            Registry registry) {
        if (near == null) return List.of();
        double radius = switch (input.transportPref() == null ? "any" : input.transportPref()) {
            case "walk" -> WALK_SEARCH_KM;
            case "car" -> CAR_SEARCH_KM;
            default -> TRANSIT_SEARCH_KM;
        };
        boolean cafe = "cafe".equals(kind);
        List<PlaceCandidate> dishPool = new ArrayList<>();
        if (!cafe) {
            for (String token : dishWords(dish.text(), dish.aliases())) {
                resolver.findMealsByDish(token, input.regions(), near).forEach(p -> dishPool.add(PlaceCandidates.of(p, 0)));
            }
        }
        for (String name : dish.named()) {
            DraftPlaceResolver.Resolution r = resolver.resolve(name, "meal", input.regions(), req.cityNames(), near);
            if (r.place() != null) dishPool.add(PlaceCandidates.of(r.place(), 0));
        }
        // 산사·호수처럼 식당이 드문 곳에서 끝난 칸은 넓혀 찾는다. 못 찾으면 끼니가 빠진 하루가 된다.
        for (int widen = 1; widen <= MAX_MEAL_WIDEN; widen *= 2) {
            List<PlaceCandidate> picks = pickEateryWithin(cafe, dish, dishPool, input, req, near, radius * widen,
                    widen, exclusions, used, registry);
            if (!picks.isEmpty()) return picks;
        }
        return List.of();
    }

    /** @param dishPool 이름·메뉴로 찾은 그 음식 식당 */
    private List<PlaceCandidate> pickEateryWithin(boolean cafe, Dish dish, List<PlaceCandidate> dishPool,
                                                  VectorEnrichedInput input, Request req, double[] near, double radius,
                                                  int widen, TripExclusions exclusions, MealLedger used,
                                                  Registry registry) {
        List<String> tokens = dishWords(dish.text(), dish.aliases());
        // 요청한 음식은 동선에서 조금 멀어도 찾아간다.
        double dishRadius = dish.wanted() ? WANTED_DISH_KM * widen : radius * DISH_RADIUS_FACTOR;
        List<PlaceCandidate> pool = new ArrayList<>(dishPool.stream().filter(c -> kmTo(c, near) <= dishRadius).toList());
        pool.addAll(candidateSupplement.enrichFromDb(registry.near.search(
                cafe ? SearchRole.CAFE : SearchRole.RESTAURANT, near, radius, SEARCH_LIMIT, 1)));
        Map<Long, PlaceCandidate> unique = new LinkedHashMap<>();
        pool.forEach(c -> unique.putIfAbsent(c.placeId(), c));
        return registry.withClosures(unique.values()).stream()
                .filter(c -> c.placeId() != null && !used.contains(c.placeId()))
                // 요청한 음식은 쉬는 날이어도 남겨 두면 다른 날로 옮긴다(moveClosedRequests). 여는 곳이 있으면 그쪽이 먼저다.
                .filter(c -> dish.required() || used.date == null || !closedOn(c, used.date, req.country()))
                .filter(c -> exclusions.violatedBy(c) == null)
                // 요청한 음식이 빵·디저트면 그 가게는 카페로 분류돼 있다. 끼니로만 찾으면 요청이 빠진다.
                .filter(c -> cafe ? CandidatePolicy.roleOf(c) == CandidatePolicy.Role.CAFE
                        : CandidatePolicy.roleOf(c) == CandidatePolicy.Role.MEAL
                        && PlaceCategoryConstants.isMealPlace(c.name(), c.category(), c.tags())
                        || dish.required() && CandidatePolicy.roleOf(c) == CandidatePolicy.Role.CAFE)
                .filter(c -> !DraftPlaceResolver.notAVisit(c.name(), c.category()))
                .filter(c -> !namesOtherCity(c.name(), req))
                .filter(c -> !GENERIC_NAMES.contains(PlaceNames.exactKey(c.name())))
                .filter(c -> !dish.required() || WantMatcher.serves(dish.text(), dish.aliases(), dish.named(), c))
                .filter(c -> cafe || dish.required() || !SNACK_SHOP.matcher(Objects.toString(c.displayName(), "")).find())
                .sorted(Comparator.comparing((PlaceCandidate c) -> used.date != null
                                && closedOn(c, used.date, req.country()))
                        .thenComparing(c -> !matchesDish(c, tokens))
                        .thenComparing(used::repeats)
                        .thenComparing(c -> CHAIN.matcher(c.name() == null ? "" : c.name()).find())
                        .thenComparing(c -> -PlaceProminence.of(c))
                        .thenComparingDouble(c -> kmTo(c, near)))
                .limit(3)
                .map(registry::add)
                .toList();
    }

    /** 국내 식당은 다른 도시 이름을 단 가게가 흔하다. 해외 식당 이름에는 한국어 도시 이름이 들어가지 않는다. */
    private static boolean namesOtherCity(String name, Request req) {
        if (name == null || !TripCalendar.isKorea(req.country())) return false;
        List<String> tripCities = req.cityNames();
        return DomesticCities.all().stream().map(Destination::nameKo)
                .filter(c -> !tripCities.contains(c))
                .anyMatch(name::contains);
    }

    private static final String NOT_A_SIGHT = "관광지 아님";
    private static final java.util.regex.Pattern HANGUL = java.util.regex.Pattern.compile("[가-힣]");

    /** 코드가 빈 시간을 채울 때 쓰는 최소 방문 가치. 근처에 있으면 들를 만한 곳(1)으로 채우면 채우기용 장소가 된다. */
    private static final int MIN_FILL_VALUE = 2;
    /** 출발일 오전은 숙소·출발 허브 근처에서만 보낼 수 있어 근처에 있으면 들를 만한 곳(1)도 채운다. */
    private static final int MIN_DEPARTURE_FILL_VALUE = 1;
    /** 빈 시간 후보 수. 일정에 이미 든 곳과 겹치는 것을 빼고도 오후를 채울 만큼 둔다. */
    private static final int KNOWN_SIGHTS_LIMIT = 8;

    /**
     * 빈 시간을 채울 근처 명소. 방문 가치 판정이 없으면 언어판이나 알려진 정도가 있는 곳만 쓴다. 장소 정보가 빈약한
     * 곳으로 채우면 관공서·운동장이 관광지로 들어간다.
     */
    private List<DayBuilder.Stop> knownSightsNear(VectorEnrichedInput input, double[] anchor, Registry registry,
                                                  TripExclusions exclusions, LocalDate date, String country,
                                                  double direction) {
        return knownSightsNear(input, anchor, registry, exclusions, date, country, MIN_FILL_VALUE, direction);
    }

    /** @param direction {@link FameMix#fillDirection} */
    private List<DayBuilder.Stop> knownSightsNear(VectorEnrichedInput input, double[] anchor, Registry registry,
                                                  TripExclusions exclusions, LocalDate date, String country,
                                                  int minValue, double direction) {
        if (anchor == null) return List.of();
        long seed = FameMix.seedOf(input.destination(), String.valueOf(date),
                input.companionType() == null ? "" : input.companionType().name(),
                input.themes() == null ? "" : String.join(",", input.themes().stream().sorted().toList()));
        double radius = "car".equals(input.transportPref()) ? CAR_SEARCH_KM : TRANSIT_SEARCH_KM;
        List<PlaceCandidate> eligible = registry.withClosures(candidateSupplement.enrichFromDb(
                registry.near.search(SearchRole.ATTRACTION, anchor, radius, SEARCH_LIMIT, 1))).stream()
                .filter(c -> !TripCalendar.isKorea(c.country()) || HANGUL.matcher(Objects.toString(c.name(), "")).find())
                .filter(c -> !DraftPlaceResolver.notAVisit(c.name(), c.category()))
                .filter(c -> {
                    PlaceFactsReader.PlaceFact f = registry.fact(c);
                    if (f != null && f.value() != null) return f.value() >= minValue;
                    Integer sitelinks = PlaceProminence.effectiveSitelinks(c.wikidataSitelinks(), c.travelerFame());
                    return sitelinks != null && sitelinks >= 1;
                })
                .filter(c -> !CandidatePolicy.isEventOnlySight(c.name(), c.category()))
                .filter(c -> exclusions.violatedBy(c) == null)
                // 쉬는 곳을 자르기 전에 빼야 휴관일에 채울 곳이 남는다.
                .filter(c -> !closedOn(c, date, country))
                .toList();
        return FameMix.pickNearby(eligible, direction, seed, KNOWN_SIGHTS_LIMIT).stream()
                .map(c -> new DayBuilder.Stop(registry.add(c), "sight", "afternoon", List.of()))
                .toList();
    }

    private static double fillDirection(VectorEnrichedInput input, List<DayBuilder.Stop> dayStops) {
        return FameMix.of(input.themes()).fillDirection(
                dayStops == null ? List.of() : dayStops.stream().filter(s -> !s.meal()).map(DayBuilder.Stop::place).toList());
    }

    /**
     * 밤마다 그날 마지막 장소와 다음 날 첫 장소 사이에 숙소를 둔다. 앞 밤 숙소가 그 지점에서 가까우면 연박한다.
     * 도시를 옮기는 날 전날 밤은 떠나는 도시에, 일출을 보는 날 전날 밤은 일출 장소 근처에 둔다.
     */
    private Map<Integer, PlaceCandidate> chooseLodging(int dayCount, List<List<DayBuilder.Stop>> stopsByDay,
                                                       List<double[]> anchors, List<Destination> cityByDay,
                                                       Set<Integer> transfers, VectorEnrichedInput input,
                                                       Registry registry, Map<Integer, LodgingNeed> needs,
                                                       TripExclusions exclusions, List<String> notes) {
        double splitKm = "car".equals(input.transportPref()) ? CAR_LODGING_SPLIT_KM : LODGING_SPLIT_KM;
        // 저녁에 도시를 옮기는 밤은 다음 도시에서 잔다.
        List<Destination> nightCity = new ArrayList<>();
        for (int d = 1; d < dayCount; d++) nightCity.add(transfers.contains(d) ? cityByDay.get(d) : cityByDay.get(d - 1));
        List<List<Integer>> groups = new ArrayList<>();
        List<double[]> centers = new ArrayList<>();
        for (int d = 1; d < dayCount; d++) {
            double[] c = transfers.contains(d)
                    ? lodgingCenter(List.of(), stopsByDay.get(d), anchors.get(d), true, cityByDay.get(d))
                    : lodgingCenter(stopsByDay.get(d - 1), stopsByDay.get(d), anchors.get(d - 1),
                    cityByDay.get(d - 1) == cityByDay.get(d), cityByDay.get(d - 1));
            LodgingNeed need = needs.get(d);
            if (need != null) c = need.center();
            centers.add(c);
            List<Integer> group = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            // 조건이 있는 밤은 다른 밤과 묶지 않는다. 묶음 중심으로 옮기면 그 조건을 못 지킨다.
            if (need != null || (group != null && needs.containsKey(group.get(0)))) {
                if (group == null || need == null || !needs.containsKey(group.get(0))
                        || kmBetween(needs.get(group.get(0)).center(), need.center()) > 1) {
                    groups.add(new ArrayList<>(List.of(d)));
                } else {
                    group.add(d);
                }
                continue;
            }
            boolean sameCity = group != null && nightCity.get(group.get(0) - 1) == nightCity.get(d - 1);
            int nightsLeftHere = 0;
            for (int n = d; n < dayCount && nightCity.get(n - 1) == nightCity.get(d - 1); n++) nightsLeftHere++;
            boolean far = group != null && kmBetween(groupCenter(group, centers), c) > splitKm;
            if (group == null || !sameCity || (far && nightsLeftHere >= MIN_NIGHTS_TO_MOVE)) {
                groups.add(new ArrayList<>(List.of(d)));
            } else {
                group.add(d);
            }
        }
        Map<Integer, PlaceCandidate> byNight = new HashMap<>();
        CompanionType companion = input.companionType();
        for (List<Integer> group : groups) {
            double[] center = groupCenter(group, centers);
            LodgingNeed need = needs.get(group.get(0));
            PlaceCandidate pick;
            if (need != null) {
                pick = pickLodging(registry, center, need.maxKm(), 4 * LODGING_LIMIT, companion, exclusions);
                if (pick != null && kmTo(pick, center) > need.maxKm()) pick = null;
                if (pick == null && need.hard()) {
                    notes.add(group.get(0) + "일차 숙소 조건 불가: " + need.reason() + " 범위(" + need.maxKm()
                            + "km) 안에 숙소가 없음");
                }
            } else {
                pick = null;
            }
            if (pick == null) pick = pickLodging(registry, center, LODGING_SEARCH_KM, LODGING_LIMIT, companion, exclusions);
            // 가까운 후보가 모두 동행 조건에 걸리는 동네(한옥마을의 게스트하우스)는 더 넓게 찾는다.
            if (pick == null) {
                pick = pickLodging(registry, center, 2 * LODGING_SEARCH_KM, 4 * LODGING_LIMIT, companion, exclusions);
            }
            Destination city = nightCity.get(group.get(0) - 1);
            if (pick == null && city != null) {
                pick = pickLodging(registry, new double[]{city.lat(), city.lng()}, 2 * LODGING_SEARCH_KM,
                        4 * LODGING_LIMIT, companion, exclusions);
            }
            // 밤마다 숙소로 끝나야 하므로 마지막에는 동행·동네 조건을 풀고 도시 전체에서 찾는다.
            if (pick == null && city != null) {
                pick = pickLodging(registry, new double[]{city.lat(), city.lng()}, CITY_WIDE_LODGING_KM,
                        4 * LODGING_LIMIT, null, exclusions, false);
                if (pick != null) notes.add(group.get(0) + "일차 숙소: 조건에 맞는 곳이 없어 도시 안 다른 숙소로 정함");
            }
            PlaceCandidate chosen = pick == null ? null : registry.add(pick);
            if (chosen == null) log.warn("숙소 후보 없음 nights={} center={},{}", group, center[0], center[1]);
            for (int night : group) byNight.put(night, chosen);
        }
        return byNight;
    }

    /** 예약한 숙소가 있는 도시의 밤은 모두 그 숙소다. 동선에 맞춰 고른 숙소보다 사용자의 예약이 먼저다. */
    private static void keepBookedLodging(Map<Integer, PlaceCandidate> byNight, Request req,
                                          List<Destination> cityByDay, Set<Integer> transfers, Registry registry) {
        PlaceCandidate booked = req.bookedLodging();
        double[] at = booked == null ? null : coordsOf(booked);
        if (at == null) return;
        Destination bookedCity = nearestCity(req.cities(), at);
        PlaceCandidate chosen = registry.add(booked);
        for (int night = 1; night < cityByDay.size(); night++) {
            Destination city = transfers.contains(night) ? cityByDay.get(night) : cityByDay.get(night - 1);
            if (city == bookedCity) byNight.put(night, chosen);
        }
    }

    private PlaceCandidate pickLodging(Registry registry, double[] center, double radiusKm, int limit,
                                       CompanionType companion, TripExclusions exclusions) {
        return pickLodging(registry, center, radiusKm, limit, companion, exclusions, true);
    }

    /** @param preferredOnly 외딴 숙소·숙박 밀집 동네 이름을 거른다 */
    private PlaceCandidate pickLodging(Registry registry, double[] center, double radiusKm, int limit,
                                       CompanionType companion, TripExclusions exclusions, boolean preferredOnly) {
        return candidateSupplement.enrichFromDb(
                        registry.near.search(SearchRole.ACCOMMODATION, center, radiusKm, limit, 1)).stream()
                .filter(c -> exclusions.violatedBy(c) == null)
                .filter(c -> companion == null || !companion.avoidsDormLodging()
                        || !PlaceCategoryConstants.isDormLodging(c.name(), c.category(), c.tags()))
                .filter(c -> c.placeRole() == null || "LODGING".equals(c.placeRole()))
                .filter(c -> !preferredOnly || !REMOTE_LODGING.matcher(Objects.toString(c.displayName(), "")).find())
                .filter(c -> !preferredOnly || !LODGING_DISTRICT.matcher(Objects.toString(c.displayName(), "")).find())
                .min(Comparator.comparingDouble((PlaceCandidate c) -> kmTo(c, center)
                        + lodgingPenaltyKm(c, companion) - 2 * PlaceProminence.of(c)))
                .orElse(null);
    }

    /** 초안이 그날 권역에 적은 동네의 좌표. 도시 이름만 적었거나 다른 도시의 같은 이름이면 null이다. */
    private double[] areaAnchor(DraftPlan.Day day, Request req) {
        if (day == null || day.area() == null) return null;
        String area = day.area();
        for (String city : req.cityNames()) area = area.replace(city, " ");
        area = area.replaceAll("근처|주변|인근|일대|권역|시내|도심", " ").trim();
        if (area.length() < 2) return null;
        DraftPlaceResolver.Resolution r = resolver.resolve(area, "sight", req.input().regions(), req.cityNames(), null);
        double[] at = r.place() == null ? null : DraftPlaceResolver.coords(r.place());
        if (at == null) return null;
        for (Destination c : req.cities()) {
            if (kmBetween(at, new double[]{c.lat(), c.lng()}) <= cityRangeKm(c, CITY_KM)) return at;
        }
        return null;
    }

    private static double[] groupCenter(List<Integer> nights, List<double[]> centers) {
        double lat = 0;
        double lon = 0;
        for (int n : nights) {
            lat += centers.get(n - 1)[0];
            lon += centers.get(n - 1)[1];
        }
        return new double[]{lat / nights.size(), lon / nights.size()};
    }

    private static double kmBetween(double[] a, double[] b) {
        return com.shg.trip.shgtrip.global.util.GeoUtils.haversine(a, b);
    }

    /**
     * 숙소 유형 감점(km 환산). 부모님·가족·커플에게 모텔·호스텔은 몇 km 더 가더라도 피할 숙소다.
     */
    private static double lodgingPenaltyKm(PlaceCandidate c, CompanionType companion) {
        String category = c.category() == null ? "" : c.category().toLowerCase(Locale.ROOT);
        String name = Objects.toString(c.displayName(), "");
        boolean low = category.contains("motel") || category.contains("hostel") || name.contains("모텔")
                || name.contains("여관") || name.contains("여인숙")
                || name.contains("게스트하우스") || category.endsWith("> lodging") || category.equals("lodging");
        if (!low) return 0;
        return companion != null && companion.avoidsDormLodging() ? 8 : 3;
    }

    /** 숙소 위치를 정할 때 쓰는 도시 범위. 좌표가 틀린 장소 하나가 숙소를 도시 밖 들판으로 끌고 간다. */
    private static final double CITY_KM = 25;
    private static final double JEJU_KM = 45;

    private static double[] lodgingCenter(List<DayBuilder.Stop> today, List<DayBuilder.Stop> tomorrow,
                                          double[] anchor, boolean sameCity, Destination city) {
        java.util.function.Predicate<double[]> inCity = c -> c != null && (city == null
                || kmBetween(c, new double[]{city.lat(), city.lng()})
                <= cityRangeKm(city, "제주".equals(city.nameKo()) ? JEJU_KM : CITY_KM));
        double[] end = lastCoords(today, inCity);
        if (end == null) end = inCity.test(anchor) || city == null ? anchor : new double[]{city.lat(), city.lng()};
        if (!sameCity) return end;
        for (DayBuilder.Stop s : tomorrow) {
            if (s.sunrise() && inCity.test(coordsOf(s.place()))) return coordsOf(s.place());
        }
        double[] next = tomorrow.stream().filter(s -> !s.meal()).map(s -> coordsOf(s.place()))
                .filter(inCity).findFirst().orElse(null);
        return next == null ? end : new double[]{(end[0] + next[0]) / 2, (end[1] + next[1]) / 2};
    }

    /** 해외 여행지는 목록의 배정 반경이 더 넓으면 그 반경까지 그 도시다. */
    private static double cityRangeKm(Destination city, double domesticKm) {
        return TripCalendar.isKorea(city.country()) ? domesticKm : Math.max(CITY_KM, city.radiusKm());
    }

    private static double[] lastCoords(List<DayBuilder.Stop> stops, java.util.function.Predicate<double[]> keep) {
        for (int i = stops.size() - 1; i >= 0; i--) {
            double[] c = coordsOf(stops.get(i).place());
            if (keep.test(c)) return c;
        }
        return null;
    }

    /** 끼니·카페가 아닌 항목. 모델이 이름 여부를 false로 적어도 장소를 가리키므로 연결을 시도한다. */
    private static boolean isPlaceKind(DraftPlan.Item item) {
        return item.kind() != null && !"meal".equals(item.kind()) && !"cafe".equals(item.kind());
    }

    /** 다른 표기는 통째로만 찾는다. "めんたいこ"의 앞 두 글자 "めん"은 면 요리 전체에 걸린다. */
    static List<String> dishWords(String dish, List<String> aliases) {
        List<String> out = new ArrayList<>(dishTokens(dish));
        aliases.forEach(a -> out.add(a.toLowerCase(Locale.ROOT)));
        return out.stream().distinct().toList();
    }

    private static List<String> dishTokens(String dish) {
        if (dish == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : dish.split("[\\s,·/]+")) {
            if (t.length() >= 2) out.add(t.toLowerCase(Locale.ROOT));
        }
        // "막창구이"를 파는 곳은 이름이 "○○막창"인 경우가 많아 앞 두 글자로도 찾는다.
        for (String t : List.copyOf(out)) {
            if (t.length() >= 4) out.add(t.substring(0, 2));
        }
        return out;
    }

    private static boolean matchesDish(PlaceCandidate c, List<String> tokens) {
        if (tokens.isEmpty()) return false;
        String menu = c.attributes() == null || c.attributes().official() == null ? ""
                : String.join(" ", c.attributes().official().values());
        String text = String.join(" ", Objects.toString(c.name(), ""), Objects.toString(c.nameKo(), ""),
                Objects.toString(c.cuisine(), ""),
                c.tags() == null ? "" : String.join(" ", c.tags()), menu).toLowerCase(Locale.ROOT);
        return tokens.stream().anyMatch(text::contains);
    }

    private static double kmTo(PlaceCandidate c, double[] at) {
        double[] x = coordsOf(c);
        return x == null || at == null ? Double.MAX_VALUE : com.shg.trip.shgtrip.global.util.GeoUtils.haversine(x, at);
    }

    private static double[] centroid(java.util.Collection<PlaceCandidate> places) {
        double lat = 0;
        double lon = 0;
        int n = 0;
        for (PlaceCandidate p : places) {
            double[] c = coordsOf(p);
            if (c == null) continue;
            lat += c[0];
            lon += c[1];
            n++;
        }
        return n == 0 ? null : new double[]{lat / n, lon / n};
    }

    private static DraftPlan.Day dayOf(List<DraftPlan.Day> days, int number) {
        return days.stream().filter(d -> d.day() == number).findFirst().orElse(null);
    }

    private static boolean slowCompanion(CompanionType companion) {
        return companion == CompanionType.FAMILY || companion == CompanionType.SENIOR;
    }

    private static int minutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    /** 이름표만 있는 식당 행("식사", "맛집"). */
    private static final Set<String> GENERIC_NAMES = Set.of("식사", "맛집", "식당", "카페", "커피", "restaurant", "cafe");

    /**
     * 끼니 장부. 같은 식당을 두 번 쓰지 않고, 같은 날 같은 음식 종류와 여행 전체 세 번째 같은 종류를 뒤로 미룬다.
     * 초안이 쌈밥·구이를 연달아 적어도 근처에 다른 종류가 있으면 그쪽을 고른다.
     */
    private static final class MealLedger {
        private static final int TRIP_KIND_LIMIT = 2;
        private final Set<Long> ids = new HashSet<>();
        private final Map<String, Integer> tripKinds = new HashMap<>();
        private final Map<Integer, Set<String>> dayKinds = new HashMap<>();
        int day;
        /** 끼니를 고르는 날. 그날 쉬는 식당은 고르지 않는다. 모르면 null이다 */
        LocalDate date;

        void on(int day, LocalDate date) {
            this.day = day;
            this.date = date;
        }

        boolean contains(Long id) {
            return id != null && ids.contains(id);
        }

        void add(PlaceCandidate c) {
            if (c.placeId() != null) ids.add(c.placeId());
            String kind = CandidatePolicy.cuisineOf(c);
            if (kind == null) return;
            tripKinds.merge(kind, 1, Integer::sum);
            dayKinds.computeIfAbsent(day, k -> new HashSet<>()).add(kind);
        }

        boolean repeats(PlaceCandidate c) {
            String kind = CandidatePolicy.cuisineOf(c);
            if (kind == null) return false;
            return tripKinds.getOrDefault(kind, 0) >= TRIP_KIND_LIMIT
                    || dayKinds.getOrDefault(day, Set.of()).contains(kind);
        }
    }

    /** 장소를 한 번만 후보로 만들고 index를 목록 위치와 맞춘다. */
    static final class Registry {
        private static final java.util.regex.Pattern BRACKETS = java.util.regex.Pattern.compile("[(\\[（【].*?[)\\]）】]");
        private final Map<Long, PlaceCandidate> byId = new LinkedHashMap<>();
        private final Map<Long, PlaceFactsReader.PlaceFact> facts = new HashMap<>();
        private final PlaceFactsReader factsReader;
        private final List<String> cityNames;
        /** 이 계획의 근처 검색. 끼니·숙소·빈 시간 후보를 찾을 때마다 질의를 다시 임베딩하지 않는다. */
        final VectorSearchQueryService.NearSearch near;

        Registry(PlaceFactsReader factsReader, List<String> cityNames, VectorSearchQueryService.NearSearch near) {
            this.factsReader = factsReader;
            this.cityNames = cityNames == null ? List.of() : cityNames;
            this.near = near;
        }

        PlaceFactsReader.PlaceFact fact(PlaceCandidate c) {
            if (c.placeId() == null) return null;
            if (!facts.containsKey(c.placeId())) {
                facts.put(c.placeId(), factsReader.find(List.of(c.placeId())).get(c.placeId()));
            }
            return facts.get(c.placeId());
        }

        /** 영업시간이 비어 있으면 장소 사실의 휴무 요일을 붙인다. 하루 짜기·옮기기·판정이 모두 영업시간으로 휴무를 본다. */
        PlaceCandidate withClosures(PlaceCandidate c) {
            PlaceFactsReader.PlaceFact f = fact(c);
            if (f == null) return c;
            String hours = OpeningHoursText.withClosedDays(c.openingHours(), f.closedDays());
            return Objects.equals(hours, c.openingHours()) ? c : c.withOpeningHours(hours);
        }

        List<PlaceCandidate> withClosures(Collection<PlaceCandidate> cs) {
            List<Long> missing = cs.stream().map(PlaceCandidate::placeId)
                    .filter(id -> id != null && !facts.containsKey(id)).distinct().toList();
            if (!missing.isEmpty()) {
                Map<Long, PlaceFactsReader.PlaceFact> found = factsReader.find(missing);
                missing.forEach(id -> facts.put(id, found.get(id)));
            }
            return cs.stream().map(this::withClosures).toList();
        }

        /** 같은 곳을 가리키는 이름을 하나로 모은 키. 대표 장소가 있으면 그 이름이고, 괄호·도시 이름은 뗀다. */
        String canonical(PlaceCandidate c) {
            PlaceFactsReader.PlaceFact f = fact(c);
            String name = f != null && f.partOf() != null && !f.partOf().isBlank() ? f.partOf() : c.displayName();
            return DraftPlaceResolver.queryKey(BRACKETS.matcher(Objects.toString(name, "")).replaceAll(" "), cityNames);
        }

        /** @return 넣지 않을 이유. 넣어도 되면 null */
        String whyNotVisit(PlaceCandidate c, Set<String> visited) {
            PlaceFactsReader.PlaceFact f = fact(c);
            if (f != null && Boolean.FALSE.equals(f.reachable())) return "들어갈 수 없는 곳";
            if (f != null && f.notWorthVisiting()) return NOT_A_SIGHT;
            if (visited.contains(canonical(c))) return "이미 간 곳";
            return null;
        }

        PlaceCandidate add(Place place) {
            PlaceCandidate existing = byId.get(place.getId());
            if (existing != null) return existing;
            PlaceCandidate c = withClosures(PlaceCandidates.of(place, byId.size() + 1));
            byId.put(place.getId(), c);
            return c;
        }

        PlaceCandidate add(PlaceCandidate c) {
            if (c.placeId() == null) return c;
            PlaceCandidate existing = byId.get(c.placeId());
            // 초안 이름으로 먼저 잡힌 곳도 사용자가 고른 장소면 제외 판정을 건너뛰어야 한다.
            if (existing != null && c.userSelected() && !existing.userSelected()) {
                existing = existing.asUserSelected();
                byId.put(c.placeId(), existing);
            }
            if (existing != null) return existing;
            PlaceCandidate re = withClosures(c.reindexed(byId.size() + 1));
            byId.put(c.placeId(), re);
            return re;
        }

        List<PlaceCandidate> list() {
            return List.copyOf(byId.values());
        }
    }
}
