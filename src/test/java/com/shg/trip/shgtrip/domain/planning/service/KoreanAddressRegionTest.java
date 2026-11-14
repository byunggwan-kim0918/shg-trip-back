package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanAddressRegionTest {

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @DisplayName("주소 첫 토큰의 광역 단위가 지역에 허용된 범위 밖일 때만 불일치")
    @CsvSource(delimiter = '|', value = {
            "경기 용인시 처인구 백령로20번길 3|Busan|true",
            "전라남도 함평군 월야면|Busan|true",
            "부산광역시 기장군 기장읍 공수2길 11|Busan|false",
            "경남 김해시 어딘가 1|Busan|false",
            "부산진구 서전로47번길 17|Busan|false",
            "해운대구 구남로 42|Busan|false",
            "129 Cheongsapo-ro, Haeundae, Busan, South Korea|Busan|false",
            "전남광주통합특별시 여수시 어딘가|Yeosu|false",
            "세종특별자치시 어딘가|Jeju|true",
            "경기 어딘가|Osaka|false",
    })
    void contradicts(String address, String region, boolean expected) {
        assertThat(KoreanAddressRegion.contradicts(address, region)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @DisplayName("주소가 지역과 어긋나면 세부지역을 비워 지역명이 쓰이게 한다")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "경기 용인시 처인구 백령로20번길 3|Busan|null",
            "부산광역시 해운대구 구남로 42|Busan|해운대구",
            "경남 김해시 어딘가 1|Busan|김해시",
            "1-1 Namba, Chuo-ku, Osaka|Osaka|Chuo-ku",
    })
    void subRegionOf(String address, String region, String expected) {
        assertThat(PlaceCategoryConstants.subRegionOf(address, region)).isEqualTo(expected);
    }
}
