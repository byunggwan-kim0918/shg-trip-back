package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction.*;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceRestrictionsTest {

    private static final String RESTAURANT = "Dining and Drinking > Restaurant > Korean Restaurant";
    private static final String GENERIC_SIGHT = "Landmarks and Outdoors";

    private static PlaceCandidate place(String name, String category, List<String> tags, String cuisine,
                                        String role, PlaceAttributes attributes) {
        return new PlaceCandidate(1, 1L, name, "주소", category, tags, "Busan", "KR", BigDecimal.ONE, BigDecimal.ONE,
                null, null, 0.8, null, null, null, null, false, null, null, null, null, role, cuisine, false, null,
                attributes, null);
    }

    private static PlaceCandidate dining(String name, String cuisine, String... tags) {
        return place(name, RESTAURANT, List.of(tags), cuisine, "MEAL", null);
    }

    private static PlaceCandidate sight(String name, String category) {
        return place(name, category, List.of(), null, "SIGHT", null);
    }

    private static PlaceAttributes hike(String level) {
        return new PlaceAttributes(null, null, null, level, null, null, null);
    }

    private static PlaceCandidate staying(PlaceCandidate c, int minutes) {
        return new PlaceCandidate(c.index(), c.placeId(), c.name(), c.address(), c.category(), c.tags(), c.region(),
                c.country(), c.latitude(), c.longitude(), null, null, 0.8, null, null, null, minutes, false, null, null,
                null, null, c.placeRole(), c.cuisine(), false, null, c.attributes(), null);
    }

    private static PlaceAttributes ingredients(String... kinds) {
        return new PlaceAttributes("MEAL", null, null, null, null, null, null, List.of(kinds));
    }

    private static PlaceAttributes type(String subType) {
        return new PlaceAttributes(null, subType, null, null, null, null, null);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // 보강이 이름의 요리를 놓쳐 한식·일식으로만 남은 식당
                Arguments.of(SEAFOOD, dining("여수명동게장", "KOREAN_SET", "식당", "한식"), true),
                Arguments.of(SEAFOOD, dining("경주횟집 지중해", "KOREAN_SET", "식당", "한식"), true),
                Arguments.of(SEAFOOD, dining("미카도스시 전주전북도청점", "JAPANESE", "식당", "일식", "초밥"), true),
                Arguments.of(SEAFOOD, dining("해변짚불곰장어", "MEAT", "식당", "한식", "돼지고기", "해산물"), true),
                Arguments.of(SEAFOOD, dining("장유요리사횟집", "SEAFOOD", "식당", "해산물"), true),
                Arguments.of(SEAFOOD, dining("도야 짬뽕", "NOODLE", "식당", "짬뽕", "면요리"), true),
                Arguments.of(SEAFOOD, dining("무교동낙지", "KOREAN_SET", "식당", "한식"), true),
                Arguments.of(SEAFOOD, dining("タコタコキング アメリカ村店", "JAPANESE", "식당", "타코야키", "일식"), true),
                Arguments.of(SEAFOOD, dining("명동교자", "NOODLE", "식당", "칼국수", "면요리"), false),
                Arguments.of(SEAFOOD, dining("원조바지락칼국수", "NOODLE", "식당", "칼국수", "면요리"), true),
                Arguments.of(SEAFOOD, dining("벌교꼬막정식", "KOREAN_SET", "식당", "한식"), true),
                Arguments.of(SEAFOOD, dining("안목항물회", "KOREAN_SET", "식당", "한식"), true),
                Arguments.of(SEAFOOD, dining("경주얼굴빵", null, "베이커리", "빵"), false),
                Arguments.of(SEAFOOD, dining("보물회관", "KOREAN_SET", "식당", "한식"), false),
                Arguments.of(SEAFOOD, dining("흑돈가", "MEAT", "식당", "돼지고기"), false),
                Arguments.of(SEAFOOD, place("자갈치시장", "Retail > Market", List.of("해산물"), null, "SIGHT", null), false),
                Arguments.of(RAW, dining("영육일삼", null, "육회", "해산물", "한식"), true),
                Arguments.of(RAW, dining("시민초밥", "JAPANESE", "식당", "일식", "초밥"), true),
                Arguments.of(RAW, dining("삼성혈해물탕", null, "해물탕", "해산물", "식당"), false),
                Arguments.of(PORK, dining("흑돈가", "MEAT", "식당", "돼지고기"), true),
                Arguments.of(PORK, dining("한우명가", "MEAT", "식당", "소고기"), false),
                Arguments.of(PORK, dining("원조갈비", "MEAT", "식당", "한식"), true),
                Arguments.of(BEEF, dining("원조갈비", "MEAT", "식당", "한식"), true),
                Arguments.of(PORK, place("Touyoutei Istan", "Dining and Drinking > Restaurant > Steakhouse",
                        List.of("식당"), "MEAT", "MEAL", null), false),
                Arguments.of(BEEF, dining("흑돈가", "MEAT", "식당", "돼지고기"), false),
                Arguments.of(PORK, place("고깃집", "Dining and Drinking > Restaurant > Korean BBQ Restaurant",
                        List.of(), null, "MEAL", null), true),
                Arguments.of(CHICKEN, place("교촌치킨", "Dining and Drinking > Restaurant > Fried Chicken Joint",
                        List.of(), "OTHER", "MEAL", null), true),
                Arguments.of(MEAT, dining("흑돈가", "MEAT", "식당", "돼지고기"), true),
                Arguments.of(MEAT, dining("명동교자", "NOODLE", "식당", "칼국수", "면요리"), false),
                Arguments.of(NIGHTLIFE, place("광안리 포차", RESTAURANT, List.of("포차"), null, "BAR", null), true),
                Arguments.of(NIGHTLIFE, place("Bar Liquor", "Dining and Drinking > Bar > Pub", List.of(), null, null,
                        null), true),
                Arguments.of(NIGHTLIFE, dining("흑돈가", "MEAT", "식당", "돼지고기"), false),
                Arguments.of(HIKING, sight("한라산", "Landmarks and Outdoors > Mountain"), true),
                Arguments.of(HIKING, sight("용눈이오름", GENERIC_SIGHT), false),
                Arguments.of(HIKING, staying(place("성산일출봉", GENERIC_SIGHT, List.of("오름", "트레킹"), null, "SIGHT", null),
                        90), false),
                Arguments.of(HIKING, staying(place("한라산 성판악", GENERIC_SIGHT, List.of("등산"), null, "SIGHT", null),
                        420), true),
                Arguments.of(HIKING, staying(place("곶자왈도립공원", "Landmarks and Outdoors > Park", List.of("트레킹"), null,
                        "SIGHT", null), 90), false),
                Arguments.of(NIGHTLIFE, sight("해바라기 길", "Arts and Entertainment > Public Art"), false),
                Arguments.of(HIKING, place("한라산", "Landmarks and Outdoors > Mountain", List.of(), null, "SIGHT",
                        hike("NONE")), false),
                Arguments.of(HIKING, place("성산일출봉", GENERIC_SIGHT, List.of(), null, "SIGHT", hike("EASY")), false),
                Arguments.of(HIKING, place("올레 7코스", "Landmarks and Outdoors > Trail", List.of("트레킹"), null, "SIGHT",
                        hike("LONG")), false),
                Arguments.of(HIKING, place("한라산 성판악", GENERIC_SIGHT, List.of("등산"), null, "SIGHT", hike("LONG")),
                        true),
                Arguments.of(HIKING, place("한라산 국립공원", "Landmarks and Outdoors > Park > National Park", List.of(),
                        null, "SIGHT", new PlaceAttributes(null, "landmark", null, "LONG", null, null, null)), true),
                Arguments.of(HIKING, staying(place("경주국립공원", "Landmarks and Outdoors > Park > National Park",
                        List.of("산책로", "트레킹"), null, "SIGHT", null), 240), false),
                Arguments.of(HIKING, staying(place("쪼른숲길", "Landmarks and Outdoors > Trail > Hiking Trail",
                        List.of("트레킹", "숲길"), null, "SIGHT", null), 180), false),
                Arguments.of(TREKKING, staying(place("쪼른숲길", "Landmarks and Outdoors > Trail > Hiking Trail",
                        List.of("트레킹", "숲길"), null, "SIGHT", null), 180), true),
                Arguments.of(TREKKING, place("올레 7코스", "Landmarks and Outdoors > Trail", List.of("트레킹"), null,
                        "SIGHT", hike("LONG")), true),
                Arguments.of(TREKKING, staying(place("이기대 해안산책로", "Landmarks and Outdoors > Trail > Hiking Trail",
                        List.of("산책로"), null, "SIGHT", null), 60), false),
                Arguments.of(TREKKING, staying(place("성산일출봉", GENERIC_SIGHT, List.of("오름", "트레킹"), null, "SIGHT",
                        null), 90), false),
                Arguments.of(HIKING, place("한라산 게스트하우스", "Travel and Transportation > Lodging > Hostel",
                        List.of(), null, null, null), false),
                Arguments.of(THEME_PARK, sight("롯데월드 어드벤처", "Arts and Entertainment > Amusement Park"), true),
                Arguments.of(SHOPPING, sight("신세계 센텀시티", "Retail > Department Store"), true),
                Arguments.of(SHOPPING, sight("동문시장", "Retail > Market"), false),
                Arguments.of(MUSEUM, sight("국립중앙박물관", "Arts and Entertainment > Museum > History Museum"), true),
                Arguments.of(RELIGIOUS, sight("불국사", "Community and Government > Spiritual Center > Buddhist Temple"),
                        true),
                Arguments.of(ZOO_AQUARIUM, sight("아쿠아플라넷 제주", "Arts and Entertainment > Aquarium"), true),
                Arguments.of(MUSEUM, sight("성산일출봉", GENERIC_SIGHT), false),
                Arguments.of(MUSEUM, place("섭지코지", GENERIC_SIGHT, List.of(), null, "SIGHT", hike("EASY")), false),
                Arguments.of(NIGHTLIFE, place("광안리 바다포차", RESTAURANT, List.of("포차", "술집"), null, "MEAL", null),
                        true),
                Arguments.of(NIGHTLIFE, place("Domaine", "Dining and Drinking > Winery", List.of(), null, null, null),
                        true),
                Arguments.of(RELIGIOUS, place("寺町京極商店街", GENERIC_SIGHT, List.of(), null, "SIGHT", type("street")),
                        false),
                Arguments.of(THEME_PARK, place("롯데월드", GENERIC_SIGHT, List.of("놀이공원"), null, "SIGHT", null), true),
                Arguments.of(ZOO_AQUARIUM, place("아쿠아플라넷 제주", GENERIC_SIGHT, List.of("수족관"), null, "SIGHT", null),
                        true),
                Arguments.of(RAW, dining("진미간장게장", "SEAFOOD", "간장게장", "해산물"), true),
                Arguments.of(RAW, dining("체육회관 식당", "KOREAN_SET", "식당", "한식"), false),
                Arguments.of(RAW, dining("시장 가게장터 국밥", "SOUP", "식당", "국밥"), false),
                Arguments.of(THEME_PARK, place("월미도", GENERIC_SIGHT, List.of("놀이공원", "바다"), null, "SIGHT",
                        type("street")), false),
                Arguments.of(SEAFOOD, place("부산역", "Travel and Transportation > Transport Hub > Train Station",
                        List.of("해산물"), null, null, null), false),
                // 보강 주재료는 요리 형태로만 분류된 식당의 고기 종류를 채우고, 고기 종류를 알면 구이 추정을 하지 않는다
                Arguments.of(BEEF, place("큰집 설렁탕", RESTAURANT, List.of("식당", "설렁탕", "탕·국밥"), "SOUP", "MEAL",
                        ingredients("BEEF")), true),
                Arguments.of(BEEF, place("큰집 설렁탕", RESTAURANT, List.of("식당", "설렁탕", "탕·국밥"), "SOUP", "MEAL",
                        null), true),
                Arguments.of(PORK, place("큰집 설렁탕", RESTAURANT, List.of("식당", "설렁탕", "탕·국밥"), "SOUP", "MEAL",
                        ingredients("BEEF")), false),
                Arguments.of(BEEF, place("명가갈비", "Dining and Drinking > Restaurant > Steakhouse", List.of("식당", "갈비"),
                        "MEAT", "MEAL", ingredients("PORK")), false),
                Arguments.of(PORK, place("명가갈비", "Dining and Drinking > Restaurant > Steakhouse", List.of("식당", "갈비"),
                        "MEAT", "MEAL", ingredients("PORK")), true),
                Arguments.of(PORK, place("한일관", RESTAURANT, List.of("식당", "불고기", "한식"), "KOREAN_SET", "MEAL",
                        null), true),
                Arguments.of(PORK, place("한일관", RESTAURANT, List.of("식당", "불고기", "한식"), "KOREAN_SET", "MEAL",
                        ingredients("BEEF")), false),
                Arguments.of(PORK, place("조개구이 명가", "Dining and Drinking > Restaurant > Korean BBQ Restaurant",
                        List.of("식당", "조개구이"), null, "MEAL", ingredients("SEAFOOD")), false),
                Arguments.of(MEAT, place("조개구이 명가", "Dining and Drinking > Restaurant > Korean BBQ Restaurant",
                        List.of("식당", "조개구이"), null, "MEAL", ingredients("SEAFOOD")), false),
                Arguments.of(MEAT, place("조개구이 명가", "Dining and Drinking > Restaurant > Korean BBQ Restaurant",
                        List.of("식당", "조개구이"), null, "MEAL", null), true),
                Arguments.of(MEAT, place("숯불 명가", "Dining and Drinking > Restaurant > Korean BBQ Restaurant",
                        List.of("식당"), "MEAT", "MEAL", ingredients("SEAFOOD")), true),
                Arguments.of(MEAT, place("신장 양꼬치", RESTAURANT, List.of("식당", "양꼬치"), "CHINESE", "MEAL",
                        ingredients("LAMB")), true),
                Arguments.of(MEAT, place("제주 말고기", RESTAURANT, List.of("식당", "말고기"), "KOREAN_SET", "MEAL",
                        ingredients("OTHER_MEAT")), true),
                Arguments.of(MEAT, place("하남오리탕", RESTAURANT, List.of("식당", "오리탕"), "SOUP", "MEAL",
                        ingredients("DUCK")), true),
                Arguments.of(PORK, place("하남오리탕", RESTAURANT, List.of("식당", "오리탕"), "SOUP", "MEAL",
                        ingredients("DUCK")), false),
                Arguments.of(CHICKEN, place("영미식당", RESTAURANT, List.of("식당", "한식"), "KOREAN_SET", "MEAL",
                        ingredients("CHICKEN")), true),
                Arguments.of(SEAFOOD, place("영미식당", RESTAURANT, List.of("식당", "한식"), "KOREAN_SET", "MEAL",
                        ingredients("SEAFOOD")), true),
                Arguments.of(PORK, place("원조갈비", RESTAURANT, List.of("식당", "한식"), "MEAT", "MEAL", ingredients()),
                        true),
                // 고기 갈래 태그가 없으면 이름·태그의 고기 낱말로 돼지·소·닭을 나눈다
                Arguments.of(BEEF, place("터줏집돼지국밥", "Dining and Drinking > Restaurant",
                        List.of("식당", "돼지국밥", "탕·국밥"), "SOUP", "MEAL", null), false),
                Arguments.of(PORK, place("터줏집돼지국밥", "Dining and Drinking > Restaurant",
                        List.of("식당", "돼지국밥", "탕·국밥"), "SOUP", "MEAL", null), true),
                Arguments.of(CHICKEN, place("춘천명동닭갈비", "Dining and Drinking > Restaurant",
                        List.of("식당", "닭갈비", "한식"), "KOREAN_SET", "MEAL", null), true),
                Arguments.of(PORK, place("춘천명동닭갈비", "Dining and Drinking > Restaurant",
                        List.of("식당", "닭갈비", "한식"), "KOREAN_SET", "MEAL", null), false),
                Arguments.of(MEAT, place("장안닭갈비", "Dining and Drinking > Restaurant > Comfort Food Restaurant",
                        List.of("식당", "닭갈비", "한식"), "KOREAN_SET", "MEAL", null), true),
                Arguments.of(BEEF, place("제주오광흑돼지", "Dining and Drinking > Restaurant > Steakhouse",
                        List.of("식당", "흑돼지", "돼지고기"), "MEAT", "MEAL", null), false),
                Arguments.of(PORK, place("호탕한갈비탕", RESTAURANT, List.of("식당", "탕·국밥", "한식"), "SOUP", "MEAL",
                        null), false),
                Arguments.of(BEEF, place("호탕한갈비탕", RESTAURANT, List.of("식당", "탕·국밥", "한식"), "SOUP", "MEAL",
                        null), true),
                // 공용 분류가 국물·일식으로 세는 돼지·닭 요리
                Arguments.of(PORK, place("참이맛감자탕 인천직영점", RESTAURANT, List.of("식당", "탕·국밥"), null, "MEAL",
                        null), true),
                Arguments.of(PORK, place("돈카츠 서황", RESTAURANT, List.of("돈카츠", "일식당"), null, "MEAL", null), true),
                Arguments.of(CHICKEN, dining("토속촌 삼계탕", "SOUP", "삼계탕", "탕·국밥"), true),
                Arguments.of(MEAT, dining("토속촌 삼계탕", "SOUP", "삼계탕", "탕·국밥"), true),
                // 갈래 태그는 리프·구이 추정만 대신하고 이름에 적힌 고기는 늘 본다
                Arguments.of(PORK, dining("원조치킨족발", "MEAT", "치킨", "족발", "닭고기"), true),
                Arguments.of(BEEF, dining("흑돼지한우명가", "MEAT", "흑돼지", "돼지고기"), true),
                // 시장은 보강이 먹는 곳으로 두어도 식당 조건으로 빼지 않는다
                Arguments.of(SEAFOOD, place("자갈치시장", "Retail > Market", List.of("해산물"), null, "MARKET",
                        new PlaceAttributes("MEAL", "market", null, null, null, null, null)), false),
                // 역할이 없으면 보강 venue로 끼니 후보가 되므로 식당 조건으로 본다
                Arguments.of(SEAFOOD, place("자갈치시장", "Retail > Market", List.of("해산물"), null, null,
                        new PlaceAttributes("MEAL", "market", null, null, null, null, null)), true),
                Arguments.of(BEEF, place("제주오광흑돼지", "Dining and Drinking > Restaurant > Steakhouse", List.of(),
                        "MEAT", "MEAL", null), false),
                Arguments.of(BEEF, place("Kobe Teppan", "Dining and Drinking > Restaurant > Steakhouse", List.of(), null,
                        "MEAL", null), true),
                Arguments.of(SEAFOOD, place("니시키시장", "Retail > Market", List.of("해산물"), null, "SIGHT",
                        new PlaceAttributes("SNACK", "market", null, null, null, null, null)), false),
                Arguments.of(NIGHTLIFE, place("광안리 포차거리", "Event > Marketplace > Street Food Gathering",
                        List.of("포차"), null, "MARKET", new PlaceAttributes("MEAL", "market", null, null, null, null,
                                null)), false),
                Arguments.of(NIGHTLIFE, place("부산역 포차", "Event > Marketplace > Street Food Gathering",
                        List.of("포장마차", "야시장"), null, "MARKET",
                        new PlaceAttributes("MEAL", "market", null, "NONE", null, null, null)), false),
                Arguments.of(NIGHTLIFE, place("お好み焼き居酒屋 現", "Dining and Drinking > Restaurant > Asian Restaurant"
                        + " > Japanese Restaurant > Okonomiyaki Restaurant", List.of("식당", "오코노미야키", "일식"),
                        "JAPANESE", "MEAL", null), true),
                Arguments.of(NIGHTLIFE, place("Izakaya Zachimaru Kamaboko", "Dining and Drinking > Restaurant"
                        + " > Spanish Restaurant > Tapas Restaurant", List.of("식당", "가마보코", "해산물", "일식"),
                        "SEAFOOD", "MEAL", null), true),
                Arguments.of(NIGHTLIFE, place("WITTAMER Chocolat Bar", "Retail > Food and Beverage Retail > Candy Store",
                        List.of("초콜릿", "디저트"), null, "SHOPPING", null), false),
                Arguments.of(SHOPPING, place("Taketorado", "Retail > Souvenir Store", List.of("쇼핑", "기념품"), null,
                        "SHOPPING", null), true),
                Arguments.of(SHOPPING, place("안경거리", "Landmarks and Outdoors > Other Great Outdoors",
                        List.of("안경거리", "쇼핑거리"), null, "SHOPPING", null), true),
                Arguments.of(SHOPPING, place("라라브레드 여수점", "Retail > Food and Beverage Retail",
                        List.of("빵집", "베이커리"), null, "SHOPPING", null), false),
                // 요리 이름이 재료를 정한다. 보강 태그가 다른 고기를 말해도 그렇다
                Arguments.of(BEEF, place("수복찜갈비", "Dining and Drinking > Restaurant > Steakhouse",
                        List.of("식당", "찜갈비", "돼지고기"), "MEAT", "MEAL", null), true),
                Arguments.of(BEEF, dining("늘곰탕", "SOUP", "식당", "한식", "탕·국밥"), true),
                Arguments.of(MEAT, dining("일품양평해장국대릉원점", "SOUP", "식당", "한식", "탕·국밥"), true),
                Arguments.of(MEAT, dining("삼백집 콩나물해장국", "SOUP", "식당", "한식", "탕·국밥"), false),
                Arguments.of(MEAT, dining("해밀진주냉면", "NOODLE", "식당", "면요리"), true),
                Arguments.of(MEAT, dining("동경라멘학교", "JAPANESE", "식당", "일식"), true),
                Arguments.of(PORK, dining("해밀진주냉면", "NOODLE", "식당", "면요리"), false),
                Arguments.of(PORK, dining("올래국수 고기국수", "NOODLE", "식당", "면요리"), true),
                Arguments.of(PORK, place("스카이 제일막창", RESTAURANT, List.of("식당"), "CHINESE", "MEAL", null), true),
                Arguments.of(PORK, place("소곱창 명가", RESTAURANT, List.of("식당", "소고기"), "MEAT", "MEAL", null), false));
    }

    @Test
    @DisplayName("조건 종류마다 걸리는 사례가 있다")
    void everyRestrictionHasHitCase() {
        Set<Object> covered = cases().filter(a -> Boolean.TRUE.equals(a.get()[2])).map(a -> a.get()[0])
                .collect(Collectors.toSet());
        assertThat(covered).containsAll(EnumSet.allOf(PlaceRestriction.class));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void judgesFromStoredFieldsAndSharedClassifiers(PlaceRestriction restriction, PlaceCandidate c, boolean hit) {
        assertThat(PlaceRestrictions.of(List.of(restriction)).violatedBy(c)).isEqualTo(hit ? restriction : null);
    }

    @Test
    @DisplayName("검색 결과는 체류 시간이 없어 보강 난이도가 없는 등산은 후보 단계로 미룬다")
    void searchStageDefersHikingWithoutLevel() {
        PlaceRestrictions hiking = PlaceRestrictions.of(List.of(HIKING));
        VectorSearchResult trail = new VectorSearchResult(1L, "이기대 해안산책로", "주소",
                "Landmarks and Outdoors > Trail > Hiking Trail", List.of("산책로"), "Busan", "KR", BigDecimal.ONE,
                BigDecimal.ONE, null, null, null, null, 0.8, "SIGHT", null, null, null);
        VectorSearchResult longHike = new VectorSearchResult(2L, "한라산", "주소", "Landmarks and Outdoors > Mountain",
                List.of("등산"), "Jeju", "KR", BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, 0.8, "SIGHT",
                null, null, hike("LONG"));

        assertThat(hiking.violatedBy(trail)).isNull();
        assertThat(hiking.violatedBy(longHike)).isEqualTo(HIKING);
        assertThat(hiking.violatedBy(staying(place("이기대 해안산책로", "Landmarks and Outdoors > Trail > Hiking Trail",
                List.of("산책로"), null, "SIGHT", null), 60))).isNull();
    }

    @Test
    @DisplayName("사용자가 고른 장소는 해당해도 남기고, 조건이 없으면 목록을 그대로 둔다")
    void keepsUserSelected() {
        PlaceCandidate crab = dining("여수명동게장", "KOREAN_SET", "식당", "한식");
        PlaceCandidate chosen = dining("장유요리사횟집", "SEAFOOD", "식당", "해산물").asUserSelected();
        List<PlaceCandidate> pool = List.of(crab, chosen);

        assertThat(PlaceRestrictions.of(List.of(SEAFOOD)).exclude(pool)).containsExactly(chosen);
        assertThat(PlaceRestrictions.NONE.exclude(pool)).isSameAs(pool);
    }

    @Test
    @DisplayName("출처별 행 중 한쪽에만 요리 분류가 있어도 같은 장소로 보이는 행은 함께 뺀다")
    void excludesTwinOfRestrictedRow() {
        PlaceCandidate tagged = at(dining("할매집", "SEAFOOD", "식당", "해산물"), 11L, 35.1631, 129.1636);
        PlaceCandidate untagged = at(dining("할매집", "KOREAN_SET", "식당", "한식"), 12L, 35.1634, 129.1639);
        PlaceCandidate sameBuilding = at(dining("명동교자", "NOODLE", "식당", "칼국수"), 13L, 35.1631, 129.1636);
        PlaceCandidate chosenTwin = at(dining("할매집", "KOREAN_SET", "식당"), 14L, 35.1632, 129.1637).asUserSelected();

        assertThat(PlaceRestrictions.of(List.of(SEAFOOD)).exclude(List.of(tagged, untagged, sameBuilding, chosenTwin)))
                .containsExactly(sameBuilding, chosenTwin);
    }

    @Test
    @DisplayName("같은 이름이어도 대분류가 다른 시장과 영문 병기가 붙은 다른 표기는 남기고, 같은 이름 행은 멀어도 함께 뺀다")
    void twinPropagationScope() {
        PlaceCandidate seafoodRow = at(dining("자갈치시장", "SEAFOOD", "식당", "해산물"), 21L, 35.0966, 129.0306);
        PlaceCandidate market = at(place("자갈치시장", "Retail > Market", List.of("해산물"), null, "MARKET", null), 22L,
                35.0967, 129.0307);
        PlaceCandidate tagged = at(dining("할매집", "SEAFOOD", "식당", "해산물"), 23L, 35.1631, 129.1636);
        PlaceCandidate spaced = at(dining("할매 집", "KOREAN_SET", "식당"), 24L, 35.1632, 129.1637);
        PlaceCandidate farBranch = at(dining("할매집", "KOREAN_SET", "식당"), 25L, 35.2500, 129.2200);
        PlaceCandidate bilingual = at(dining("할매집 Halmae House", "KOREAN_SET", "식당"), 26L, 35.1633, 129.1638);

        assertThat(PlaceRestrictions.of(List.of(SEAFOOD))
                .exclude(List.of(seafoodRow, market, tagged, spaced, farBranch, bilingual)))
                .extracting(PlaceCandidate::placeId).containsExactly(22L, 26L);
    }

    @Test
    @DisplayName("박물관 기관 항목에 걸린 조건은 같은 곳의 장소 항목(궁궐)으로 옮기지 않는다")
    void keepsSiteOfRestrictedInstitution() {
        PlaceCandidate palace = item(41L, "故宫", "Landmarks and Outdoors > Palace", 39.9163, 116.3972, 106);
        PlaceCandidate museum = item(42L, "故宫博物院", "Arts and Entertainment > Museum", 39.9180, 116.3970, 20);

        assertThat(PlaceIdentity.sameAs(palace, museum)).isPresent();
        assertThat(PlaceRestrictions.of(List.of(MUSEUM)).exclude(List.of(palace, museum))).containsExactly(palace);
    }

    private static PlaceCandidate item(long id, String name, String category, double lat, double lng, int sitelinks) {
        return new PlaceCandidate(1, id, name, "주소", category, List.of(), "Beijing", "CN", BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, null, false, null, null, null, sitelinks,
                "SIGHT", null, false, null, null, null);
    }

    private static PlaceCandidate at(PlaceCandidate c, long id, double lat, double lng) {
        return new PlaceCandidate(c.index(), id, c.name(), c.address(), c.category(), c.tags(), c.region(),
                c.country(), BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, null,
                false, null, null, null, null, c.placeRole(), c.cuisine(), false, null, c.attributes(), null);
    }

    @Test
    @DisplayName("요리 분류도 종류 태그도 없는 식당만 종류 미상으로 본다")
    void unknownFood() {
        PlaceRestrictions seafood = PlaceRestrictions.of(List.of(SEAFOOD));

        assertThat(seafood.isUnknownFood(result("이름없는식당", null, List.of("식당", "한식")))).isTrue();
        assertThat(seafood.isUnknownFood(result("명동교자", "NOODLE", List.of("식당", "칼국수")))).isFalse();
        assertThat(seafood.isUnknownFood(result("가게", null, List.of("칼국수")))).isFalse();
        assertThat(seafood.isUnknownFood(new VectorSearchResult(3L, "이름없는식당", "주소", RESTAURANT, List.of("식당"),
                "Busan", "KR", BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, 0.8, "MEAL", null, null,
                ingredients("BEEF")))).isFalse();
        assertThat(PlaceRestrictions.of(List.of(HIKING))
                .isUnknownFood(result("이름없는식당", null, List.of("식당")))).isFalse();
        assertThat(seafood.isUnknownFood(new VectorSearchResult(2L, "카페 소요", "주소", "Dining and Drinking > Cafe",
                List.of(), "Busan", "KR", BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, 0.8, "CAFE", null,
                null, null))).isFalse();
    }

    @Test
    @DisplayName("피할 이름을 품은 이름만 같은 곳으로 본다. 더 짧은 이름·두 글자 미만·다른 이름은 보지 않는다")
    void avoidedNames() {
        assertThat(TripExclusions.namesAvoided("우도면", List.of("우도"))).isTrue();
        assertThat(TripExclusions.namesAvoided("경포해수욕장", List.of("경포해변"))).isTrue();
        assertThat(TripExclusions.namesAvoided("서빈백사 (우도)", List.of("우도"))).isTrue();
        assertThat(TripExclusions.namesAvoided("성산일출봉", List.of("우도"))).isFalse();
        assertThat(TripExclusions.namesAvoided("우도", null)).isFalse();
        assertThat(TripExclusions.namesAvoided("산", List.of("남산"))).isFalse();
        assertThat(TripExclusions.namesAvoided("우도", List.of("우도봉"))).isFalse();
        assertThat(TripExclusions.namesAvoided("돼지국밥", List.of("쌍둥이돼지국밥"))).isFalse();
        assertThat(TripExclusions.namesAvoided("남산·N서울타워", List.of("남산N서울타워"))).isTrue();
    }

    private static VectorSearchResult result(String name, String cuisine, List<String> tags) {
        return new VectorSearchResult(1L, name, "주소", RESTAURANT, tags, "Busan", "KR", BigDecimal.ONE,
                BigDecimal.ONE, null, null, null, null, 0.8, "MEAL", cuisine, null, null);
    }
}
