package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EventVenueTest {

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @DisplayName("경기장·대학만 행사 시설이고, 이름에 경기장이 든 관광지와 대학 안 박물관·수목원은 아니다")
    @CsvSource(delimiter = '|', value = {
            "부산아시아드주경기장 (Busan Asiad Main Stadium)|Arts and Entertainment > Stadium > Track Stadium|true",
            "부산 아시아드주경기장|Sports and Recreation > Outdoor Activity|true",
            "고척스카이돔|Arts and Entertainment > Stadium > Baseball Stadium|true",
            "고려대학교|Community and Government > Education > College and University|true",
            "전북대학교 박물관|Arts and Entertainment > Cultural Center|false",
            "전남대학교 수목원|Landmarks and Outdoors > Garden|false",
            "수영만 요트경기장|Landmarks and Outdoors > Harbor or Marina|false",
            "왕산요트경기장|Landmarks and Outdoors > Beach|false",
            "한강공원(올림픽주경기장)|Landmarks and Outdoors > Park|false",
            "서울동물원 기린운동장|Arts and Entertainment > Zoo > Zoo Exhibit|false",
            "벡스코(BEXCO)|Arts and Entertainment > Cultural Center|false",
            "감천문화마을|Landmarks and Outdoors > Tourist Attraction|false",
    })
    void isEventVenue(String name, String category, boolean expected) {
        assertThat(PlaceCategoryConstants.isEventVenue(name, category)).isEqualTo(expected);
    }
}
