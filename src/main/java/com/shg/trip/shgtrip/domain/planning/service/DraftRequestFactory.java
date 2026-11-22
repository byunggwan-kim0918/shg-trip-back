package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.validation.PlaceRegionValidator;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 생성 요청과 입력 보강 결과로 초안 요청을 만든다. 사용자가 화면에서 고른 값(동행·인원·피할 조건·고른 장소·꼭 할 경험)이
 * 문장에서 추론한 값보다 먼저다. 생성과 평가가 같은 변환을 써야 평가한 일정이 운영 일정과 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftRequestFactory {

    /** 직접 적은 장소 이름이 여행 도시에서 이보다 멀면 다른 지역의 같은 이름이다. */
    private static final double CUSTOM_PLACE_MAX_KM = 100.0;
    private static final int CUSTOM_PLACE_SEARCH_LIMIT = 10;

    private final Destinations destinations;
    private final PlaceRepository placeRepository;
    private final PlaceRegionValidator placeRegionValidator;
    private final TripHubs hubs;

    /**
     * @param input   사용자가 고른 값을 반영한 입력
     * @param notices 여행자에게 그대로 보일 안내(운전 불가, 찾지 못한 장소). 편집 뒤에도 남는다
     */
    public record Prepared(DraftItineraryPlanner.Request request, VectorEnrichedInput input, List<String> notices) {
    }

    /**
     * @throws BusinessException 여행 도시를 알 수 없거나 장소가 부족한 여행지, 고른 장소가 여행지 밖이거나 직접 적은
     *                           장소를 하나도 찾지 못한 직접 선택 요청
     */
    public Prepared prepare(ItineraryGenerateRequest request, VectorEnrichedInput enriched) {
        List<String> notices = new ArrayList<>();
        VectorEnrichedInput input = withUserChoices(request, enriched, notices);
        List<Destination> cities = citiesOf(input);
        if (cities.isEmpty()) {
            log.info("여행 도시를 알 수 없음: country={}, regions={}", input.country(), input.regions());
            throw new BusinessException(ErrorCode.INSUFFICIENT_PLACE_DATA);
        }
        requirePlaceData(input);

        List<Place> selected = selectedPlaces(request, notices);
        List<String> unresolved = new ArrayList<>();
        selected.addAll(customPlaces(request, cities, unresolved));
        if (!unresolved.isEmpty()) {
            notices.add(quoted(unresolved) + "은(는) 장소를 찾지 못해 일정에 넣지 못했어요.");
        }
        placeRegionValidator.validate(selected, input);
        selected = collapseSamePlaces(selected);

        List<String> mustDo = new ArrayList<>();
        List<DraftItineraryPlanner.UserWant> wants = new ArrayList<>();
        PlaceCandidate booked = null;
        List<String> extraLodgings = new ArrayList<>();
        for (Place p : selected) {
            PlaceCandidate c = PlaceCandidates.of(p, 0).asUserSelected();
            // 예약 숙소는 하나만 고정한다. 둘째 숙소를 요구로 두면 낮 일정의 방문지가 된다.
            if (CandidatePolicy.roleOf(c) == CandidatePolicy.Role.LODGING) {
                if (booked == null) booked = c;
                else extraLodgings.add(c.displayName());
                continue;
            }
            mustDo.add(c.displayName());
            wants.add(new DraftItineraryPlanner.UserWant(c.displayName(), c.name(), null, c));
        }
        if (!extraLodgings.isEmpty()) {
            notices.add(quoted(extraLodgings) + "은(는) 숙소를 하나만 고정할 수 있어 '" + booked.displayName()
                    + "'에 묵는 일정으로 짰어요.");
        }
        for (ItineraryGenerateRequest.RequiredExperience x : nz(request.requiredExperiences())) {
            String time = timeOf(x.timing(), x.text());
            mustDo.add(x.text() + ("morning".equals(x.timing()) ? " (아침)" : "evening".equals(x.timing()) ? " (저녁)" : ""));
            wants.add(new DraftItineraryPlanner.UserWant(x.text(), x.areaKeyword(), time, null));
        }

        TransportationHub hub = input.transportationHub();
        Place arrivalHub = hub == null ? null : hubs.named(hub.arrivalHub(), cities, input.regions());
        Place departureHub = hub == null ? null : hubs.named(hub.departureHub(), cities, input.regions());
        // 짓는 쪽은 도착 시각이 있어야 도착 허브를, 출발 시각이 있고 차로 돌아가지 않아야 출발 허브를 쓴다.
        Destination lastCity = cities.get(cities.size() - 1);
        boolean drivesHome = "car".equals(input.transportPref()) && !hubs.flies(lastCity);
        if (request.arrivalTime() != null) {
            noticeUnusedHub(hub == null ? null : hub.arrivalHub(), arrivalHub, cities.get(0), cities, "도착", notices);
        }
        if (request.departureTime() != null && !drivesHome) {
            noticeUnusedHub(hub == null ? null : hub.departureHub(), departureHub, lastCity, cities, "출발", notices);
        }
        TripExclusions exclusions = new TripExclusions(PlaceRestrictions.of(input.restrictions()),
                AvoidedPlaces.load(placeRepository, request.avoidPlaceIds()),
                FerryAreaFilter.activeTokens(input.ferryOnlyAreas(), input.description(), request.customPlaceNames(),
                        request.avoidPlaceNames(),
                        token -> addressShare(input.regions(), token)),
                displacedBySelection(selected));
        DraftItineraryPlanner.Request draftRequest = new DraftItineraryPlanner.Request(input, null, cities,
                time(request.arrivalTime()), time(request.departureTime()),
                arrivalHub == null ? null : PlaceCandidates.of(arrivalHub, 0),
                departureHub == null ? null : PlaceCandidates.of(departureHub, 0), mustDo, wants, Map.of(), booked,
                exclusions, Map.of(), Set.of(), request.avoidPlaceNames());
        return new Prepared(draftRequest, input, notices);
    }

    /**
     * 말한 공항·역을 찾지 못했거나 그 도시의 허브가 아니면 짓는 단계가 대표 허브로 바꾼다. 바꾼 사실을 알리지 않으면 여행자는
     * 말한 곳에서 출발하는 일정으로 안다.
     */
    private static void noticeUnusedHub(String named, Place found, Destination city, List<Destination> cities,
                                        String which, List<String> notices) {
        if (named == null || named.isBlank()) return;
        if (found != null && DraftItineraryPlanner.servesCity(PlaceCandidates.of(found, 0), city, cities)) return;
        notices.add("말씀하신 " + which + " '" + named + "'을(를) " + city.nameKo()
                + "에서 찾지 못해 여행지 대표 공항·역 기준으로 짰어요.");
    }

    /** 동행·인원·피할 조건은 화면에서 고른 값을 쓴다. 인원을 안 줬으면 동행에서 정해지는 인원만 쓴다. */
    private static VectorEnrichedInput withUserChoices(ItineraryGenerateRequest request, VectorEnrichedInput enriched,
                                                       List<String> notices) {
        VectorEnrichedInput.CompanionType chosen = VectorEnrichedInput.companionOf(request.companion());
        VectorEnrichedInput input = enriched.withRestrictions(request.restrictions()).withCompanion(chosen,
                request.partySize() != null ? request.partySize() : VectorEnrichedInput.defaultPartySize(chosen));
        if (!"car".equals(input.transportPref()) || input.country() == null) return input;
        String country = input.country().trim().toUpperCase(Locale.ROOT);
        if (!TripFeasibility.drivingNotAllowed(country)) return input;
        notices.add(Destinations.countryNameKo(country) + "은 한국 운전면허로 운전할 수 없어 택시·차량 호출로 다니는 기준으로 짰어요.");
        return input.withTransportPref("any");
    }

    /**
     * 보강이 정한 지역의 도시. 해외는 함께 고른 상위 여행지가 있으면 근교 여행지(오사카와 교토의 교토)를 그 도시의 권역으로
     * 본다. 근교마다 도시로 두면 당일치기한 날마다 숙소를 옮긴다.
     */
    List<Destination> citiesOf(VectorEnrichedInput input) {
        String country = input.country() == null || input.country().isBlank() ? null : input.country().trim();
        List<Destination> found = new ArrayList<>();
        for (String region : nz(input.regions())) {
            Optional<Destination> d = destinations.find(region).or(() -> DomesticCities.find(region));
            if (d.isEmpty() || country != null && !country.equalsIgnoreCase(d.get().country())) continue;
            if (found.stream().noneMatch(f -> f.name().equals(d.get().name()))) found.add(d.get());
        }
        Set<String> names = found.stream().map(Destination::name).collect(Collectors.toSet());
        return found.stream().filter(d -> !hasAncestorIn(d, names)).toList();
    }

    private boolean hasAncestorIn(Destination d, Set<String> names) {
        Set<String> seen = new HashSet<>();
        String parent = d.parent();
        while (parent != null && seen.add(parent)) {
            if (names.contains(parent)) return true;
            parent = destinations.find(parent).map(Destination::parent).orElse(null);
        }
        return false;
    }

    /**
     * 장소가 거의 없는 여행지는 모델이 쓴 이름을 이을 곳이 없어 빈 날이 생긴다. 근교 여행지만 고른 해외 요청은 상위 도시를
     * 함께 고르라고 안내한다.
     */
    private void requirePlaceData(VectorEnrichedInput input) {
        Map<String, Long> roles = new LinkedHashMap<>();
        for (Object[] row : placeRepository.countRolesByRegions(nz(input.regions()))) {
            roles.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        if (TripFeasibility.enoughPlaceData(roles, days)) return;
        log.info("여행지 장소 부족: regions={}, 역할별={}", input.regions(), roles);
        throw new BusinessException(ErrorCode.INSUFFICIENT_PLACE_DATA, sparseMessage(input));
    }

    String sparseMessage(VectorEnrichedInput input) {
        for (String region : nz(input.regions())) {
            Optional<Destination> parent = destinations.find(region).map(Destination::parent).flatMap(destinations::find);
            if (parent.isPresent() && !input.regions().contains(parent.get().name())) {
                String child = destinations.find(region).map(Destination::nameKo).orElse(region);
                return "'" + child + "'만으로는 장소가 적어 일정을 만들기 어려워요. '" + parent.get().nameKo()
                        + "'까지 함께 넣어 다시 요청해 주세요.";
            }
        }
        return ErrorCode.INSUFFICIENT_PLACE_DATA.getMessage();
    }

    private double addressShare(List<String> regions, String token) {
        long all = placeRepository.countActiveByRegions(nz(regions));
        return all == 0 ? 0 : (double) placeRepository.countActiveByRegionsAndAddressContaining(regions, token) / all;
    }

    /** 검색 뒤 삭제된 장소는 이름을 알 수 없어 개수로 알린다. 화면의 임시 장소(음수 id)는 고른 장소가 아니다. */
    private List<Place> selectedPlaces(ItineraryGenerateRequest request, List<String> notices) {
        List<Long> ids = nz(request.selectedPlaceIds()).stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) return new ArrayList<>();
        List<Place> found = new ArrayList<>(placeRepository.findAllById(ids));
        if (found.size() < ids.size()) {
            notices.add("고른 장소 중 " + (ids.size() - found.size()) + "곳은 찾을 수 없어 일정에서 뺐어요.");
        }
        return found;
    }

    /**
     * 직접 적은 장소 이름을 DB에서만 찾는다. 외부 검색 결과는 Maps 약관 3.2.3(a)상 보관할 수 없다. 같은 이름의 다른 지역
     * 가게를 막으려고 여행 도시에 가장 가까운 하나를 쓴다.
     */
    private List<Place> customPlaces(ItineraryGenerateRequest request, List<Destination> cities, List<String> unresolved) {
        List<Place> out = new ArrayList<>();
        for (String raw : nz(request.customPlaceNames())) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim();
            Place best = null;
            double bestKm = CUSTOM_PLACE_MAX_KM;
            for (Destination city : cities) {
                for (Place p : placeRepository.findActiveByNameLikeNearest(name, city.lat(), city.lng(),
                        PageRequest.of(0, CUSTOM_PLACE_SEARCH_LIMIT))) {
                    double km = GeoUtils.haversine(city.lat(), city.lng(), p.getLatitude().doubleValue(),
                            p.getLongitude().doubleValue());
                    if (km < bestKm) {
                        bestKm = km;
                        best = p;
                    }
                }
            }
            if (best == null) unresolved.add(name);
            else out.add(best);
        }
        boolean manual = request.mode() == ItineraryGenerateRequest.PlanningMode.MANUAL;
        if (manual && nz(request.selectedPlaceIds()).isEmpty() && out.isEmpty() && !unresolved.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "직접 입력한 장소를 하나도 찾지 못했습니다. 장소명을 확인하거나 검색으로 선택해주세요.");
        }
        return out;
    }

    /** 같은 곳(SAME)의 여러 행을 함께 골랐으면 한 행만 남긴다. 대표 행을 골랐으면 그 행, 아니면 먼저 고른 행이다. */
    static List<Place> collapseSamePlaces(List<Place> selected) {
        Map<Long, Place> byPlace = new LinkedHashMap<>();
        for (Place p : selected) {
            Long key = Objects.requireNonNullElse(p.samePlaceRepresentativeId(), p.getId());
            Place kept = byPlace.get(key);
            if (kept == null || (key.equals(p.getId()) && !key.equals(kept.getId()))) byPlace.put(key, p);
        }
        if (byPlace.size() == selected.size()) return selected;
        return selected.stream().filter(byPlace::containsValue).toList();
    }

    /** 고른 행이 합쳐져 있는 큰 장소. 함께 넣으면 같은 곳을 두 번 간다. 큰 장소도 함께 골랐으면 빼지 않는다. */
    static Set<Long> displacedBySelection(List<Place> selected) {
        Set<Long> selectedIds = selected.stream().map(Place::getId).collect(Collectors.toSet());
        return selected.stream().map(Place::getMergedIntoId)
                .filter(id -> id != null && !selectedIds.contains(id))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 일출을 말한 아침 경험은 해 뜨기 전에 닿아야 한다. */
    static String timeOf(String timing, String text) {
        if ("morning".equals(timing)) return Objects.toString(text, "").contains("일출") ? "sunrise" : "morning";
        return "evening".equals(timing) ? "evening" : null;
    }

    private static LocalTime time(String hhmm) {
        return hhmm == null || hhmm.isBlank() ? null : LocalTime.parse(hhmm.trim());
    }

    private static String quoted(List<String> names) {
        return names.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
