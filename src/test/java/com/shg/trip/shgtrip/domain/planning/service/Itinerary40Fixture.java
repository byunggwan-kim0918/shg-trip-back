package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * itinerary 40(제주 2박3일) 실측 후보 풀 픽스처.
 *
 * <p>이름·카테고리·좌표·평점·체류시간은 전부 로컬 DB의 실제 places 행에서 가져왔다. 40번 일정에
 * 실제로 등장한 15곳(해안도로 7곳 포함)에, 같은 지역의 카페·숙소·식당·관광지 실데이터를 더해
 * 31개로 구성했다. 개편 전 40번이 드러낸 문제 — 해안도로 반복, 하루 196km, 식사 원정, 카페 0개 —
 * 를 그대로 재현하는 입력이므로 C1~C5 회귀 테스트의 기준 풀로 쓴다.
 *
 * <p>주의: 40번 저장 당시 DB에 남은 (0,0) fallback 행이 아니라, 벡터 검색이 실제로 반환했던
 * 원본 행의 좌표다(그 좌표 사고 자체는 A7/A8이 별도로 다룬다).
 */
final class Itinerary40Fixture {

    private Itinerary40Fixture() {}

    static final String VIA = "Landmarks and Outdoors > Tourist Attraction";
    static final String ATTRACTION = "Landmarks and Outdoors > Tourist Attraction";
    static final String KOREAN_RESTAURANT = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    static final String SEAFOOD_RESTAURANT = "Dining and Drinking > Restaurant > Seafood Restaurant";
    static final String RESTAURANT = "Dining and Drinking > Restaurant";
    static final String BURGER = "Dining and Drinking > Restaurant > Burger Joint";
    static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House > Café";
    static final String COFFEE_SHOP = "Dining and Drinking > Cafe, Coffee, and Tea House > Coffee Shop";
    static final String RESORT = "Travel and Transportation > Lodging > Resort";
    static final String HOTEL = "Travel and Transportation > Lodging > Hotel";
    static final String AIRPORT = "Travel and Transportation > Transport Hub > Airport > Airport Terminal";
    static final String MARKET = "Retail > Market";
    static final String CULTURAL_CENTER = "Arts and Entertainment > Cultural Center";

    /** 31개 후보(1-based index 순서 = 리스트 순서). */
    static List<PlaceCandidate> candidates() {
        List<PlaceCandidate> c = new ArrayList<>();
        // ── 40번 일정에 실제로 등장한 장소들 ──
        c.add(p(1, "제주국제공항", AIRPORT, 33.5059806, 126.4927372, "제주시", 4.4, 60));
        c.add(p(2, "용담해안도로", VIA, 33.5157583, 126.5116346, "제주시", 4.0, 90));
        c.add(p(3, "양가형제", RESTAURANT, 33.3073201, 126.2540152, "제주시", 4.1, 70));
        c.add(p(4, "신창풍차해안도로", VIA, 33.3428888, 126.1722338, "제주시", 4.1, 90));
        c.add(p(5, "아레 한국 식당", KOREAN_RESTAURANT, 33.49964063, 126.50257271, "제주시", 4.5, 70));
        c.add(p(6, "제주 무지개해안도로", VIA, 33.509258, 126.4719749, "제주시", 4.4, 90));
        c.add(p(7, "유니호텔 제주", RESORT, 33.4795189, 126.3735431, "제주시", 4.1, 480));
        c.add(p(8, "공천포식당", SEAFOOD_RESTAURANT, 33.2664055, 126.6426731, "서귀포시", 4.2, 70));
        c.add(p(9, "조천함덕해안도로", VIA, 33.5427175, 126.6692481, "제주시", 4.6, 90));
        c.add(p(10, "성산세화해안도로", VIA, 33.4896874, 126.9106417, "제주시", 4.5, 90));
        c.add(p(11, "명품진전복해물탕", KOREAN_RESTAURANT, 33.495353, 126.543014, "제주시", 4.2, 70));
        c.add(p(12, "고산일과해안도로", VIA, 33.25642454, 126.20994195, "제주시", null, 90));
        c.add(p(13, "연돈", RESTAURANT, 33.2588769, 126.4061366, "서귀포시", 4.2, 70));
        c.add(p(14, "사라봉", ATTRACTION, 33.5180564, 126.5460544, "제주시", 4.3, 90));
        c.add(p(15, "월정리해안도로", VIA, 33.5446034, 126.7803854, "제주시", null, 90));

        // ── 같은 지역 보완 후보(카페·숙소·식당·관광지) ──
        c.add(p(16, "카페콜라", CAFE, 33.4433127, 126.2919686, "제주시", 4.4, 60));
        c.add(p(17, "쉼표", COFFEE_SHOP, 33.3951682, 126.2418996, "제주시", 4.2, 60));
        c.add(p(18, "파스쿠찌용담해안도로점", COFFEE_SHOP, 33.5194449, 126.4931894, "제주시", 4.1, 60));
        c.add(p(19, "서귀포 칼호텔", HOTEL, 33.2465497, 126.5818708, "서귀포시", 4.2, 480));
        c.add(p(20, "유니호텔 앤 풀빌라", RESORT, 33.47956988, 126.37339736, "제주시", 4.1, 480));
        c.add(p(21, "하하호호 우도점", BURGER, 33.5208759, 126.9486077, "제주시", 4.4, 70));
        c.add(p(22, "대금식당", KOREAN_RESTAURANT, 33.4025, 126.2511111, "제주시", 4.4, 70));
        c.add(p(23, "사라봉공원", ATTRACTION, 33.5158463, 126.5462002, "제주시", 5.0, 90));
        c.add(p(24, "용두암", ATTRACTION, 33.5145979, 126.5120264, "제주시", 5.0, 60));
        c.add(p(25, "제주 서귀포 산방산", ATTRACTION, 33.2423934, 126.3139575, "서귀포시", 4.9, 90));
        c.add(p(26, "논짓물", ATTRACTION, 33.2368903, 126.3887326, "서귀포시", 4.8, 90));
        c.add(p(27, "1100고지습지", ATTRACTION, 33.357423, 126.462841, "서귀포시", 4.8, 120));
        c.add(p(28, "중문색달해수욕장", ATTRACTION, 33.2451968, 126.4111861, "서귀포시", 4.3, 120));
        c.add(p(29, "곽지해수욕장", ATTRACTION, 33.4488216, 126.3034986, "제주시", 4.3, 120));
        c.add(p(30, "한림민속오일시장", MARKET, 33.42037935, 126.27392377, "제주시", 3.9, 60));
        c.add(p(31, "제주항공우주박물관", CULTURAL_CENTER, 33.30437064, 126.29962755, "서귀포시", null, 90));
        return List.copyOf(c);
    }

    static PlaceCandidate byIndex(int index) {
        return candidates().get(index - 1);
    }

    private static PlaceCandidate p(int index, String name, String category,
                                    double lat, double lng, String subRegion,
                                    Double rating, Integer durationMinutes) {
        return new PlaceCandidate(
                index, (long) index, name, "제주특별자치도 " + subRegion + " " + name, category,
                List.of(), "Jeju", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                null,
                rating != null ? BigDecimal.valueOf(rating) : null,
                0.9, null, null, null, durationMinutes, false, null, subRegion);
    }
}
