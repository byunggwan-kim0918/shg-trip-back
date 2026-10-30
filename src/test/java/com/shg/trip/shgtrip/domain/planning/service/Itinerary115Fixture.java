package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * itinerary 115(제주 3박4일 "맛집 위주 커플여행", 예산 100만원) 재구성 픽스처.
 *
 * <p>생성 로그에 후보 풀 전문이 남지 않아 <b>재구성</b>했다. 115번 일정에 등장한 장소와 로그에
 * 이름이 찍힌 장소(트림·교체·보충 대상)를 Sonnet 선택으로, 같은 지역 실제 places 행을 spare·풀로
 * 두었다. 모든 필드(좌표·태그·시간대·체류시간·입장료·인기도)는 로컬 DB 실제 값이다.
 *
 * <p>115가 드러낸 문제 — 흑돼지 반복, 카페 트림, 숙소→점심 111분, 백록담 오후 갭필, 우도 식당,
 * 커플에게 게스트하우스, 마지막날 5시간 공백 — 를 재현하는 기준 풀이다.
 */
final class Itinerary115Fixture {

    private Itinerary115Fixture() {}

    /** 48개 후보(1-based index 순서 = 리스트 순서). 47~48은 날짜별 예비 후보. */
    static List<PlaceCandidate> candidates() {
        List<PlaceCandidate> c = new ArrayList<>();
        c.add(p(1, 28L, "제주국제공항", "Travel and Transportation > Transport Hub > Airport > Airport Terminal", 33.50608174, 126.49278096, "제주국제공항", "제주시",
                null, 60, List.of("morning", "afternoon"),
                List.of("제주국제공항", "출입국심사", "이민국", "공항터미널", "국제선", "입국심사", "출국심사", "교통허브"),
                0, 30));
        c.add(p(2, 598131L, "성산포항 종합여객터미널", "Travel and Transportation > Transport Hub > Terminal", 33.47289120, 126.93460520, "South Korea, Jeju-do, Seogwipo, Seongsan-eup, Seongsandeungyong-ro, 130-21 #63643", "서귀포시",
                null, 60, List.of("morning", "afternoon"),
                List.of("여객선 터미널", "해상교통", "제주 동부", "성산포항", "울릉도 연결", "독도", "해안 경관", "관광 출발점"),
                0, 0));
        c.add(p(3, 18067L, "소낭게스트하우스", "Travel and Transportation > Lodging > Hostel", 33.55203900, 126.79098200, "대한민국 제주특별자치도 제주시 구좌읍 월정1길 1", "제주시",
                null, 120, List.of("afternoon", "evening"),
                List.of("게스트하우스", "저가숙박", "배낭여행", "제주도", "여행자교류", "개별여행", "호스텔", "합리적가격"),
                0, 0));
        c.add(p(4, 1651591L, "펜션연리", "Travel and Transportation > Lodging", 33.24040300, 126.38632900, "대한민국 제주특별자치도 서귀포시 하예동 463-1", "서귀포시",
                null, 480, List.of("all_day"),
                List.of("제주펜션", "숙박", "휴양", "가족여행", "커플여행", "제주도", "휴식", "숙소"),
                0, 0));
        c.add(p(5, 18068L, "제주R호텔", "Travel and Transportation > Lodging > Hotel", 33.49944070, 126.51791950, "5 Seogwang-ro 14-gil, 특별자치도, Cheju, Jeju-do, South Korea", "제주시",
                null, 480, List.of("evening", "night", "morning"),
                List.of("호텔", "숙박", "제주도", "여행", "휴식", "관광거점", "편의시설", "서비스"),
                0, 0));
        c.add(p(6, 1651564L, "프레리아 커플 독채펜션", "Travel and Transportation > Lodging", 33.42548947, 126.40504901, "제주특별자치도 제주시 애월읍 유수암평화5길 34-29", "제주시",
                null, 1440, List.of("afternoon", "evening", "night"),
                List.of("펜션", "독채", "커플", "제주도", "숙박", "휴양", "로맨틱", "프라이빗"),
                0, 0));
        c.add(p(7, 1651579L, "신라스테이 제주", "Travel and Transportation > Lodging", 33.48551752, 126.49105129, "제주특별자치도 제주시 노연로 100", "제주시",
                null, null, List.of("all_day"),
                List.of("럭셔리 호텔", "제주 숙소", "프리미엄 리조트", "온돌 객실", "제주 여행", "고급 숙박", "가족 여행", "휴양"),
                0, 0));
        c.add(p(8, 308856L, "열안지오름(오라동)", "Landmarks and Outdoors > Tourist Attraction", 33.42659673, 126.52071671, "제주특별자치도 제주시 산록북로", "제주시",
                null, 120, List.of("morning", "afternoon"),
                List.of("제주 오름", "화산지형", "등산", "초보자 친화", "자연관광", "제주 명소", "트레킹", "경치 좋은", "야외활동"),
                0, 3));
        c.add(p(9, 308884L, "별방진", "Landmarks and Outdoors > Tourist Attraction", 33.52653920, 126.88479360, "대한민국 제주특별자치도 제주시 특별자치도, 구좌읍 해맞이해안로 1708 KR", "제주시",
                null, 90, List.of("morning", "afternoon"),
                List.of("제주 해안", "절벽", "자연경관", "사진명소", "해양생태", "지질명소", "트레킹", "일출", "바다풍경", "관광지"),
                0, 1));
        c.add(p(10, 308827L, "성산일출봉 [유네스코 세계자연유산]", "Landmarks and Outdoors > Tourist Attraction", 33.45913060, 126.93961950, "대한민국 제주특별자치도 서귀포시 성산읍 성산리 78", "서귀포시",
                null, 120, List.of("morning", "afternoon"),
                List.of("일출", "화산", "등산", "유네스코 세계자연유산", "제주도", "해상경관", "트레킹", "자연경관", "관광지"),
                5000, 16));
        c.add(p(11, 308987L, "제주도 국가지질공원", "Landmarks and Outdoors > Tourist Attraction", 33.45696010, 126.71438950, "569-36 Seongyo-ro, Jocheon-eup, Cheju, Jeju-do, South Korea", "제주시",
                null, 180, List.of("morning", "afternoon"),
                List.of("지질공원", "한라산", "성산일출봉", "주상절리", "화산", "제주도", "자연", "지질학", "트레킹", "관광명소"),
                0, 1));
        c.add(p(12, 308881L, "외돌개(제주)", "Landmarks and Outdoors > Tourist Attraction", 33.23990380, 126.54659480, "대한민국 제주특별자치도 서귀포시 천지동 서귀포 스쿠버 다이빙선 탑승지", "서귀포시",
                null, 60, List.of("morning", "afternoon", "evening"),
                List.of("제주도", "해안 절경", "기암괴석", "자연 경관", "사진 명소", "서귀포", "해변", "관광지", "지질 구조", "제주 필수코스"),
                0, 3));
        c.add(p(13, 308814L, "송악산전망대", "Landmarks and Outdoors > Tourist Attraction", 33.19859390, 126.29396030, "산 2-13 Sangmo-ri, Daejeong-eup, Seogwipo, Jeju-do, South Korea", "서귀포시",
                null, 120, List.of("morning", "evening"),
                List.of("제주 오름", "해안 경관", "전망대", "사진 명소", "한라산 조망", "자연 관광지", "일출 명소", "트레킹", "제주 북서부", "야경 명소"),
                0, 0));
        c.add(p(14, 308948L, "제주 서귀포 산방산", "Landmarks and Outdoors > Tourist Attraction", 33.24239340, 126.31395750, "산16 Sage-ri, Andeok-myeon, Seogwipo, Jeju-do, South Korea", "서귀포시",
                null, 90, List.of("morning", "afternoon"),
                List.of("화산", "등산", "하이킹", "해안경관", "서귀포", "제주 랜드마크", "자연 관광지", "경치", "트레킹"),
                1000, 8));
        c.add(p(15, 308882L, "협재해수욕장", "Landmarks and Outdoors > Tourist Attraction", 33.39413250, 126.23972800, "South Korea, 협재해변", "제주시",
                null, 120, List.of("morning", "afternoon"),
                List.of("해변", "백사장", "에메랄드빛 바다", "수상스포츠", "가족 관광지", "제주 필수 명소", "해수욕", "해양 레저", "경치", "휴양지"),
                0, 4));
        c.add(p(16, 1651841L, "형돈", "Dining and Drinking > Restaurant", 33.48935110, 126.53292534, "제주특별자치도 제주시 구남로 22 (이도이동)", "제주시",
                null, 70, List.of("afternoon", "evening"),
                List.of("제주 식당", "한식", "현지 음식", "저녁 식사", "레스토랑", "제주 특산물", "한끼 식사", "로컬 맛집"),
                0, 0));
        c.add(p(17, 1652049L, "흑돈가 제주", "Dining and Drinking > Restaurant", 33.48003140, 126.47359750, "대한민국 제주특별자치도 제주시 노형동 한라대학로 11", "제주시",
                null, 70, List.of("afternoon", "evening", "night"),
                List.of("흑돈", "돼지고기", "제주 음식", "고기 구이", "식당", "지역 맛집", "저녁 식사", "야식"),
                0, 0));
        c.add(p(18, 1651911L, "임성반점", "Dining and Drinking > Restaurant", 33.50951794, 126.50976301, "제주특별자치도 제주시 용화로 57-1 (용담이동)", "제주시",
                null, 70, List.of("afternoon", "evening"),
                List.of("중식당", "중국음식", "제주", "식당", "베이징덕", "짜장면", "짬뽕", "대중적", "저렴"),
                0, 0));
        c.add(p(19, 1652047L, "은희네해장국", "Dining and Drinking > Restaurant", 33.50855743, 126.54073811, "제주특별자치도 제주시 고마로13길 8", "제주시",
                null, 70, List.of("morning", "afternoon"),
                List.of("해장국", "국밥", "제주음식", "현지맛집", "아침식사", "숙취해소", "포장가능", "제주도"),
                0, 0));
        c.add(p(20, 1651779L, "섬소나이 우도본점", "Dining and Drinking > Restaurant", 33.51336916, 126.95766015, "제주특별자치도 제주시 우도면 우도해안길 814", "제주시",
                null, 70, List.of("afternoon", "evening"),
                List.of("제주 음식", "우동", "일식당", "현지 맛집", "점심", "저녁 식사", "제주도 여행"),
                0, 0));
        c.add(p(21, 1651639L, "흑본오겹 함덕점", "Dining and Drinking > Restaurant", 33.54362820, 126.65881180, "대한민국 제주특별자치도 제주시 특별자치도, 신북로 454 KR", "제주시",
                null, 70, List.of("afternoon", "evening"),
                List.of("일식당", "고기구이", "흑돼지", "제주음식", "오겹살", "점심식사", "저녁식사", "현지맛집"),
                0, 0));
        c.add(p(22, 1652017L, "봉순이네흑돼지", "Dining and Drinking > Restaurant", 33.27775800, 126.27961020, "대한민국 제주특별자치도 서귀포시 특별자치도, 대정읍 구억리 1010", "서귀포시",
                null, 70, List.of("afternoon", "evening"),
                List.of("흑돼지", "제주 음식", "구이", "돼지고기", "현지 맛집", "저녁 식사", "가족 식사", "제주 여행"),
                0, 0));
        c.add(p(23, 1651818L, "크라운돼지", "Dining and Drinking > Restaurant", 33.39389890, 126.38331570, "대한민국 제주특별자치도 제주시 특별자치도, 애월읍 애월읍 상가목장길 84", "제주시",
                null, 70, List.of("afternoon", "evening"),
                List.of("흑돼지", "제주 음식", "고기 구이", "돼지고기", "식당", "한식", "로컬 맛집", "점심", "저녁"),
                0, 0));
        c.add(p(24, 1651796L, "반양", "Dining and Drinking > Restaurant", 33.28105230, 126.22629510, "대한민국 제주특별자치도 서귀포시 대정읍 중산간서로 2986", "서귀포시",
                null, 70, List.of("afternoon", "evening"),
                List.of("제주 음식", "한식당", "현지 맛집", "저녁 식사", "가족 식사", "제주 특산물", "편안한 분위기"),
                0, 0));
        c.add(p(25, 1651942L, "수애기베이커리", "Dining and Drinking > Restaurant", 33.22228240, 126.24384040, "대한민국 제주특별자치도 서귀포시 특별자치도, 동일리 3132 KR", "서귀포시",
                null, 60, List.of("morning", "afternoon"),
                List.of("베이커리", "카페", "브런치", "빵", "디저트", "제주카페", "수제빵", "커피"),
                0, 0));
        c.add(p(26, 1651944L, "미쁜제과", "Dining and Drinking > Restaurant", 33.26916050, 126.18005920, "대한민국 제주특별자치도 서귀포시 특별자치도, 대정읍 도원남로 16", "서귀포시",
                null, 60, List.of("morning", "afternoon"),
                List.of("제주 카페", "베이커리", "디저트", "제과점", "간식", "카페 문화", "로컬 맛집"),
                0, 0));
        c.add(p(27, 1651976L, "감따남", "Dining and Drinking > Restaurant", 33.26217710, 126.49475240, "대한민국 제주특별자치도 서귀포시 강정동 1890-2", "서귀포시",
                null, 70, List.of("afternoon", "evening"),
                List.of("제주 음식", "현지 맛집", "제주 흑돼지", "돈까스", "한식당", "가정식", "저녁 식사", "현지인 추천"),
                0, 0));
        c.add(p(28, 1651751L, "영육일삼", "Dining and Drinking > Restaurant", 33.23447022, 126.48835162, "제주특별자치도 서귀포시 이어도로 679 (강정동)", "서귀포시",
                null, 70, List.of("afternoon", "evening"),
                List.of("제주 음식", "로컬 식당", "육회", "해산물", "제주 향토음식", "저녁 식사", "가족 식사", "한식"),
                0, 0));
        c.add(p(29, 308874L, "제주항", "Landmarks and Outdoors > Tourist Attraction", 33.52053890, 126.53664130, "제주시 제주항", "제주시",
                null, 90, List.of("afternoon", "evening"),
                List.of("항구", "해안 풍경", "일몰", "해양 문화", "해산물", "관광지", "제주도", "해상 활동"),
                0, 5));
        c.add(p(30, 308988L, "한라산 백록담", "Landmarks and Outdoors > Tourist Attraction", 33.36166660, 126.52916660, "대한민국 서귀포시 한라산", "서귀포시",
                null, 180, List.of("morning", "afternoon"),
                List.of("한라산", "백록담", "화산", "분화구", "등산", "트레킹", "제주도", "자연", "경치", "관광지"),
                0, 5));
        c.add(p(31, 1652034L, "올래국수", "Dining and Drinking > Restaurant", 33.49148918, 126.49720617, "제주특별자치도 제주시 귀아랑길 24", "제주시",
                null, 70, List.of("morning", "afternoon"),
                List.of("국수", "면요리", "제주음식", "현지식당", "점심식사", "저렴한식사", "전통음식", "국물요리"),
                0, 0));
        c.add(p(32, 1651732L, "삼보식당", "Dining and Drinking > Restaurant", 33.24764489, 126.55908686, "제주특별자치도 서귀포시 중정로 25 (서귀동)", "서귀포시",
                null, 70, List.of("afternoon", "evening"),
                List.of("제주음식", "로컬식당", "향토요리", "제주 맛집", "식사", "저녁식사", "점심", "친절한 서빙", "합리적 가격", "전통음식"),
                0, 0));
        c.add(p(33, 1651938L, "고집돌우럭 중문점", "Dining and Drinking > Restaurant", 33.25801094, 126.41671841, "제주특별자치도 서귀포시 일주서로 879 (색달동)", "서귀포시",
                null, 70, List.of("afternoon", "evening"),
                List.of("우럭회", "생선회", "해산물", "제주음식", "중문", "한끼식사", "신선한재료"),
                0, 0));
        c.add(p(34, 308800L, "천지연폭포", "Landmarks and Outdoors > Tourist Attraction", 33.24481370, 126.55979000, "대한민국 제주특별자치도 서귀포시 천지동 666-2", "서귀포시",
                null, 90, List.of("morning", "afternoon"),
                List.of("폭포", "자연", "트레킹", "산책로", "제주도", "서귀포", "경치", "힐링"),
                0, 5));
        c.add(p(35, 1651612L, "카페 귤꽃다락", "Dining and Drinking > Restaurant", 33.24643889, 126.52252339, "제주특별자치도 서귀포시 이어도로1027번길 34", "서귀포시",
                null, 60, List.of("morning", "afternoon"),
                List.of("제주카페", "감성카페", "다락방", "귤", "제주여행", "카페투어", "힐링", "디저트", "음료"),
                0, 0));
        c.add(p(36, 3L, "한림민속오일시장", "Retail > Market", 33.42037935, 126.27392377, "한림읍 한수풀로4길 10", "제주시",
                null, 60, List.of("morning", "afternoon"),
                List.of("전통시장", "제주 특산물", "민속시장", "기념품", "제주 먹거리", "로컬문화", "재래시장", "쇼핑"),
                0, 0));
        c.add(p(37, 1651659L, "도두해녀의집", "Dining and Drinking > Restaurant", 33.50622595, 126.46664183, "제주특별자치도 제주시 도두항길 16 (도두일동)", "제주시",
                null, 90, List.of("lunch", "afternoon"),
                List.of("해녀 문화", "제주 향토 음식", "신선한 해산물", "전통 레스토랑", "관광 명소", "제주 특산물", "해산물 요리", "현지 식당"),
                0, 0));
        c.add(p(38, 1651710L, "가시아방국수", "Dining and Drinking > Restaurant", 33.43865733, 126.91809308, "제주특별자치도 서귀포시 성산읍 섭지코지로 10", "서귀포시",
                null, 70, List.of("morning", "afternoon"),
                List.of("국수", "제주 음식", "전통 요리", "현지 맛집", "점심", "저렴한 가격", "소문난 식당"),
                0, 0));
        c.add(p(39, 1651605L, "해녀의 부엌", "Dining and Drinking > Restaurant", 33.49781737, 126.91230611, "제주특별자치도 제주시 구좌읍 해맞이해안로 2265", "제주시",
                null, 70, List.of("afternoon", "evening"),
                List.of("제주 음식", "해녀", "신선한 해산물", "로컬 레스토랑", "제주 특산물", "전통 요리", "바다음식", "제주 여행"),
                0, 0));
        c.add(p(40, 1651825L, "중문색달통갈치 본점", "Dining and Drinking > Restaurant", 33.25839126, 126.40453891, "제주특별자치도 서귀포시 일주서로 993 (색달동)", "서귀포시",
                null, 70, List.of("lunch", "dinner"),
                List.of("갈치구이", "제주 해산물", "신선한 생선", "갈치 전문점", "제주 향토 음식", "가족 식사", "로컬 맛집"),
                0, 0));
        c.add(p(41, 1651994L, "바다속고등어쌈밥", "Dining and Drinking > Restaurant", 33.48429965, 126.40678364, "제주특별자치도 제주시 애월읍 일주서로 7089", "제주시",
                null, 70, List.of("lunch", "dinner"),
                List.of("고등어", "쌈밥", "제주 음식", "로컬 맛집", "신선한 생선", "한정식", "점심", "저녁"),
                0, 0));
        c.add(p(42, 1651849L, "소섬전복", "Dining and Drinking > Restaurant", 33.49886811, 126.96510706, "제주특별자치도 제주시 우도면 우도해안길 1158", "제주시",
                null, 70, List.of("afternoon", "evening"),
                List.of("전복", "해산물", "제주 음식", "고급 식당", "신선한 재료", "전통 요리", "해물 요리"),
                0, 0));
        c.add(p(43, 18073L, "카페콜라", "Dining and Drinking > Cafe, Coffee, and Tea House > Café", 33.44331270, 126.29196860, "5857 Iljuseo-ro, Hallim-eup, Cheju, Jeju-do, South Korea", "제주시",
                null, 60, List.of("morning", "afternoon"),
                List.of("제주카페", "커피", "차", "휴식", "자연경관", "카페문화", "조용한분위기", "브레이크타임"),
                0, 0));
        c.add(p(44, 5L, "쉼표", "Dining and Drinking > Cafe, Coffee, and Tea House > Coffee Shop", 33.39528441, 126.24190994, "한림읍 한림로 359", "제주시",
                null, 60, List.of("morning", "afternoon"),
                List.of("제주카페", "감성카페", "커피", "휴식공간", "감정치유", "분위기좋은", "일상탈출", "제주감성"),
                0, 0));
        c.add(p(45, 1651807L, "애월더선셋", "Dining and Drinking > Restaurant", 33.45620955, 126.30904278, "제주특별자치도 제주시 애월읍 일주서로 6111", "제주시",
                null, 90, List.of("afternoon", "evening"),
                List.of("석양 카페", "제주 해변", "선셋뷰", "감성 카페", "드라이브 코스", "사진명소", "로맨틱", "제주 핫플레이스"),
                0, 0));
        c.add(p(46, 1651713L, "어니스트밀크 본점", "Dining and Drinking > Restaurant", 33.45044139, 126.87189681, "제주특별자치도 서귀포시 성산읍 중산간동로 3147-7", "서귀포시",
                null, 60, List.of("morning", "afternoon"),
                List.of("카페", "디저트", "브런치", "감성있는", "제주도", "핫플레이스", "우유", "라떼", "감성카페"),
                0, 0));
        // ── 날짜별 예비 후보(실행기 appendNearbyReserve가 붙이는 그날 중심 근처 관광지) ──
        // 선택·spare 어디에도 없고 "미사용 후보"로만 쓰인다. 제주시 day의 빈 시간을 채울 재료다.
        c.add(p(47, 1658235L, "제주 삼성혈", "Landmarks and Outdoors > Tourist Attraction", 33.50414593, 126.52894598,
                "제주특별자치도 제주시 삼성로 22", "제주시", null, null, List.of(), List.of(), null, 7));
        c.add(p(48, 1658279L, "제주목 관아", "Landmarks and Outdoors > Tourist Attraction", 33.51367249, 126.52205196,
                "제주특별자치도 제주시 관덕로 25", "제주시", null, null, List.of(), List.of(), null, 4));
        return List.copyOf(c);
    }

    /**
     * 로그(2b95a44f)와 최종 스텝에서 역산한 Sonnet 선택. day3→day4 지리 재배치, 유형 상한 트림,
     * 거리 트림 대상이 모두 원래 선택에 있었다는 점을 반영했다. "제주도 국가지질공원"(11)은 Sonnet이
     * day2에 골랐지만 실행기의 stripZoneLevelPicks가 spare로 뺀 상태로 둔다(RouteOptimizer 입력 기준).
     */
    static SelectionOutput selection() {
        return new SelectionOutput(
                "제주 로컬 맛집을 따라가는 커플 미식 여행",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(8, 16, 17, 18, 19), null, null),
                        new SelectionOutput.DayPlan(2, null, List.of(9, 20, 10, 21), null, null),
                        new SelectionOutput.DayPlan(3, null, List.of(12, 13, 14, 22), null, null),
                        new SelectionOutput.DayPlan(4, null, List.of(23, 25, 15, 26, 24), null, null)
                ),
                List.of(),
                List.of(27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 11),
                List.of(), List.of()
        );
    }

    private static PlaceCandidate p(int index, long placeId, String name, String category,
                                    double lat, double lng, String address, String subRegion,
                                    Double rating, Integer durationMinutes, List<String> timeSlots,
                                    List<String> tags, Integer admissionFee, Integer wikidataSitelinks) {
        return new PlaceCandidate(
                index, placeId, name, address, category,
                tags, "Jeju", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                null,
                rating != null ? BigDecimal.valueOf(rating) : null,
                0.9, null, null, timeSlots.isEmpty() ? null : timeSlots, durationMinutes, false,
                admissionFee, subRegion, null, wikidataSitelinks);
    }
}
