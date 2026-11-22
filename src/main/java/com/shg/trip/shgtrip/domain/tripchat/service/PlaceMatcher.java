package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.place.service.PlaceService;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import com.shg.trip.shgtrip.domain.planning.service.PlaceIdentity;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 대화에서 나온 숙소·장소 이름을 DB 장소에 맞춘다. 생성기가 일정에 넣거나 빼기를 보장하는 것은 장소 id로 받은 장소뿐이다.
 * 애매하면 맞추지 않는다. 엉뚱한 장소가 고정되는 것보다 참고로 남는 편이 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceMatcher {

    private static final int SEARCH_SIZE = 5;
    private static final int MIN_CONTAINED_NAME = 3;
    /** 이것만 남는 이름("야시장", "박물관")은 특정 장소가 아니다. "동문시장"·"국립중앙박물관"은 특정 장소다. */
    private static final List<String> GENERIC_SUFFIXES = List.of(
            "야시장", "시장", "박물관", "미술관", "해수욕장", "해변", "바다", "공원", "카페", "맛집", "식당", "호텔",
            "숙소", "전망대", "투어", "체험", "온천", "스파", "사찰", "성당", "거리", "골목", "케이블카");

    private final PlaceService placeService;
    private final Destinations destinations;
    private final PlaceFactsReader factsReader;

    /** {@link #suggestions}에서 여행지 밖에는 있는 장소의 표시. */
    public static final String ELSEWHERE = "";

    public static String key(DraftField field, String text) {
        return field.name() + "|" + TextNormalizer.key(text);
    }

    /** 다시 맞출지 판단하는 값. 여행지나 정해둔 것이 바뀔 때만 DB를 조회한다. */
    public static String signature(Draft draft) {
        List<String> parts = new ArrayList<>();
        parts.add(String.valueOf(draft.value(DraftField.DESTINATION)));
        parts.add(String.valueOf(TripRequestMapper.userValue(draft, DraftField.LODGING_STATE)));
        parts.add(String.valueOf(TripRequestMapper.userValue(draft, DraftField.LODGING)));
        TripRequestMapper.userItems(draft, DraftField.MUST_VISIT).forEach(i -> parts.add("m:" + i.text()));
        TripRequestMapper.userItems(draft, DraftField.BOOKED).forEach(i -> parts.add("b:" + i.text()));
        TripRequestMapper.userItems(draft, DraftField.AVOID_PLACES).forEach(i -> parts.add("a:" + i.text()));
        return String.join("|", parts);
    }

    /** 사용자가 말한 이름만 맞춘다. 모델이 추론한 장소는 일정에 고정하지 않는다. */
    public Map<String, PlaceMatch> match(Draft draft) {
        Map<String, PlaceMatch> out = new LinkedHashMap<>();
        String destination = draft.value(DraftField.DESTINATION);
        String lodging = TripRequestMapper.userValue(draft, DraftField.LODGING);
        if (lodging != null && "booked".equals(TripRequestMapper.userValue(draft, DraftField.LODGING_STATE))) {
            find(lodging, destination).ifPresent(m -> out.put(key(DraftField.LODGING, lodging), m));
        }
        for (DraftField field : List.of(DraftField.MUST_VISIT, DraftField.BOOKED, DraftField.AVOID_PLACES)) {
            for (Item item : TripRequestMapper.userItems(draft, field)) {
                find(item.text(), destination, field == DraftField.AVOID_PLACES)
                        .ifPresent(m -> out.put(key(field, item.text()), m));
            }
        }
        Map<Long, PlaceFactsReader.PlaceFact> facts = factsReader.find(out.values().stream()
                .map(PlaceMatch::placeId).filter(java.util.Objects::nonNull).distinct().toList());
        out.replaceAll((k, m) -> new PlaceMatch(m.placeId(), m.name(), m.major(), m.openingHours(),
                facts.containsKey(m.placeId()) ? facts.get(m.placeId()).closedDays() : null));
        return out;
    }

    /**
     * 꼭 가고 싶다고 했는데 데이터에서 찾지 못한 곳마다 여행지 안에서 이름이 가장 비슷한 관광지. 다른 도시에서는
     * 찾아지는 곳은 데이터에 없는 것이 아니라 여행지와 맞지 않는 것이라 {@link #ELSEWHERE}로 표시한다.
     *
     * @param matched {@link #match} 결과
     */
    public Map<String, String> suggestions(Draft draft, Map<String, PlaceMatch> matched) {
        Map<String, String> out = new LinkedHashMap<>();
        String destination = draft.value(DraftField.DESTINATION);
        Set<String> regions = destination == null ? Set.of() : destinationRegions(destination);
        if (regions.isEmpty()) return out;
        for (Item item : TripRequestMapper.userItems(draft, DraftField.MUST_VISIT)) {
            String k = key(DraftField.MUST_VISIT, item.text());
            if (matched.containsKey(k)) continue;
            if (find(item.text(), null).isPresent()) {
                out.put(k, ELSEWHERE);
                continue;
            }
            try {
                placeService.searchPlacesInRegions(item.text().strip(), regions, SEARCH_SIZE).stream()
                        .filter(p -> "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(p.category())))
                        .findFirst()
                        .ifPresent(p -> out.put(k, p.nameKo() != null && !p.nameKo().isBlank() ? p.nameKo() : p.name()));
            } catch (Exception e) {
                log.warn("비슷한 장소 찾기 실패: {}", e.getMessage());
            }
        }
        return out;
    }

    java.util.Optional<PlaceMatch> find(String name, String destination) {
        return find(name, destination, false);
    }

    /**
     * 같은 이름이 여러 도시에 있으면 전국 검색 상위에 여행지 행이 들지 않아 여행지 지역 안에서 먼저 찾는다.
     *
     * @param avoiding 지역 이름("해운대")이 같은 이름 가게나 이름 일부가 겹치는 가게에 맞춰지면 안내와 결과가 어긋난다.
     */
    java.util.Optional<PlaceMatch> find(String name, String destination, boolean avoiding) {
        PlaceMatch nonSight = null;
        for (String variant : PlaceIdentity.nameVariants(name)) {
            java.util.Optional<PlaceMatch> found = findExactly(variant, destination, avoiding);
            if (found.isEmpty()) continue;
            // "송정해변"이라고 하면 같은 이름 카페보다 별칭 이름의 해수욕장 행이 맞다.
            if ("ATTRACTION".equals(found.get().major())) return found;
            if (nonSight == null) nonSight = found.get();
        }
        return java.util.Optional.ofNullable(nonSight);
    }

    /** 이름이 짧거나 총칭어("카페")면 찾지 않는다. 찾지 않은 이름을 데이터에 없다고 말하면 안 된다. */
    static boolean searchable(String name) {
        String query = TextNormalizer.key(name);
        return query.length() >= 2 && !isGeneric(query);
    }

    /** 조회가 실패하면 예외를 그대로 낸다. 빈 결과로 바꾸면 일시 장애를 데이터에 없다고 판정한다. */
    private java.util.Optional<PlaceMatch> findExactly(String name, String destination, boolean avoiding) {
        String query = TextNormalizer.key(name);
        if (!searchable(name)) return java.util.Optional.empty();
        Set<String> regions = destination == null ? Set.of() : destinationRegions(destination);
        if (!regions.isEmpty()) {
            java.util.Optional<PlaceMatch> inRegion = pick(
                    placeService.searchPlacesInRegions(name.strip(), regions, SEARCH_SIZE), query, null, avoiding);
            if (inRegion.isPresent()) return inRegion;
        }
        return pick(placeService.searchPlaces(name.strip(), PageRequest.of(0, SEARCH_SIZE)).getContent(),
                query, destination, avoiding);
    }

    private java.util.Optional<PlaceMatch> pick(List<PlaceResponse> results, String query, String destination,
                                                boolean avoiding) {
        PlaceMatch nonSightMatch = null;
        for (PlaceResponse p : results) {
            String placeName = TextNormalizer.key(p.name());
            String placeNameKo = TextNormalizer.key(p.nameKo());
            if (!sameName(placeName, query) && !sameName(placeNameKo, query)) continue;
            String major = PlaceCategoryConstants.majorCategory(p.category());
            boolean exact = isExact(p.name(), p.region(), query) || isExact(p.nameKo(), p.region(), query);
            boolean kindSuffix = "ATTRACTION".equals(major)
                    && (endsWithKind(placeName, query) || endsWithKind(placeNameKo, query));
            if (avoiding && !exact && !kindSuffix) continue;
            if (destination != null && !matchesDestination(p, destination)) continue;
            String shown = p.nameKo() != null && !p.nameKo().isBlank() ? p.nameKo() : p.name();
            PlaceMatch match = new PlaceMatch(p.id(), shown, major, p.openingHours(), null);
            if (avoiding && !"ATTRACTION".equals(major)) {
                if (nonSightMatch == null) nonSightMatch = match;
                continue;
            }
            return java.util.Optional.of(match);
        }
        return java.util.Optional.ofNullable(nonSightMatch);
    }

    /** "용지봉 (634m)"·"강릉 경포대"도 "용지봉"·"경포대"와 같은 이름이다. */
    private static boolean isExact(String placeName, String region, String query) {
        if (placeName == null || placeName.isBlank()) return false;
        return TextNormalizer.key(placeName).equals(query)
                || TextNormalizer.key(PlaceIdentity.bareName(placeName, region)).equals(query);
    }

    /** "광안리"→"광안리해수욕장", "블루라인파크"→"해운대블루라인파크"는 같은 곳으로 본다. 두 글자 이름은 완전 일치만. */
    static boolean sameName(String placeName, String query) {
        if (placeName.equals(query)) return true;
        return query.length() >= MIN_CONTAINED_NAME && placeName.contains(query);
    }

    private static boolean endsWithKind(String placeName, String query) {
        return placeName.startsWith(query) && GENERIC_SUFFIXES.contains(placeName.substring(query.length()));
    }

    static boolean isGeneric(String query) {
        for (String suffix : GENERIC_SUFFIXES) {
            if (query.endsWith(suffix) && query.length() - suffix.length() < 2) return true;
        }
        return false;
    }

    /** 여러 도시를 적은 여행지("서울, 부산", "강릉 갔다가 경주")는 그중 한 곳에 있으면 된다. */
    boolean matchesDestination(PlaceResponse p, String destination) {
        if (matchesOne(p, destination)) return true;
        return destinationParts(destination).anyMatch(d -> matchesOne(p, d));
    }

    /** 여행지가 가리키는 장소 region. 해외는 묶음 전체(오사카면 교토·나라까지)다. 모르는 이름뿐이면 비어 있다. */
    Set<String> destinationRegions(String destination) {
        Set<String> regions = new java.util.LinkedHashSet<>();
        java.util.stream.Stream.concat(java.util.stream.Stream.of(destination), destinationParts(destination))
                .forEach(d -> {
                    DomesticCities.find(destinationKey(d)).ifPresent(city -> regions.add(city.name()));
                    destinations.resolve(d).ifPresent(overseas -> {
                        String root = destinations.rootOf(overseas.name());
                        destinations.all().stream().map(Destination::name)
                                .filter(n -> destinations.rootOf(n).equals(root))
                                .forEach(regions::add);
                    });
                });
        return regions;
    }

    private static java.util.stream.Stream<String> destinationParts(String destination) {
        return java.util.stream.Stream.concat(
                        DESTINATION_SEPARATOR.splitAsStream(destination).map(String::strip),
                        DomesticCities.all().stream().map(Destination::nameKo).filter(destination::contains))
                .filter(d -> !d.isEmpty() && !d.equals(destination));
    }

    /** 해외 여행지는 주소가 현지어라 이름 대신 장소 region이 같은 여행지 묶음(교토·나라는 오사카)인지 본다. */
    private boolean matchesOne(PlaceResponse p, String destination) {
        java.util.Optional<Destination> overseas = destinations.resolve(destination);
        if (overseas.isEmpty()) return inDestination(p.address(), destination);
        return p.region() != null
                && destinations.rootOf(p.region()).equals(destinations.rootOf(overseas.get().name()));
    }

    /** 조사는 뒤에 공백이 올 때만 구분자로 본다. 붙여 쓴 이름("과천")을 덜 자르기 위해서다. */
    private static final java.util.regex.Pattern DESTINATION_SEPARATOR =
            java.util.regex.Pattern.compile("[,·/&→~]|(?:이랑|랑|하고|와|과|및|그리고)\\s+");

    /** 주소에 여행지 이름이 있어야 한다. "제주도"·"부산시"처럼 붙은 행정 단위는 떼고 본다. */
    static boolean inDestination(String address, String destination) {
        if (address == null) return false;
        String d = destinationKey(destination);
        return !d.isEmpty() && TextNormalizer.key(address).contains(d);
    }

    static String destinationKey(String destination) {
        String d = TextNormalizer.key(destination);
        return d.length() > 2 ? d.replaceAll("(특별자치도|특별자치시|특별시|광역시|도|시|군)$", "") : d;
    }
}
