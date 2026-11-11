package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.service.PlaceService;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
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

/**
 * 대화에서 나온 숙소·장소 이름을 DB 장소에 맞춘다. 생성기가 일정에 넣기를 보장하는 것은 장소 id로 받은 장소뿐이다.
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
        for (DraftField field : List.of(DraftField.MUST_VISIT, DraftField.BOOKED)) {
            for (Item item : TripRequestMapper.userItems(draft, field)) {
                find(item.text(), destination).ifPresent(m -> out.put(key(field, item.text()), m));
            }
        }
        return out;
    }

    java.util.Optional<PlaceMatch> find(String name, String destination) {
        String query = TextNormalizer.key(name);
        if (query.length() < 2 || isGeneric(query)) return java.util.Optional.empty();
        try {
            for (PlaceResponse p : placeService.searchPlaces(name.strip(), PageRequest.of(0, SEARCH_SIZE))) {
                if (!sameName(TextNormalizer.key(p.name()), query)
                        && !sameName(TextNormalizer.key(p.nameKo()), query)) continue;
                if (destination != null && !matchesDestination(p, destination)) continue;
                String shown = p.nameKo() != null && !p.nameKo().isBlank() ? p.nameKo() : p.name();
                return java.util.Optional.of(new PlaceMatch(p.id(), shown,
                        PlaceCategoryConstants.majorCategory(p.category())));
            }
        } catch (Exception e) {
            log.warn("대화 장소 매칭 조회 실패: {}", e.getMessage());
        }
        return java.util.Optional.empty();
    }

    /** "광안리"→"광안리해수욕장", "블루라인파크"→"해운대블루라인파크"는 같은 곳으로 본다. 두 글자 이름은 완전 일치만. */
    static boolean sameName(String placeName, String query) {
        if (placeName.equals(query)) return true;
        return query.length() >= MIN_CONTAINED_NAME && placeName.contains(query);
    }

    static boolean isGeneric(String query) {
        for (String suffix : GENERIC_SUFFIXES) {
            if (query.endsWith(suffix) && query.length() - suffix.length() < 2) return true;
        }
        return false;
    }

    /** 해외 여행지는 주소가 현지어라 이름 대신 장소 region이 같은 여행지 묶음(교토·나라는 오사카)인지 본다. */
    boolean matchesDestination(PlaceResponse p, String destination) {
        java.util.Optional<Destination> overseas = destinations.resolve(destination);
        if (overseas.isEmpty()) return inDestination(p.address(), destination);
        return p.region() != null
                && destinations.rootOf(p.region()).equals(destinations.rootOf(overseas.get().name()));
    }

    /** 주소에 여행지 이름이 있어야 한다. "제주도"·"부산시"처럼 붙은 행정 단위는 떼고 본다. */
    static boolean inDestination(String address, String destination) {
        if (address == null) return false;
        String d = TextNormalizer.key(destination);
        if (d.length() > 2) d = d.replaceAll("(특별자치도|특별자치시|특별시|광역시|도|시|군)$", "");
        return !d.isEmpty() && TextNormalizer.key(address).contains(d);
    }
}
