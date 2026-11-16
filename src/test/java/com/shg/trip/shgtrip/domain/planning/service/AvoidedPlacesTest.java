package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvoidedPlacesTest {

    private final PlaceRepository placeRepository = mock(PlaceRepository.class);

    private static Place.PlaceBuilder busan(long id, String name, String category, double lat, double lng) {
        return Place.builder().id(id).name(name).region("Busan").country("KR").category(category)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng));
    }

    private static PlaceCandidate candidate(long placeId, String name, String nameKo, String category, String region,
                                            double lat, double lng, Integer sitelinks) {
        return new PlaceCandidate(0, placeId, name, "주소", category, List.of(), region, "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, null,
                false, null, null, null, sitelinks, null, null, false, null, null, nameKo, null);
    }

    @Test
    @DisplayName("같은 Wikidata 항목의 출처별 행은 관광지끼리만 더하고, 가게에 붙은 항목 id는 믿지 않으며, 교통 거점은 요청해도 빼지 않는다")
    void loadsSameItemRowsOfSameMajor() {
        String sight = "Landmarks and Outdoors > Historic Site";
        Place gamcheon = busan(9L, "감천문화마을", sight, 35.0975, 129.0106)
                .wikidataId("Q12583085").wikidataSitelinks(5).build();
        Place kiyomizu = Place.builder().id(20L).name("清水寺").nameKo("기요미즈데라").region("Kyoto").country("JP")
                .category("Landmarks and Outdoors > Temple")
                .latitude(BigDecimal.valueOf(34.9949)).longitude(BigDecimal.valueOf(135.7850)).build();
        Place sameItem = busan(10L, "Gamcheon Culture Village", sight, 35.0990, 129.0080)
                .wikidataId("Q12583085").wikidataSitelinks(5).build();
        Place guesthouse = busan(11L, "감천 게스트하우스", "Travel and Transportation > Lodging > Hostel", 35.0960, 129.0120)
                .wikidataId("Q12583085").wikidataSitelinks(5).build();
        Place busStop = busan(12L, "감천문화마을", "Travel and Transportation > Transport Hub > Bus Station",
                35.0970, 129.0100).wikidataId("Q12583085").wikidataSitelinks(5).build();
        Place station = busan(3L, "부산역", "Travel and Transportation > Transport Hub > Train Station", 35.1151, 129.0414)
                .build();
        Place diner = busan(30L, "무의도 쌈밥", "Dining and Drinking > Restaurant", 35.0970, 129.0110)
                .wikidataId("Q999").wikidataSitelinks(4).build();
        Place island = busan(31L, "무의도", "Landmarks and Outdoors > Island", 35.0900, 129.0200)
                .wikidataId("Q999").wikidataSitelinks(4).build();
        when(placeRepository.findAllById(List.of(9L, 20L, 3L, 30L))).thenReturn(List.of(gamcheon, kiyomizu, station, diner));
        List<Place> itemRows = List.of(gamcheon, sameItem, guesthouse, busStop, diner, island);
        when(placeRepository.findActiveByWikidataIds(any(), any())).thenAnswer(inv -> {
            Collection<String> qids = inv.getArgument(0);
            Collection<String> regions = inv.getArgument(1);
            return itemRows.stream()
                    .filter(p -> qids.contains(p.getWikidataId()) && regions.contains(p.getRegion())).toList();
        });

        AvoidedPlaces avoided = AvoidedPlaces.load(placeRepository, Arrays.asList(9L, null, 20L, 9L, 3L, 30L));

        assertThat(avoided.places()).extracting(PlaceCandidate::placeId).containsExactly(9L, 20L, 30L, 10L);
    }

    @Test
    @DisplayName("같은 곳으로 합쳐진 행을 피하면 후보에 나오는 대표 행도 피하고, 딸린 일부를 피한다고 큰 장소까지 피하지는 않는다")
    void avoidsRepresentativeOfSamePlaceOnly() {
        String sight = "Landmarks and Outdoors > Scenic Lookout";
        Place diamond = busan(1734764L, "부산다이아몬드타워", sight, 35.1009, 129.0323)
                .mergedIntoId(308559L).mergeRelation("SAME").build();
        Place tower = busan(308559L, "부산타워", sight, 35.1012, 129.0324).build();
        Place gate = busan(8L, "범어사 조계문", "Landmarks and Outdoors > Historic Site", 35.2840, 129.0680)
                .mergedIntoId(7L).mergeRelation("PART").build();
        when(placeRepository.findAllById(List.of(1734764L, 8L))).thenReturn(List.of(diamond, gate));
        when(placeRepository.findAllById(List.of(308559L))).thenReturn(List.of(tower));

        AvoidedPlaces avoided = AvoidedPlaces.load(placeRepository, List.of(1734764L, 8L));

        assertThat(avoided.places()).extracting(PlaceCandidate::placeId).containsExactly(1734764L, 8L, 308559L);
        verify(placeRepository, never()).findAllById(List.of(7L));
    }

    @Test
    @DisplayName("같은 항목 id의 다른 이름 관광지(별칭)도 더한다")
    void addsAliasesOfSameItem() {
        String sight = "Landmarks and Outdoors > Tower";
        Place nTower = Place.builder().id(1666706L).name("N서울타워").region("Seoul").country("KR").category(sight)
                .latitude(BigDecimal.valueOf(37.5512)).longitude(BigDecimal.valueOf(126.9882))
                .wikidataId("Q69134").wikidataSitelinks(40).build();
        Place namsanTower = Place.builder().id(1654126L).name("남산서울타워").region("Seoul").country("KR")
                .category(sight).latitude(BigDecimal.valueOf(37.5515)).longitude(BigDecimal.valueOf(126.9885))
                .wikidataId("Q69134").wikidataSitelinks(40).build();
        when(placeRepository.findAllById(List.of(1666706L))).thenReturn(List.of(nTower));
        when(placeRepository.findActiveByWikidataIds(any(), any())).thenReturn(List.of(nTower, namsanTower));

        AvoidedPlaces avoided = AvoidedPlaces.load(placeRepository, List.of(1666706L));

        assertThat(avoided.places()).extracting(PlaceCandidate::placeId).containsExactly(1666706L, 1654126L);
    }

    @Test
    @DisplayName("피할 장소의 출처별 행과 한국어 이름이 같은 행은 빼고, 다른 지역·다른 장소·사용자가 고른 장소는 남긴다")
    void excludesTwinsAndKeepsOthers() {
        String sight = "Landmarks and Outdoors > Historic Site";
        AvoidedPlaces avoided = AvoidedPlaces.of(List.of(
                candidate(9L, "감천문화마을", null, sight, "Busan", 35.0975, 129.0106, 5),
                candidate(10L, "Gamcheon Culture Village", null, sight, "Busan", 35.0990, 129.0080, 5),
                candidate(20L, "清水寺", "기요미즈데라", "Landmarks and Outdoors > Temple", "Kyoto", 34.9949, 135.7850,
                        null)));

        List<PlaceCandidate> kept = avoided.exclude(List.of(
                candidate(101L, "감천문화마을", null, sight, "Busan", 35.0950, 129.0130, null),
                candidate(102L, "Gamcheon Culture Village", null, sight, "Busan", 35.0990, 129.0080, 5),
                candidate(103L, "Kiyomizu-dera", "기요미즈데라", sight, "Kyoto", 34.9940, 135.7860, null),
                candidate(104L, "감천문화마을", null, sight, "Seoul", 37.5, 127.0, null),
                candidate(105L, "태종대", null, sight, "Busan", 35.0533, 129.0870, 8),
                candidate(106L, "감천문화마을", null, sight, "Busan", 35.0950, 129.0130, null).asUserSelected()));

        assertThat(kept).extracting(PlaceCandidate::placeId).containsExactly(104L, 105L, 106L);
    }

    @Test
    @DisplayName("피할 장소가 없으면 DB를 조회하지 않고 후보를 그대로 둔다")
    void noneKeepsCandidates() {
        AvoidedPlaces avoided = AvoidedPlaces.load(placeRepository, Arrays.asList(null, null));
        List<PlaceCandidate> pool = List.of(candidate(1L, "태종대", null, "Landmarks", "Busan", 35.05, 129.08, 8));

        assertThat(avoided).isSameAs(AvoidedPlaces.NONE);
        assertThat(avoided.exclude(pool)).isSameAs(pool);
        verify(placeRepository, never()).findAllById(any());
    }
}
