package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.place.service.PlaceService;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceMatcherTest {

    private static PlaceResponse place(long id, String name, String address, String category) {
        return new PlaceResponse(id, name, address, null, null, category, null, null, null, null, null, null, null, null,
                null, null, null, false);
    }

    private static PlaceResponse inRegion(long id, String name, String address, String region, String category) {
        return new PlaceResponse(id, name, address, null, null, category, null, null, null, null, null, region, null,
                null, null, null, null, false);
    }

    private static PlaceResponse overseas(long id, String name, String nameKo, String region, String category) {
        return new PlaceResponse(id, name, "東京都", null, null, category, null, null, null, null, null, region, null,
                nameKo, null, null, null, false);
    }

    @Test
    @DisplayName("이름이 포함되고 여행지 주소인 장소만 맞춘다")
    void matchesByNameAndDestination() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(eq("블루라인파크"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                place(1, "블루라인파크 카페", "강원특별자치도 강릉시", "Dining and Drinking > Cafe"),
                place(2, "해운대 블루라인파크", "부산광역시 해운대구 달맞이길", "Landmarks and Outdoors > Park"))));
        when(placeService.searchPlaces(eq("파라다이스 호텔"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                place(3, "파라다이스 호텔 부산", "부산광역시 해운대구", "Travel and Transportation > Lodging > Hotel"))));

        Map<String, PlaceMatch> matches = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class)).match(Events.of()
                .user(DraftField.DESTINATION, "부산")
                .user(DraftField.MUST_VISIT, "블루라인파크")
                .user(DraftField.LODGING_STATE, "booked")
                .user(DraftField.LODGING, "파라다이스 호텔").draft());

        assertThat(matches.get(PlaceMatcher.key(DraftField.MUST_VISIT, "블루라인파크")).placeId()).isEqualTo(2L);
        PlaceMatch lodging = matches.get(PlaceMatcher.key(DraftField.LODGING, "파라다이스 호텔"));
        assertThat(lodging.placeId()).isEqualTo(3L);
        assertThat(lodging.major()).isEqualTo("LODGING");
    }

    @Test
    @DisplayName("피할 장소도 같은 기준으로 맞추고, 바뀌면 다시 맞춘다")
    void matchesAvoidPlaces() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(any(), any(Pageable.class))).thenReturn(Page.empty());
        when(placeService.searchPlacesInRegions(eq("감천문화마을"), eq(Set.of("Busan")), anyInt())).thenReturn(List.of(
                place(4, "감천문화마을", "부산광역시 사하구 감내2로", "Landmarks and Outdoors > Historic Site")));
        Events trip = Events.of().user(DraftField.DESTINATION, "부산");
        String before = PlaceMatcher.signature(trip.draft());

        Events avoiding = trip.user(DraftField.AVOID_PLACES, "감천문화마을");
        Map<String, PlaceMatch> matches = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class))
                .match(avoiding.draft());

        assertThat(matches.get(PlaceMatcher.key(DraftField.AVOID_PLACES, "감천문화마을")).placeId()).isEqualTo(4L);
        assertThat(PlaceMatcher.signature(avoiding.draft())).isNotEqualTo(before);
    }

    @Test
    @DisplayName("이름이 같거나 남는 부분이 종류 이름인 관광지를 같은 이름 가게보다 먼저 받고, 피할 장소는 그것만 받는다")
    void avoidPlacePartialNameOnlyForKindSuffix() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(any(), any(Pageable.class))).thenReturn(Page.empty());
        when(placeService.searchPlacesInRegions(eq("남포동"), eq(Set.of("Busan")), anyInt())).thenReturn(List.of(
                place(5, "남포동 바다횟집", "부산광역시 중구 남포동", "Dining and Drinking > Restaurant"),
                place(7, "남포동 볼링장", "부산광역시 중구 남포동", "Sports and Recreation > Bowling Alley")));
        when(placeService.searchPlacesInRegions(eq("광안리"), eq(Set.of("Busan")), anyInt())).thenReturn(List.of(
                place(6, "광안리해수욕장", "부산광역시 수영구 광안해변로", "Landmarks and Outdoors > Beach")));
        when(placeService.searchPlacesInRegions(eq("해운대"), eq(Set.of("Busan")), anyInt())).thenReturn(List.of(
                place(9, "해운대", "부산광역시 해운대구 중동", "Dining and Drinking > Restaurant"),
                place(10, "해운대해수욕장", "부산광역시 해운대구 우동", "Landmarks and Outdoors > Beach")));
        when(placeService.searchPlacesInRegions(eq("할매국밥"), eq(Set.of("Busan")), anyInt())).thenReturn(List.of(
                place(8, "할매국밥", "부산광역시 동구 범일동", "Dining and Drinking > Restaurant")));
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.find("남포동", "부산", true)).isEmpty();
        assertThat(matcher.find("남포동", "부산", false)).isPresent();
        assertThat(matcher.find("광안리", "부산", true)).get().extracting(PlaceMatch::placeId).isEqualTo(6L);
        assertThat(matcher.find("할매국밥", "부산", true)).get().extracting(PlaceMatch::placeId).isEqualTo(8L);
        assertThat(matcher.find("해운대", "부산", true)).get().extracting(PlaceMatch::placeId).isEqualTo(10L);
        assertThat(matcher.find("해운대", "부산", false)).get().extracting(PlaceMatch::placeId).isEqualTo(10L);
        assertThat(matcher.find("할매국밥", "부산", false)).get().extracting(PlaceMatch::placeId).isEqualTo(8L);
    }

    @Test
    @DisplayName("여행지 지역 안에서 먼저 찾고, 괄호 설명이 붙은 이름도 같은 이름으로 본다")
    void searchesDestinationRegionFirst() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlacesInRegions(eq("용지봉"), eq(Set.of("Daegu")), anyInt())).thenReturn(List.of(
                inRegion(1655991, "용지봉", "대구광역시 수성구 들안로 9", "Daegu",
                        "Dining and Drinking > Restaurant > Korean Restaurant"),
                inRegion(1705804, "용지봉 (634m)", "수성구 범물동 산362", "Daegu", "Landmarks and Outdoors > Mountain")));
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.find("용지봉", "대구", true)).get().extracting(PlaceMatch::placeId).isEqualTo(1705804L);
        assertThat(matcher.find("용지봉", "대구", false)).get().extracting(PlaceMatch::placeId).isEqualTo(1705804L);
        verify(placeService, never()).searchPlaces(eq("용지봉"), any(Pageable.class));
    }

    @Test
    @DisplayName("경포해변은 이름을 담기만 한 딸린 광장보다 별칭 이름의 해수욕장에 맞춘다")
    void beachAliasBeatsAttachedPlaza() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(any(), any(Pageable.class))).thenReturn(Page.empty());
        when(placeService.searchPlacesInRegions(eq("경포해변"), eq(Set.of("Gangneung")), anyInt())).thenReturn(List.of(
                inRegion(1, "경포해변 중앙광장", "강원특별자치도 강릉시 강문동", "Gangneung",
                        "Landmarks and Outdoors > Plaza")));
        when(placeService.searchPlacesInRegions(eq("경포해수욕장"), eq(Set.of("Gangneung")), anyInt())).thenReturn(
                List.of(inRegion(2, "경포해수욕장", "강원특별자치도 강릉시 창해로", "Gangneung",
                        "Landmarks and Outdoors > Beach")));
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.find("경포해변", "강릉", false)).get().extracting(PlaceMatch::placeId).isEqualTo(2L);
        verify(placeService, times(1)).searchPlacesInRegions(eq("경포해변"), eq(Set.of("Gangneung")), anyInt());
    }

    @Test
    @DisplayName("여행지 region은 여러 도시를 합치고 해외는 묶음 전체를 넣으며, 모르는 여행지면 비어 있다")
    void destinationRegions() {
        PlaceMatcher matcher = new PlaceMatcher(mock(PlaceService.class), Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.destinationRegions("강릉 갔다가 경주")).contains("Gangneung", "Gyeongju");
        assertThat(matcher.destinationRegions("부산, 교토")).contains("Busan", "Osaka", "Kyoto", "Nara");
        assertThat(matcher.destinationRegions("제주도")).containsExactly("Jeju");
        assertThat(matcher.destinationRegions("속초")).isEmpty();
    }

    @Test
    @DisplayName("해외 장소는 한국어 이름으로 맞추고, 주소 대신 같은 여행지 묶음인지 본다")
    void matchesOverseasByKoreanNameAndRegion() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(eq("센소지"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                overseas(1, "浅草寺", "센소지", "Osaka", "Spiritual Center > Buddhist Temple"),
                overseas(2, "浅草寺", "센소지", "Tokyo", "Spiritual Center > Buddhist Temple"))));
        when(placeService.searchPlaces(eq("도다이지"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                overseas(3, "東大寺", "도다이지", "Nara", "Spiritual Center > Buddhist Temple"))));
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        PlaceMatch sensoji = matcher.find("센소지", "도쿄").orElseThrow();
        assertThat(sensoji.placeId()).isEqualTo(2L);
        assertThat(sensoji.name()).isEqualTo("센소지");
        assertThat(matcher.find("도다이지", "교토")).get().extracting(PlaceMatch::placeId).isEqualTo(3L);
        assertThat(matcher.find("도다이지", "도쿄")).isEmpty();
    }

    @Test
    @DisplayName("두 글자 이름은 완전 일치만, 행정 단위는 떼고 주소를 본다")
    void nameAndDestinationRules() {
        assertThat(PlaceMatcher.sameName("광안리해수욕장", "광안리")).isTrue();
        assertThat(PlaceMatcher.sameName("해운대시장", "해운")).isFalse();
        assertThat(PlaceMatcher.inDestination("제주특별자치도 서귀포시", "제주도")).isTrue();
        assertThat(PlaceMatcher.inDestination("부산광역시 해운대구", "해운대")).isTrue();
        assertThat(PlaceMatcher.inDestination("서울특별시 종로구", "부산")).isFalse();
    }

    @Test
    @DisplayName("여러 도시를 적은 여행지는 그중 한 곳 주소에 있으면 맞추고, 이름 속 글자로는 나누지 않는다")
    void multiCityDestination() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(eq("해운대 그랜드호텔"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                place(7, "해운대 그랜드호텔", "부산광역시 해운대구 해운대해변로 217", "Travel and Transportation > Lodging"))));
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.find("해운대 그랜드호텔", "서울, 부산")).isPresent();
        assertThat(matcher.find("해운대 그랜드호텔", "서울이랑 부산")).isPresent();
        assertThat(matcher.find("해운대 그랜드호텔", "서울 갔다가 부산")).isPresent();
        assertThat(matcher.find("해운대 그랜드호텔", "서울과 대전")).isEmpty();

        when(placeService.searchPlaces(eq("국립과천과학관"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                place(8, "국립과천과학관", "경기도 과천시 상하벌로 110", "Arts and Entertainment > Science Museum"))));
        assertThat(matcher.find("국립과천과학관", "과천")).isPresent();
    }

    @Test
    @DisplayName("야시장·박물관처럼 일반 명사만 남는 이름은 조회하지 않는다")
    void genericNamesAreNotSearched() {
        PlaceService placeService = mock(PlaceService.class);
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.find("야시장", "부산")).isEmpty();
        assertThat(matcher.find("박물관", "부산")).isEmpty();
        assertThat(PlaceMatcher.isGeneric("동문시장")).isFalse();
        assertThat(PlaceMatcher.isGeneric("남산")).isFalse();
        org.mockito.Mockito.verifyNoInteractions(placeService);
    }

    @Test
    @DisplayName("해변과 해수욕장은 같은 이름으로 보고 피할 장소를 찾는다")
    void beachAliasFindsAvoidPlace() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(any(), any(Pageable.class))).thenReturn(Page.empty());
        when(placeService.searchPlacesInRegions(eq("경포해변"), eq(Set.of("Gangneung")), anyInt())).thenReturn(List.of(
                place(2, "경포해변 중앙광장", "강원특별자치도 강릉시 강문동", "Landmarks and Outdoors > Plaza")));
        when(placeService.searchPlacesInRegions(eq("경포해수욕장"), eq(Set.of("Gangneung")), anyInt())).thenReturn(List.of(
                place(1, "경포해수욕장", "강원특별자치도 강릉시 강문동", "Landmarks and Outdoors > Beach")));
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.find("경포해변", "강릉", true)).get().extracting(PlaceMatch::placeId).isEqualTo(1L);
    }

    @Test
    @DisplayName("첫 이름에 같은 이름 가게만 맞아도 다른 별칭의 관광지 행을 먼저 받는다")
    void avoidPrefersSightAcrossAliases() {
        PlaceService placeService = mock(PlaceService.class);
        when(placeService.searchPlaces(any(), any(Pageable.class))).thenReturn(Page.empty());
        when(placeService.searchPlacesInRegions(eq("송정해변"), eq(Set.of("Busan")), anyInt())).thenReturn(List.of(
                place(3, "송정해변", "부산광역시 해운대구 송정동", "Dining and Drinking > Cafe")));
        when(placeService.searchPlacesInRegions(eq("송정해수욕장"), eq(Set.of("Busan")), anyInt())).thenReturn(List.of(
                place(4, "송정해수욕장", "부산광역시 해운대구 송정동", "Landmarks and Outdoors > Beach")));
        PlaceMatcher matcher = new PlaceMatcher(placeService, Destinations.fromClasspath(), mock(PlaceFactsReader.class));

        assertThat(matcher.find("송정해변", "부산", true)).get().extracting(PlaceMatch::placeId).isEqualTo(4L);
        assertThat(matcher.find("송정해변", "부산", false)).get().extracting(PlaceMatch::placeId).isEqualTo(4L);
    }
}
