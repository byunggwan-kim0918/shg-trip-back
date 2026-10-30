package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여행자 관점 품질 회귀 테스트 — itinerary 115(제주 3박4일 맛집 커플)에서 지적된 결함을 고정한다.
 *
 * <p>115 재구성 풀({@link Itinerary115Fixture})로 RouteOptimizer 전체를 돌려 "여행자가 겪는 실패"를
 * 성질로 검사하고, 개별 규칙은 작은 입력으로 따로 검사한다.
 */
class TravelerQualityTest {

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    @Nested
    @DisplayName("115 재구성 풀")
    class Itinerary115 {

        private final List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        private final Map<String, PlaceCandidate> byName = candidates.stream()
                .collect(Collectors.toMap(PlaceCandidate::name, Function.identity(), (a, b) -> a));
        private final List<StepData> steps = routeOptimizer.schedule(
                Itinerary115Fixture.selection(), candidates, "normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant")).steps();

        @Test
        @DisplayName("같은 음식 종류는 4일 일정 전체에서 2끼를 넘지 않는다 (태그까지 본다)")
        void sameCuisineAtMostTwice() {
            Map<String, Long> cuisineCount = steps.stream()
                    .map(s -> byName.get(s.place().name()))
                    .filter(c -> c != null && c.mealEligible())
                    .map(c -> PlaceCategoryConstants.subType(c.name(), c.category(), c.tags(), c.description()))
                    .filter(t -> !PlaceCategoryConstants.isGenericLeaf(t))
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

            assertThat(cuisineCount.values()).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(2));
        }

        @Test
        @DisplayName("한라산 백록담 같은 긴 등산은 빈 시간 채우기로 들어오지 않는다")
        void longHikeNeverGapFilled() {
            assertThat(steps).noneMatch(s -> s.place().name().contains("백록담"));
        }

        @Test
        @DisplayName("하루 중간에 3시간 이상 비는 날이 없다")
        void noLargeGap() {
            Map<Integer, List<StepData>> byDay = steps.stream()
                    .collect(Collectors.groupingBy(StepData::dayNumber));
            byDay.values().forEach(day -> {
                for (int i = 1; i < day.size(); i++) {
                    int gap = toMinutes(day.get(i).startTime()) - toMinutes(day.get(i - 1).endTime());
                    assertThat(gap).as("day %d %s 앞 공백", day.get(i).dayNumber(), day.get(i).place().name())
                            .isLessThan(180);
                }
            });
        }

        @Test
        @DisplayName("매일 관광지가 1곳 이상 있다 (거리 트림이 관광지만 빼서 식당만 남는 날 방지)")
        void everyDayHasAttraction() {
            Map<Integer, Long> attractions = steps.stream()
                    .filter(s -> "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(s.place().category())))
                    .collect(Collectors.groupingBy(StepData::dayNumber, Collectors.counting()));
            assertThat(attractions.keySet()).containsExactlyInAnyOrder(1, 2, 3, 4);
        }

        @Test
        @DisplayName("같은 날 두 끼가 2시간 안에 붙지 않는다 (아침 10시 → 점심 11시 반 방지)")
        void mealsAreSpacedOut() {
            Map<Integer, List<StepData>> meals = steps.stream()
                    .filter(s -> {
                        PlaceCandidate c = byName.get(s.place().name());
                        return c != null && c.mealEligible();
                    })
                    .collect(Collectors.groupingBy(StepData::dayNumber));
            meals.values().forEach(day -> {
                for (int i = 1; i < day.size(); i++) {
                    assertThat(toMinutes(day.get(i).startTime()) - toMinutes(day.get(i - 1).startTime()))
                            .as("day %d %s → %s", day.get(i).dayNumber(),
                                    day.get(i - 1).place().name(), day.get(i).place().name())
                            .isGreaterThanOrEqualTo(120);
                }
            });
        }

        @Test
        @DisplayName("상한을 넘는 유형은 인기도 낮은 쪽부터 뺀다 — 성산일출봉이 남는다")
        void prominentPlaceSurvivesTypeCap() {
            assertThat(steps).anyMatch(s -> s.place().name().startsWith("성산일출봉"));
        }
    }

    @Nested
    @DisplayName("거리 트림")
    class DistanceTrim {

        @Test
        @DisplayName("위반이 전날 숙소→첫 스텝 구간뿐이면 관광지를 빼지 않는다")
        void doesNotTrimWhenTrimCannotReduceViolation() {
            // day1은 동북(월정 부근), day2는 남서(산방산 부근) — 전날 숙소에서 56km.
            List<PlaceCandidate> candidates = List.of(
                    place(1, "동쪽점심", "Dining and Drinking > Restaurant", 33.548, 126.780),
                    place(2, "동쪽관광", "Landmarks and Outdoors > Tourist Attraction", 33.540, 126.770),
                    place(3, "동쪽저녁", "Dining and Drinking > Restaurant", 33.552, 126.795),
                    place(4, "동쪽숙소", "Travel and Transportation > Lodging > Hotel", 33.552, 126.791),
                    place(5, "서쪽관광1", "Landmarks and Outdoors > Tourist Attraction", 33.240, 126.546),
                    place(6, "서쪽점심", "Dining and Drinking > Restaurant", 33.278, 126.280),
                    place(7, "서쪽관광2", "Landmarks and Outdoors > Tourist Attraction", 33.199, 126.294),
                    place(8, "서쪽저녁", "Dining and Drinking > Restaurant", 33.258, 126.417)
            );
            SelectionOutput selection = new SelectionOutput("concept",
                    List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), 4, null),
                            new SelectionOutput.DayPlan(2, null, List.of(5, 6, 7, 8), null, null)),
                    List.of(), List.of());

            List<StepData> steps = routeOptimizer.repairAndSchedule(selection, candidates, "normal", "car", null);

            List<String> day2 = steps.stream().filter(s -> s.dayNumber() == 2)
                    .map(s -> s.place().name()).toList();
            assertThat(day2).contains("서쪽관광1", "서쪽관광2");
        }
    }

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

            List<StepData> scaled = OptimizedGenerationExecutor.scaleCostsForParty(List.of(meal, lodging), 2);

            assertThat(scaled.get(0).estimatedCost()).isEqualByComparingTo("26000");
            assertThat(scaled.get(0).alternatives().get(0).estimatedCost()).isEqualByComparingTo("26000");
            assertThat(scaled.get(0).transportationCost()).isEqualByComparingTo("1000");
            assertThat(scaled.get(1).estimatedCost()).isEqualByComparingTo("120000");
        }

        @Test
        void unknownPartySizeKeepsCost() {
            StepData meal = step("식당", "Dining and Drinking > Restaurant", 13000, List.of());
            assertThat(OptimizedGenerationExecutor.scaleCostsForParty(List.of(meal), null).get(0).estimatedCost())
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
                    List.of(), pool);
            assertThat(FerryAreaFilter.exclude(pool, tokens)).extracting(PlaceCandidate::name)
                    .doesNotContain("섬소나이 우도본점").contains("형돈", "외돌개");
        }

        @Test
        @DisplayName("요청 문장에서 섬을 언급하면 제외하지 않는다 (\"우도\" ↔ \"우도면\")")
        void keepsWhenUserMentionsIsland() {
            assertThat(FerryAreaFilter.activeTokens(List.of("우도면"), "우도 가보고 싶어요", List.of(), pool))
                    .isEmpty();
        }

        @Test
        @DisplayName("풀의 20% 이상을 지우는 토큰은 잘못된 값으로 보고 무시한다")
        void ignoresTokenThatHitsTooMuch() {
            assertThat(FerryAreaFilter.activeTokens(List.of("제주특별자치도"), null, List.of(), pool)).isEmpty();
        }

        @Test
        @DisplayName("3자 미만 토큰은 무시한다")
        void ignoresShortToken() {
            assertThat(FerryAreaFilter.activeTokens(List.of("리", "면"), null, List.of(), pool)).isEmpty();
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

    private static int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }
}
