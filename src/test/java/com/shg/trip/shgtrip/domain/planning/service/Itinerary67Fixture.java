package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * itinerary 67(제주 2박3일, 로맨틱·버짓, car) 실측 후보 풀 픽스처.
 *
 * <p>67번이 한 번에 드러낸 결함 6건을 그대로 재현하는 입력이다:
 * <ol>
 *   <li><b>숙소 0개</b> — 프롬프트가 "남는 숙소는 spare로"라고 지시해 LODGING 3곳이 전부
 *       {@code spareIndices}에 들어갔고, 코드가 spare를 "사용중"으로 봐서 배정을 포기했다.
 *       {@link #selectionWithLodgingOnlyInSpare()}가 이 상태를 재현한다.</li>
 *   <li>마지막날 저녁 없음 — 식당이 공항 길목에만 있어 day centroid 반경 밖</li>
 *   <li>마지막날 23:25 귀가 — 180분 장소 + 공항 90분</li>
 *   <li>야외 장소 야간 배치</li>
 *   <li>"제주 성산항"(여객 터미널)이 관광 90분 스텝</li>
 *   <li>하루 표시 108km</li>
 * </ol>
 *
 * <p>이름·카테고리·좌표·평점은 로컬 DB의 실제 places 행에서 가져왔다.
 */
final class Itinerary67Fixture {

    private Itinerary67Fixture() {}

    static final String ATTRACTION = "Landmarks and Outdoors > Tourist Attraction";
    static final String KOREAN_RESTAURANT =
            "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    static final String SEAFOOD_RESTAURANT = "Dining and Drinking > Restaurant > Seafood Restaurant";
    static final String RESTAURANT = "Dining and Drinking > Restaurant";
    static final String BBQ = "Dining and Drinking > Restaurant > BBQ Joint";
    static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House > Café";
    static final String COFFEE_SHOP = "Dining and Drinking > Cafe, Coffee, and Tea House > Coffee Shop";
    static final String HOTEL = "Travel and Transportation > Lodging > Hotel";
    static final String RESORT = "Travel and Transportation > Lodging > Resort";
    static final String AIRPORT = "Travel and Transportation > Transport Hub > Airport > Airport Terminal";

    /** 인덱스 상수 — 테스트 가독성을 위해 이름으로 참조한다(1-based). */
    static final int AIRPORT_IDX = 1;
    /** 체류 180분 야외 장소 — "야외 18:13~21:13" 결함의 원인. */
    static final int GEOPARK_IDX = 7;
    /** 여객 터미널이 관광지 카테고리로 들어온 행. */
    static final int PORT_IDX = 9;
    /** 공항 길목(제주시)에만 있는 식당 — 동쪽 day centroid에서 반경 밖. */
    static final int AIRPORT_SIDE_DINER_IDX = 13;

    /**
     * 20개 후보(1-based index = 리스트 순서). LODGING 3 / DINING 7 / CAFE 2 / ATTRACTION 7 / HUB 1.
     */
    static List<PlaceCandidate> candidates() {
        List<PlaceCandidate> c = new ArrayList<>();
        // 1 교통 허브
        add(c, "제주국제공항", AIRPORT, 33.50707720, 126.49343110, "제주시", 4.4, 60);
        // 2~8 관광지 (동부에 몰려 있음 — 마지막날 귀가 거리가 길어지는 구조)
        add(c, "사라봉공원", ATTRACTION, 33.51584630, 126.54620020, "제주시", 4.3, 90);
        add(c, "제주별빛누리공원", ATTRACTION, 33.44453070, 126.54929160, "제주시", 4.2, 90);
        add(c, "서프라이즈 테마파크", ATTRACTION, 33.46519360, 126.65721890, "제주시", 4.1, 180);
        add(c, "개오름", ATTRACTION, 33.42276570, 126.77197210, "서귀포시", 4.0, 120);
        add(c, "손지오름", ATTRACTION, 33.45648230, 126.81951760, "서귀포시", 4.2, 150);
        add(c, "제주도 국가지질공원", ATTRACTION, 33.45696010, 126.71438950, "서귀포시", 4.5, 180);
        add(c, "세기알해변", ATTRACTION, 33.55816900, 126.75539430, "제주시", 4.1, 90);
        // 9 여객 터미널이 관광지로 오적재된 행 (결함5)
        add(c, "제주 성산항", ATTRACTION, 33.47330010, 126.93355140, "서귀포시", 3.9, 90);
        // 10~12 동부 식당
        add(c, "어멍", BBQ, 33.54134432, 126.67237193, "제주시", 4.3, 70);
        add(c, "공천포식당", SEAFOOD_RESTAURANT, 33.26640550, 126.64267310, "서귀포시", 4.2, 70);
        add(c, "명품진전복해물탕", KOREAN_RESTAURANT, 33.49535300, 126.54301400, "제주시", 4.2, 70);
        // 13~16 공항 길목(제주시 서부) 식당 — 동쪽 day에서 보면 반경 밖
        add(c, "아레 한국 식당", KOREAN_RESTAURANT, 33.49964063, 126.50257271, "제주시", 4.5, 70);
        add(c, "양가형제", RESTAURANT, 33.30732010, 126.25401520, "제주시", 4.1, 70);
        add(c, "대금식당", KOREAN_RESTAURANT, 33.40251946, 126.25095086, "제주시", 4.4, 70);
        add(c, "연돈", RESTAURANT, 33.25887690, 126.40613660, "서귀포시", 4.2, 70);
        // 17~18 카페
        add(c, "쉼표", COFFEE_SHOP, 33.39528441, 126.24190994, "제주시", 4.3, 60);
        add(c, "카페콜라", CAFE, 33.44331270, 126.29196860, "제주시", 4.0, 60);
        // 19~20 숙소 — 67번에서는 이 둘이 전부 spareIndices에 들어가 배정되지 않았다
        add(c, "제주R호텔", HOTEL, 33.49944070, 126.51791950, "제주시", 4.1, 1440);
        add(c, "Ocean Grand Hotel Jeju", RESORT, 33.54198287, 126.66536975, "제주시", 4.2, 1440);
        return c;
    }

    /**
     * 결함1 재현: 숙소가 하나도 배정되지 않고 <b>전부 spareIndices에만</b> 있는 선택 결과.
     * Tool Use 스키마에서 accommodationIndex를 제거한 뒤 프롬프트가 "남는 숙소는 spare로"라고
     * 지시하면 Sonnet이 정확히 이렇게 출력한다(실측 60·66·67 동일).
     */
    static SelectionOutput selectionWithLodgingOnlyInSpare() {
        return new SelectionOutput(
                "제주 오름과 해안을 잇는 로맨틱 여행",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(2, 13, 4, 12, 3), null, null),
                        new SelectionOutput.DayPlan(2, null, List.of(17, 15, 18, 14, 16), null, null),
                        new SelectionOutput.DayPlan(3, null, List.of(5, 10, 9, 6, 7), null, null)
                ),
                List.of(),
                List.of(19, 20, 8, 11),   // ★ LODGING 19·20이 spare에만 존재
                List.of(), List.of()
        );
    }

    private static void add(List<PlaceCandidate> c, String name, String category,
                            double lat, double lng, String subRegion, double rating, int minutes) {
        c.add(new PlaceCandidate(
                c.size() + 1, (long) (c.size() + 1), name, "제주특별자치도 " + subRegion + " " + name,
                category, List.of(), "Jeju", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null,
                BigDecimal.valueOf(rating), 0.85, null, null, null, minutes, false, null, subRegion));
    }
}
