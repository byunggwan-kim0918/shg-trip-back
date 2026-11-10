package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.service.PlaceService;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaceMatcherTest {

    private static PlaceResponse place(long id, String name, String address, String category) {
        return new PlaceResponse(id, name, address, null, null, category, null, null, null, null, null, null, null);
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

        Map<String, PlaceMatch> matches = new PlaceMatcher(placeService).match(Events.of()
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
    @DisplayName("두 글자 이름은 완전 일치만, 행정 단위는 떼고 주소를 본다")
    void nameAndDestinationRules() {
        assertThat(PlaceMatcher.sameName("광안리해수욕장", "광안리")).isTrue();
        assertThat(PlaceMatcher.sameName("해운대시장", "해운")).isFalse();
        assertThat(PlaceMatcher.inDestination("제주특별자치도 서귀포시", "제주도")).isTrue();
        assertThat(PlaceMatcher.inDestination("부산광역시 해운대구", "해운대")).isTrue();
        assertThat(PlaceMatcher.inDestination("서울특별시 종로구", "부산")).isFalse();
    }

    @Test
    @DisplayName("야시장·박물관처럼 일반 명사만 남는 이름은 조회하지 않는다")
    void genericNamesAreNotSearched() {
        PlaceService placeService = mock(PlaceService.class);
        PlaceMatcher matcher = new PlaceMatcher(placeService);

        assertThat(matcher.find("야시장", "부산")).isEmpty();
        assertThat(matcher.find("박물관", "부산")).isEmpty();
        assertThat(PlaceMatcher.isGeneric("동문시장")).isFalse();
        assertThat(PlaceMatcher.isGeneric("남산")).isFalse();
        org.mockito.Mockito.verifyNoInteractions(placeService);
    }
}
