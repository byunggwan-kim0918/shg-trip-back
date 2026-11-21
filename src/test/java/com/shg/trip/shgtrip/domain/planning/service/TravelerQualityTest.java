package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 여행자 관점 장소 판정 규칙(다인실 숙소·동행·카페·배로만 가는 섬)을 작은 입력으로 검사한다. */
class TravelerQualityTest {

    @Nested
    @DisplayName("다인실 숙소 판정")
    class DormLodging {

        @Test
        void hostelCategoryIsDorm() {
            assertThat(PlaceCategoryConstants.isDormLodging("소낭",
                    "Travel and Transportation > Lodging > Hostel", List.of())).isTrue();
        }

        @Test
        void guesthouseNameIsDorm() {
            assertThat(PlaceCategoryConstants.isDormLodging("바다게스트하우스",
                    "Travel and Transportation > Lodging", List.of())).isTrue();
        }

        @Test
        @DisplayName("총칭 Lodging은 태그로 판정한다")
        void genericLodgingUsesTags() {
            assertThat(PlaceCategoryConstants.isDormLodging("물결그림",
                    "Travel and Transportation > Lodging", List.of("제주 숙박", "게스트하우스"))).isTrue();
        }

        @Test
        @DisplayName("호텔은 태그에 게스트하우스가 있어도 다인실이 아니다 (제주R호텔 실측)")
        void hotelWithGuesthouseTagIsNotDorm() {
            assertThat(PlaceCategoryConstants.isDormLodging("제주R호텔",
                    "Travel and Transportation > Lodging > Hotel", List.of("호텔", "게스트하우스"))).isFalse();
        }

        @Test
        void pensionIsNotDorm() {
            assertThat(PlaceCategoryConstants.isDormLodging("펜션연리",
                    "Travel and Transportation > Lodging", List.of("제주펜션", "커플여행"))).isFalse();
        }
    }

    @Nested
    @DisplayName("동행 반영")
    class Companion {

        @Test
        @DisplayName("커플이면 숙소 슬롯 질의에 게스트하우스가 들어가지 않는다")
        void coupleLodgingQuery() {
            List<SearchQueryBuilder.SearchSlot> slots = SearchQueryBuilder.buildSearchSlots(input(
                    VectorEnrichedInput.CompanionType.COUPLE, 2));
            SearchQueryBuilder.SearchSlot lodging = slots.stream()
                    .filter(s -> s.role() == PlaceCategoryConstants.SearchRole.ACCOMMODATION)
                    .findFirst().orElseThrow();
            assertThat(lodging.queryText()).doesNotContain("게스트하우스").contains("펜션");
        }

        @Test
        @DisplayName("동행을 모르면 기존 숙소 질의를 그대로 쓴다")
        void unknownCompanionKeepsDefaultQuery() {
            List<SearchQueryBuilder.SearchSlot> slots = SearchQueryBuilder.buildSearchSlots(input(
                    VectorEnrichedInput.CompanionType.UNKNOWN, null));
            SearchQueryBuilder.SearchSlot lodging = slots.stream()
                    .filter(s -> s.role() == PlaceCategoryConstants.SearchRole.ACCOMMODATION)
                    .findFirst().orElseThrow();
            assertThat(lodging.queryText()).contains(PlaceCategoryConstants.userCategoryQuery("accommodation"));
        }

        @Test
        @DisplayName("enrich 값이 이상하면 UNKNOWN으로 떨어진다")
        void companionParseIsLenient() {
            assertThat(VectorEnrichedInput.CompanionType.parse("couple"))
                    .isEqualTo(VectorEnrichedInput.CompanionType.COUPLE);
            assertThat(VectorEnrichedInput.CompanionType.parse("연인"))
                    .isEqualTo(VectorEnrichedInput.CompanionType.UNKNOWN);
            assertThat(VectorEnrichedInput.CompanionType.parse(null))
                    .isEqualTo(VectorEnrichedInput.CompanionType.UNKNOWN);
        }

        @Test
        @DisplayName("2인이면 식사·카페·입장료는 2배, 숙소·교통은 그대로")
        void costScalesForParty() {
            StepData meal = step("식당", "Dining and Drinking > Restaurant", 13000,
                    List.of(new AlternativeData("대안식당", "a", "Dining and Drinking > Restaurant",
                            "Jeju", "KR", null, BigDecimal.valueOf(13000))));
            StepData lodging = step("펜션", "Travel and Transportation > Lodging", 120000, List.of());

            List<StepData> scaled = PartyCosts.scale(List.of(meal, lodging), 2);

            assertThat(scaled.get(0).estimatedCost()).isEqualByComparingTo("26000");
            assertThat(scaled.get(0).alternatives().get(0).estimatedCost()).isEqualByComparingTo("26000");
            assertThat(scaled.get(0).transportationCost()).isEqualByComparingTo("1000");
            assertThat(scaled.get(1).estimatedCost()).isEqualByComparingTo("120000");
        }

        @Test
        void unknownPartySizeKeepsCost() {
            StepData meal = step("식당", "Dining and Drinking > Restaurant", 13000, List.of());
            assertThat(PartyCosts.scale(List.of(meal), null).get(0).estimatedCost())
                    .isEqualByComparingTo("13000");
        }
    }

    @Nested
    @DisplayName("카페형 판정")
    class CafeLike {

        @Test
        @DisplayName("식당으로 적재된 베이커리는 카페형이다")
        void bakeryStoredAsRestaurant() {
            assertThat(PlaceCategoryConstants.isCafeLike("수애기베이커리", "Dining and Drinking > Restaurant",
                    List.of("베이커리", "카페", "빵"))).isTrue();
        }

        @Test
        @DisplayName("이름에 카페가 들어간 한식당은 카페형이 아니다 (카페리·카페서연의집)")
        void restaurantNamedCafe() {
            assertThat(PlaceCategoryConstants.isCafeLike("카페리", "Dining and Drinking > Restaurant",
                    List.of("제주 음식", "레스토랑", "식사"))).isFalse();
            assertThat(PlaceCategoryConstants.isCafeLike("카페서연의집", "Dining and Drinking > Restaurant",
                    List.of("제주카페", "한식당", "식사"))).isFalse();
        }

        @Test
        void ordinaryRestaurantIsNotCafe() {
            assertThat(PlaceCategoryConstants.isCafeLike("형돈", "Dining and Drinking > Restaurant",
                    List.of("한식", "로컬 맛집"))).isFalse();
        }

        @Test
        void cafeCategoryIsCafe() {
            assertThat(PlaceCategoryConstants.isCafeLike("쉼표",
                    "Dining and Drinking > Cafe, Coffee, and Tea House > Coffee Shop", List.of())).isTrue();
        }
    }

    @Nested
    @DisplayName("도선 섬 제외")
    class FerryArea {

        private final List<PlaceCandidate> pool = List.of(
                addressed(1, "섬소나이 우도본점", "제주특별자치도 제주시 우도면 우도해안길 814"),
                addressed(2, "형돈", "제주특별자치도 제주시 구남로 22"),
                addressed(3, "봉순이네흑돼지", "제주특별자치도 서귀포시 대정읍 구억리 1010"),
                addressed(4, "외돌개", "제주특별자치도 서귀포시 천지동"),
                addressed(5, "성산일출봉", "제주특별자치도 서귀포시 성산읍 성산리 78"),
                addressed(6, "별방진", "제주특별자치도 제주시 구좌읍 해맞이해안로 1708"));

        @Test
        @DisplayName("섬 주소의 장소는 빠지고 나머지는 남는다")
        void excludesIslandPlaces() {
            List<String> tokens = FerryAreaFilter.activeTokens(List.of("우도면", "추자면"), "맛집 커플여행",
                    List.of(), List.of(), pool);
            assertThat(FerryAreaFilter.exclude(pool, tokens)).extracting(PlaceCandidate::name)
                    .doesNotContain("섬소나이 우도본점").contains("형돈", "외돌개");
        }

        @Test
        @DisplayName("요청 문장에서 섬을 언급하면 제외하지 않는다 (\"우도\" ↔ \"우도면\")")
        void keepsWhenUserMentionsIsland() {
            assertThat(FerryAreaFilter.activeTokens(List.of("우도면"), "우도 가보고 싶어요", List.of(), List.of(), pool))
                    .isEmpty();
        }

        @Test
        @DisplayName("섬 안의 한 곳만 피하면 섬을 원하는 말은 그대로 섬 제외를 푼다")
        void avoidingPlaceOnIslandStillReleasesIsland() {
            assertThat(FerryAreaFilter.activeTokens(List.of("우도면"), "원하는 여행: 우도에서 자전거 타기", List.of(),
                    List.of("우도봉"), pool)).isEmpty();
        }

        @Test
        @DisplayName("피하겠다고 한 섬은 요청 문장에 나와도 제외를 풀지 않는다")
        void keepsExcludingAvoidedIsland() {
            assertThat(FerryAreaFilter.activeTokens(List.of("우도면"), "기타: 우도는 배 타야 해서 싫음", List.of(),
                    List.of("우도"), pool)).containsExactly("우도면");
        }

        @Test
        @DisplayName("풀의 20% 이상을 지우는 토큰은 잘못된 값으로 보고 무시한다")
        void ignoresTokenThatHitsTooMuch() {
            assertThat(FerryAreaFilter.activeTokens(List.of("제주특별자치도"), null, List.of(), List.of(), pool)).isEmpty();
        }

        @Test
        @DisplayName("3자 미만 토큰은 무시한다")
        void ignoresShortToken() {
            assertThat(FerryAreaFilter.activeTokens(List.of("리", "면"), null, List.of(), List.of(), pool)).isEmpty();
        }

        @Test
        @DisplayName("섬 이름으로 시작하는 육지 도로명 주소는 섬으로 보지 않는다")
        void roadNamedAfterIslandIsMainland() {
            PlaceCandidate aquarium = addressed(9, "아쿠아플라넷 여수", "전남 여수시 오동도로 61-11");
            PlaceCandidate island = addressed(10, "오동도 동백숲", "전남 여수시 수정동 오동도");
            assertThat(FerryAreaFilter.exclude(List.of(aquarium, island), List.of("오동도")))
                    .extracting(PlaceCandidate::name).containsExactly("아쿠아플라넷 여수");
        }

        @Test
        @DisplayName("사용자가 직접 고른 섬 장소는 남는다")
        void keepsUserSelected() {
            List<PlaceCandidate> withSelected = List.of(pool.get(0).asUserSelected(), pool.get(1));
            assertThat(FerryAreaFilter.exclude(withSelected, List.of("우도면"))).hasSize(2);
        }
    }

    // ── helpers ──

    private static PlaceCandidate addressed(int index, String name, String address) {
        return new PlaceCandidate(index, (long) index, name, address, "Dining and Drinking > Restaurant",
                List.of(), "Jeju", "KR", BigDecimal.valueOf(33.4), BigDecimal.valueOf(126.5),
                null, BigDecimal.valueOf(4.3), 0.9, null, null, null, null, false, null, "제주시");
    }

    private static PlaceCandidate place(int index, String name, String category, double lat, double lng) {
        return new PlaceCandidate(index, (long) index, name, "제주특별자치도 제주시 " + name, category,
                List.of(), "Jeju", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                null, BigDecimal.valueOf(4.3), 0.9, null, null, null, null, false, null, "제주시");
    }

    private static StepData step(String name, String category, int cost, List<AlternativeData> alts) {
        PlaceData place = new PlaceData(name, "addr", category, "Jeju", "KR");
        return new StepData(1, 1, "12:00", "13:00", place, alts, "CAR", 10, BigDecimal.ONE,
                BigDecimal.valueOf(1000), null, BigDecimal.valueOf(cost));
    }

    private static VectorEnrichedInput input(VectorEnrichedInput.CompanionType companion, Integer partySize) {
        return new VectorEnrichedInput("제주", List.of("food"), List.of("restaurant"), "normal", "car",
                BigDecimal.valueOf(1_000_000), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 4),
                "11월 제주도 3박4일 맛집 위주 커플여행", List.of(), "제주", "KR", List.of("Jeju"),
                List.of(), null, "MEDIUM", "", "", null, Map.of(), companion, partySize, List.of());
    }

}
