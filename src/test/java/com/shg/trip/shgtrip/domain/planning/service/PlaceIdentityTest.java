package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.service.PlaceIdentity.Basis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceIdentityTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic Site";
    private static final String MOUNTAIN = "Landmarks and Outdoors > Mountain";
    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String CAFE = "Dining and Drinking > Cafe";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";
    private static final String INN = "Travel and Transportation > Lodging > Inn";
    private static final String BUS_STATION = "Travel and Transportation > Transport Hub > Bus Station";

    private static PlaceCandidate place(long id, String name, String category, String region,
                                        double lat, double lng, int sitelinks) {
        return new PlaceCandidate(0, id, name, "주소", category, List.of(), region, "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.5, null, null, null, null,
                false, null, null, null, sitelinks, null, null, false, null, null, null);
    }

    static Stream<Arguments> pairs() {
        PlaceCandidate raohe = place(1, "饒河街觀光夜市", SIGHT, "Taipei", 25.0508, 121.5776, 6);
        PlaceCandidate palace = place(2, "故宫", "Landmarks and Outdoors > Palace", "Taipei", 39.9163, 116.3972, 106);
        PlaceCandidate diner = place(3, "호텔 레스토랑", RESTAURANT, "Taipei", 25.0400, 121.5600, 0);
        PlaceCandidate bakery = place(4, "삐에스몽테", CAFE, "Taipei", 25.0300, 121.5500, 0);
        PlaceCandidate resort = place(5, "성호리조트", "Landmarks and Outdoors > Park", "Taipei", 25.1000, 121.6000, 0);
        PlaceCandidate inn = place(6, "도봉여관", INN, "Taipei", 25.1100, 121.6100, 0);
        PlaceCandidate gyeongpodae = place(7, "경포대", SIGHT, "Gangneung", 37.7952, 128.8966, 0);
        PlaceCandidate mountain = place(8, "용지봉 (634m)", MOUNTAIN, "Daegu", 35.8150, 128.6540, 0);
        PlaceCandidate hyundaeok = place(9, "현대옥", RESTAURANT, "Jeonju", 35.8140, 127.1460, 0);
        return Stream.of(
                Arguments.of("영어 이름만 덧붙은 Wikidata 짝",
                        place(11, "饒河街觀光夜市 Raohe St. Night Market", SIGHT, "Taipei", 25.0515, 121.5770, 0),
                        raohe, Basis.BARE_NAME),
                Arguments.of("구역 명소 이름으로 시작하는 그 안의 가게",
                        place(39, "アメリカ村 Club DROP", "Arts and Entertainment > Nightclub", "Osaka", 34.6720, 135.4985, 0),
                        place(40, "アメリカ村", "Landmarks and Outdoors > Pedestrian Plaza", "Osaka", 34.6725, 135.4990, 3),
                        null),
                Arguments.of("장소와 그 박물원 항목",
                        place(12, "故宫博物院", "Arts and Entertainment > Museum", "Taipei", 39.9180, 116.3970, 20),
                        palace, Basis.WIKIDATA_ITEM),
                Arguments.of("같은 좌표 칸의 다른 대분류(호텔 안 식당과 호텔)",
                        place(13, "그랜드 호텔", HOTEL, "Taipei", 25.0400, 121.5600, 0), diner, null),
                Arguments.of("같은 좌표 칸의 같은 대분류",
                        place(14, "같은 층 식당", RESTAURANT, "Taipei", 25.0400, 121.5600, 0), diner, Basis.SAME_CELL),
                Arguments.of("이름도 좌표도 다른 명소",
                        place(15, "士林夜市", SIGHT, "Taipei", 25.0880, 121.5240, 9), raohe, null),
                Arguments.of("카페와 식당으로 갈린 같은 이름",
                        place(16, "삐에스몽테", RESTAURANT, "Taipei", 25.0330, 121.5530, 0), bakery, Basis.SAME_NAME),
                Arguments.of("이름이 같아도 먼 숙소",
                        place(17, "삐에스몽테", HOTEL, "Taipei", 25.0360, 121.5560, 0), bakery, null),
                Arguments.of("관광지와 숙소로 갈린 바로 옆 같은 이름",
                        place(18, "성호리조트", "Travel and Transportation > Lodging > Resort", "Taipei",
                                25.1005, 121.6003, 0), resort, Basis.SAME_NAME),
                Arguments.of("숙소끼리 같은 이름",
                        place(19, "도봉여관", INN, "Taipei", 25.1120, 121.6120, 0), inn, Basis.SAME_NAME),
                Arguments.of("같은 이름이라도 먼 숙소",
                        place(27, "도봉여관", INN, "Taipei", 25.1300, 121.6300, 0), inn, null),
                Arguments.of("같은 지역 같은 이름의 먼 식당",
                        place(28, "현대옥", RESTAURANT, "Jeonju", 35.8590, 127.1460, 0), hyundaeok, null),
                Arguments.of("지역이 없어도 먼 같은 이름은 다른 곳",
                        place(29, "현대옥", RESTAURANT, null, 37.5, 127.0, 0),
                        place(30, "현대옥", RESTAURANT, null, 35.1, 129.0, 0), null),
                Arguments.of("넓은 명소는 수 km 떨어진 출처별 행도 같은 곳",
                        place(31, "서울 한양도성", SIGHT, "Seoul", 37.5800, 126.9900, 0),
                        place(32, "한양도성", SIGHT, "Seoul", 37.5560, 127.0230, 0), Basis.BARE_NAME),
                Arguments.of("영어 이름만 덧붙은 Wikidata 아닌 짝",
                        place(33, "해동용궁사 Haedong Yonggungsa Temple", SIGHT, "Busan", 35.1883, 129.2233, 0),
                        place(34, "해동용궁사", SIGHT, "Busan", 35.1885, 129.2235, 0), Basis.BARE_NAME),
                Arguments.of("앞 낱말만 같은 가게",
                        place(35, "카페 Moon", CAFE, "Busan", 35.1500, 129.0500, 0),
                        place(36, "카페 Sun", CAFE, "Busan", 35.1510, 129.0510, 0), null),
                Arguments.of("명소 이름 뒤에 영문을 붙인 근처 가게",
                        place(41, "창경궁 150", RESTAURANT, "Seoul", 37.57567, 126.99764, 0),
                        place(42, "창경궁", SIGHT, "Seoul", 37.57893, 126.99646, 0), null),
                Arguments.of("명소 이름 뒤에 영어 낱말을 붙인 근처 가게",
                        place(47, "광화문 market", RESTAURANT, "Seoul", 37.5740, 126.9770, 0),
                        place(48, "광화문", SIGHT, "Seoul", 37.5759, 126.9769, 0), null),
                Arguments.of("숫자 꼬리가 붙은 같은 가게의 다른 지점",
                        place(43, "명동교자 2", RESTAURANT, "Seoul", 37.5625, 126.9860, 0),
                        place(44, "명동교자", RESTAURANT, "Seoul", 37.5630, 126.9855, 0), null),
                Arguments.of("이름이 같으면 넓은 야외 명소가 아닌 관광지는 1km 안만 같은 곳",
                        place(45, "롯데시네마", "Arts and Entertainment > Movie Theater", "Daejeon", 36.3500, 127.3800, 0),
                        place(46, "롯데시네마", "Arts and Entertainment > Movie Theater", "Daejeon", 36.3500, 127.4080, 0),
                        null),
                Arguments.of("문장부호만 다른 이름",
                        place(37, "롯데월드·어드벤처", SIGHT, "Seoul", 37.5110, 127.0980, 0),
                        place(38, "롯데월드 어드벤처", SIGHT, "Seoul", 37.5112, 127.0985, 0), Basis.SAME_NAME),
                Arguments.of("이름이 같은 교통 거점",
                        place(20, "성호리조트", BUS_STATION, "Taipei", 25.1001, 121.6001, 0), resort, null),
                Arguments.of("150m 떨어진 같은 이름 숙소",
                        place(21, "성호리조트", HOTEL, "Taipei", 25.1014, 121.6000, 0), resort, null),
                Arguments.of("앞에 지역 도시 이름이 붙은 출처별 행",
                        place(22, "강릉 경포대", SIGHT, "Gangneung", 37.7947, 128.8962, 0), gyeongpodae,
                        Basis.BARE_NAME),
                Arguments.of("다른 지역이면 도시 이름을 떼지 않는다",
                        place(23, "강릉 경포대", SIGHT, "Seoul", 37.7947, 128.8962, 0), gyeongpodae, null),
                Arguments.of("끝에 괄호 설명이 붙은 출처별 행",
                        place(24, "용지봉", MOUNTAIN, "Daegu", 35.8160, 128.6550, 0), mountain, Basis.BARE_NAME),
                Arguments.of("괄호 설명을 뗀 이름이 같아도 먼 가게",
                        place(25, "용지봉", RESTAURANT, "Daegu", 35.8530, 128.6230, 0), mountain, null),
                Arguments.of("도시 이름을 붙인 먼 체인 지점",
                        place(26, "전주 현대옥", RESTAURANT, "Jeonju", 35.8340, 127.1420, 0), hyundaeok, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    void sameAs(String description, PlaceCandidate candidate, PlaceCandidate avoided, Basis expected) {
        assertThat(PlaceIdentity.sameAs(candidate, avoided)).isEqualTo(java.util.Optional.ofNullable(expected));
    }

    @Test
    @DisplayName("이름 끝 괄호 설명과 그 지역 도시 이름만 뗀다")
    void bareName() {
        assertThat(PlaceIdentity.bareName("용지봉 (634m)", "Daegu")).isEqualTo("용지봉");
        assertThat(PlaceIdentity.bareName("강릉 경포대", "Gangneung")).isEqualTo("경포대");
        assertThat(PlaceIdentity.bareName("강릉 경포대", "Busan")).isEqualTo("강릉 경포대");
        assertThat(PlaceIdentity.bareName("강릉역", "Gangneung")).isEqualTo("강릉역");
        assertThat(PlaceIdentity.bareName("(주)한양", "Seoul")).isEqualTo("(주)한양");
        assertThat(PlaceIdentity.bareName(null, "Seoul")).isEmpty();
        assertThat(PlaceIdentity.bareName("경주 양동마을 [유네스코 세계유산]", "Gyeongju")).isEqualTo("양동마을");
    }

    @Test
    @DisplayName("이름 키는 대소문자·띄어쓰기·문장부호만 지우고 지역·대분류로 나누며, 괄호 설명·영문 병기·짧은 이름은 묶지 않는다")
    void nameKey() {
        String park = "Landmarks and Outdoors > Park";
        String cafe = "Dining and Drinking > Cafe";

        assertThat(PlaceIdentity.nameKey("제주곶자왈도립공원", "Jeju", park))
                .isEqualTo(PlaceIdentity.nameKey("제주 곶자왈도립공원", "Jeju", park));
        assertThat(PlaceIdentity.nameKey("Café de Flore", "Seoul", cafe))
                .isNotEqualTo(PlaceIdentity.nameKey("Café Paul", "Seoul", cafe));
        assertThat(PlaceIdentity.nameKey("에버랜드 (주토피아)", "Yongin", park))
                .isNotEqualTo(PlaceIdentity.nameKey("에버랜드", "Yongin", park));
        assertThat(PlaceIdentity.nameKey("자갈치시장", "Busan", RESTAURANT))
                .isNotEqualTo(PlaceIdentity.nameKey("자갈치시장", "Busan", "Retail > Market"));
        assertThat(PlaceIdentity.nameKey("회", "Busan", RESTAURANT)).isNull();
        assertThat(PlaceIdentity.nameKey(" ", "Busan", RESTAURANT)).isNull();
    }
}
