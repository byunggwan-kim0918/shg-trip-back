package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 여행의 처음과 끝에 들르는 공항·역. 국내는 도시마다 주로 오가는 역·공항이고, 해외는 여행지 목록의 관문 공항이다.
 */
@Component
@RequiredArgsConstructor
class TripHubs {

    /** 요청에 허브가 없을 때 첫날 출발점과 마지막 날 도착점으로 쓴다. */
    private static final Map<String, String> DOMESTIC_HUBS = Map.ofEntries(
            Map.entry("서울", "서울역"), Map.entry("부산", "부산역"), Map.entry("제주", "제주국제공항"),
            Map.entry("경주", "신경주역"), Map.entry("강릉", "강릉역"), Map.entry("전주", "전주역"),
            Map.entry("여수", "여수엑스포역"), Map.entry("대구", "동대구역"), Map.entry("대전", "대전역"),
            Map.entry("광주", "광주송정역"), Map.entry("수원", "수원역"), Map.entry("인천", "인천역"));

    /** 허브 도착에서 출발까지 남길 시간. 공항은 탑승 수속·렌터카 반납, 역·터미널은 표를 찾고 타는 시간이다. */
    static final int AIRPORT_BUFFER = 90;
    static final int STATION_BUFFER = 20;
    /** 마지막 장소에서 출발 허브까지 가는 몫. 짓는 쪽과 검사 쪽이 같은 값으로 그날 끝을 잡아야 한다. */
    static final int HUB_TRAVEL_MINUTES = 30;
    /** 직선거리로 추정한 이동은 막힘·환승 대기로 늘어난다. 놓치면 안 되는 출발편 앞 구간에만 이 비율만큼 더 남긴다. */
    private static final double ESTIMATED_LEG_SLACK_SHARE = 0.25;
    private static final int MIN_ESTIMATED_LEG_SLACK = 10;

    private final DraftPlaceResolver resolver;
    private final PlaceRepository placeRepository;
    private final Destinations destinations;

    /** 그 도시를 비행기로 오가는지. 해외는 늘 관문 공항으로 들고 난다. */
    boolean flies(Destination city) {
        if (!TripCalendar.isKorea(city.country())) return true;
        return Objects.toString(DOMESTIC_HUBS.get(city.nameKo()), "").contains("공항");
    }

    /** 그 도시의 주 허브. 모르면 null. */
    Place mainHub(Destination city, Collection<String> regions) {
        if (!TripCalendar.isKorea(city.country())) return gateways(city).stream().findFirst().orElse(null);
        String name = DOMESTIC_HUBS.get(city.nameKo());
        if (name == null) return null;
        return resolver.resolve(name, "transit", regions, List.of(), new double[]{city.lat(), city.lng()}).place();
    }

    /** 요청이 이름으로 정한 허브(김포공항, 나리타). 여행 도시의 허브로 찾지 못하면 null. */
    Place named(String name, List<Destination> cities, Collection<String> regions) {
        if (name == null || name.isBlank() || cities.isEmpty()) return null;
        if (!TripCalendar.isKorea(cities.get(0).country())) {
            String key = PlaceNames.exactKey(name);
            return cities.stream().flatMap(c -> gateways(c).stream())
                    .filter(g -> PlaceNames.exactKey(g.getName()).contains(key)
                            || key.contains(PlaceNames.exactKey(g.getName()))
                            || g.getNameKo() != null && PlaceNames.exactKey(g.getNameKo()).contains(key))
                    .findFirst().orElse(null);
        }
        Destination first = cities.get(0);
        return resolver.resolve(name, "transit", regions, cities.stream().map(Destination::nameKo).toList(),
                new double[]{first.lat(), first.lng()}).place();
    }

    /**
     * 목록 순서의 관문 공항. 목록에서 뺀 관문(국내선 전용 공항·역)도 DB 행은 남아 있어 목록에 있는 이름만 쓴다.
     * 관문이 없는 근교 여행지는 상위 여행지의 관문이다.
     */
    private List<Place> gateways(Destination city) {
        String region = destinations.gatewayRegion(city.name());
        Optional<Destination> listed = destinations.find(region);
        if (listed.isEmpty()) return List.of();
        List<Place> rows = placeRepository.findGatewaysByRegions(List.of(region));
        return listed.get().gateways().stream()
                .flatMap(g -> rows.stream().filter(p -> g.name().equals(p.getName())).limit(1))
                .toList();
    }

    static boolean isAirport(PlaceCandidate hub) {
        if (hub == null) return false;
        String text = (Objects.toString(hub.name(), "") + " " + Objects.toString(hub.nameKo(), "") + " "
                + Objects.toString(hub.category(), "")).toLowerCase(Locale.ROOT);
        return text.contains("공항") || text.contains("airport");
    }

    /** 출발 허브에 닿아야 하는 시각이 출발보다 앞서는 분. 허브를 모르면 공항으로 본다. */
    static int departureBuffer(PlaceCandidate hub, String country) {
        return departureBuffer(hub == null || isAirport(hub), country);
    }

    /**
     * 출발 허브에 닿아야 하는 시각(분). 차로 돌아가면 출발 시각이다. 짓는 쪽과 검사가 함께 써야 시각을 지킨 일정이
     * 출발 초과로 판정되지 않는다.
     *
     * @param hub 출발 허브. 모르면 null이고 공항으로 본다
     */
    static int departureDeadline(LocalTime departure, PlaceCandidate hub, boolean drivesHome, String country) {
        int at = departure.getHour() * 60 + departure.getMinute();
        return drivesHome ? at : at - departureBuffer(hub, country);
    }

    /**
     * 추정한 이동 뒤에 출발 허브 기한이 있으면 더 남길 분. 짓는 쪽과 검사가 같은 값을 써야 여유를 둔 일정이 출발 초과로
     * 판정되지 않는다. TODO: 실제 경로 시간을 쓰는 구간은 여유를 두지 않는다.
     *
     * @param legMinutes 추정한 이동 분. 모르면 null
     */
    static int estimatedLegSlack(Integer legMinutes) {
        int leg = legMinutes == null ? HUB_TRAVEL_MINUTES : legMinutes;
        return Math.max(MIN_ESTIMATED_LEG_SLACK, (int) Math.ceil(leg * ESTIMATED_LEG_SLACK_SHARE));
    }

    /** 국제선은 출국 수속에 국내선보다 오래 걸린다. */
    static int departureBuffer(boolean airport, String country) {
        if (!airport) return STATION_BUFFER;
        return TripCalendar.isKorea(country) ? AIRPORT_BUFFER : ScheduleTuning.OVERSEAS_DEPARTURE_CHECKIN_BUFFER_MINUTES;
    }
}
