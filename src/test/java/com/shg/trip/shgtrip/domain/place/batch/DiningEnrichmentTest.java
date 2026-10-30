package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.DiningEnrichment.Venue;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiningEnrichmentTest {

    private static final String KOREAN = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String GENERIC = "Dining and Drinking > Restaurant";

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }

    // ── 업종 판정 ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant | MEAL",
            "Dining and Drinking > Restaurant > Gastropub                           | MEAL",
            "Dining and Drinking > Breakfast Spot                                   | MEAL",
            "Dining and Drinking > Cafeteria                                        | MEAL",
            "Dining and Drinking > Food Court                                       | MEAL",
            "Dining and Drinking > Cafe, Coffee, and Tea House                      | CAFE",
            "Dining and Drinking > Cafe, Coffee, and Tea House > Tea Room           | CAFE",
            "Dining and Drinking > Juice Bar                                        | CAFE",
            "Dining and Drinking > Smoothie Shop                                    | CAFE",
            "Dining and Drinking > Bakery                                           | BAKERY",
            "Dining and Drinking > Bagel Shop                                       | BAKERY",
            "Dining and Drinking > Dessert Shop > Ice Cream Parlor                  | DESSERT",
            "Dining and Drinking > Donut Shop                                       | DESSERT",
            "Dining and Drinking > Creperie                                         | DESSERT",
            "Dining and Drinking > Bar                                              | BAR",
            "Dining and Drinking > Bar > Sake Bar                                   | BAR",
            "Dining and Drinking > Brewery                                          | BAR",
            "Dining and Drinking > Winery                                           | BAR",
            "Dining and Drinking > Snack Place                                      | SNACK",
            "Dining and Drinking > Food Truck                                       | SNACK",
            "Dining and Drinking > Night Market                                     | SNACK",
    })
    void venueOf_mapsCategory(String category, Venue expected) {
        assertThat(DiningEnrichment.venueOf(category)).isEqualTo(expected);
    }

    @Test
    @DisplayName("2026-10 실측 Foursquare 식음료 2단계 분류는 총칭 Restaurant 말고 전부 업종이 정해진다")
    void venueOf_coversAllObservedFoursquareSubcategories() {
        List<String> observed = List.of(
                "Restaurant > Korean Restaurant", "Cafe, Coffee, and Tea House", "Bar", "Bakery", "Dessert Shop",
                "Snack Place", "Donut Shop", "Breakfast Spot", "Food Truck", "Food Court", "Bagel Shop",
                "Juice Bar", "Cafeteria", "Brewery", "Food Stand", "Smoothie Shop", "Night Market", "Creperie",
                "Distillery", "Winery", "Meadery", "Vineyard", "Cidery");

        assertThat(observed)
                .allSatisfy(sub -> assertThat(DiningEnrichment.venueOf("Dining and Drinking > " + sub))
                        .as(sub).isNotNull());
    }

    @Test
    @DisplayName("TourAPI 적재 카테고리도 같은 규칙으로 업종이 정해진다")
    void venueOf_tourApiCategories() {
        assertThat(DiningEnrichment.venueOf(TourApiSeeder.CAFE_CATEGORY)).isEqualTo(Venue.CAFE);
        assertThat(DiningEnrichment.venueOf(TourApiSeeder.BAKERY_CATEGORY)).isEqualTo(Venue.BAKERY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dining and Drinking > Restaurant", "Dining and Drinking",
            "Dining and Drinking > Something New"})
    @DisplayName("총칭이거나 모르는 하위 분류면 단정하지 않는다(모델이 고른다)")
    void venueOf_genericOrUnknown_isNull(String category) {
        assertThat(DiningEnrichment.venueOf(category)).isNull();
    }

    @Test
    @DisplayName("식음료가 아니면 식당 규칙을 타지 않는다")
    void isDining_onlyDiningAndDrinking() {
        assertThat(DiningEnrichment.isDining(KOREAN)).isTrue();
        assertThat(DiningEnrichment.isDining("Landmarks and Outdoors > Park")).isFalse();
        assertThat(DiningEnrichment.isDining("관광")).isFalse();
        assertThat(DiningEnrichment.isDining(null)).isFalse();
        assertThat(DiningEnrichment.venueOf("Landmarks and Outdoors > Park")).isNull();
    }

    // ── 동선 층 계약: 업종과 어긋나는 태그가 판정을 뒤집지 못한다 ──────────────────

    @Test
    @DisplayName("식당: 모델이 카페·디저트 태그를 붙여도 지워져 식사 후보로 남는다")
    void meal_nonMealTagsRemoved_staysMeal() {
        List<String> tags = DiningEnrichment.finalizeTags(Venue.MEAL,
                List.of("카페", "커피", "디저트", "빙수", "빵", "칼국수"));

        assertThat(tags).containsExactly("식당", "칼국수");
        assertThat(PlaceCategoryConstants.isMealPlace("한본새", KOREAN, tags)).isTrue();
        assertThat(PlaceCategoryConstants.isCafeLike("한본새", KOREAN, tags)).isFalse();
    }

    @Test
    @DisplayName("식당인데 이름에 카페가 있으면 식사 후보는 아니지만 카페로도 안 읽힌다")
    void meal_cafeInName_isNeitherMealNorCafe() {
        List<String> tags = DiningEnrichment.finalizeTags(Venue.MEAL, List.of("카페", "커피", "한식"));

        assertThat(tags).containsExactly("식당", "한식");
        assertThat(PlaceCategoryConstants.isMealPlace("카페서연의집", KOREAN, tags)).isFalse();
        assertThat(PlaceCategoryConstants.isCafeLike("카페서연의집", KOREAN, tags)).isFalse();
    }

    @Test
    @DisplayName("총칭 카테고리에서 모델이 카페로 고르면 식당 태그가 지워져 카페로 읽힌다")
    void cafe_restaurantTagsRemoved_isCafeLike() {
        List<String> tags = DiningEnrichment.finalizeTags(Venue.CAFE, List.of("한식", "식당", "커피"));

        assertThat(tags).containsExactly("카페", "커피");
        assertThat(PlaceCategoryConstants.isCafeLike("주택 카페", GENERIC, tags)).isTrue();
        assertThat(PlaceCategoryConstants.isMealPlace("주택 카페", GENERIC, tags)).isFalse();
    }

    @Test
    @DisplayName("총칭 카테고리에서 모델이 베이커리로 고르면 이름에 신호가 없어도 카페로 읽힌다")
    void bakery_onGenericCategory_isCafeLike() {
        List<String> tags = DiningEnrichment.finalizeTags(Venue.BAKERY, List.of("해산물", "식빵"));

        assertThat(tags).containsExactly("베이커리", "빵", "식빵");
        assertThat(PlaceCategoryConstants.isCafeLike("홍종흔", GENERIC, tags)).isTrue();
        assertThat(PlaceCategoryConstants.isMealPlace("홍종흔", GENERIC, tags)).isFalse();
    }

    @Test
    @DisplayName("디저트·간식: 식당 태그가 지워져 잠깐 들르는 곳으로 읽힌다")
    void dessertAndSnack_areLightStops() {
        List<String> dessert = DiningEnrichment.finalizeTags(Venue.DESSERT, List.of("한식", "젤라또"));
        List<String> snack = DiningEnrichment.finalizeTags(Venue.SNACK, List.of("음식점", "떡볶이"));

        assertThat(dessert).containsExactly("디저트", "젤라또");
        assertThat(snack).containsExactly("간식", "떡볶이");
        assertThat(PlaceCategoryConstants.isCafeLike("젤라또집", "Dining and Drinking > Dessert Shop", dessert)).isTrue();
        assertThat(PlaceCategoryConstants.isCafeLike("떡볶이집", "Dining and Drinking > Snack Place", snack)).isTrue();
    }

    @Test
    @DisplayName("술집: 디저트 태그가 지워져 총칭 카테고리여도 카페 휴식 자리로 안 들어간다")
    void bar_nonMealTagsRemoved_neverCafeLike() {
        List<String> tags = DiningEnrichment.finalizeTags(Venue.BAR, List.of("디저트", "커피", "맥주"));

        assertThat(tags).containsExactly("술집", "맥주");
        assertThat(PlaceCategoryConstants.isCafeLike("삼성호프", GENERIC, tags)).isFalse();
    }

    // ── 태그 정리 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("홍보어·지역명·빈 값·너무 긴 값을 빼고, # 접두와 공백을 정리하고, 중복을 없앤다")
    void finalizeTags_filtersNoise() {
        List<String> tags = DiningEnrichment.finalizeTags(Venue.MEAL, Arrays.asList(
                "맛집", "부산맛집", "부산", "부산광역시", "Seoul", "제 주", "#흑돼지", " 국밥 ", "", null,
                "흑돼지", "식당", "가성비좋은", "열여섯글자가넘어가는아주긴태그입니다", "제주흑돼지"));

        assertThat(tags).containsExactly("식당", "흑돼지", "국밥", "제주흑돼지");
    }

    @Test
    @DisplayName("태그는 업종 태그 포함 최대 MAX_TAGS개")
    void finalizeTags_capped() {
        List<String> tags = DiningEnrichment.finalizeTags(Venue.MEAL,
                List.of("한식", "국밥", "순대", "돼지고기", "수육", "탕·국밥", "막걸리", "전"));

        assertThat(tags).hasSize(DiningEnrichment.MAX_TAGS).startsWith("식당");
    }

    @Test
    @DisplayName("시간대는 허용 값만 남기고, 하나도 없으면 업종 기본값")
    void sanitizeSlots() {
        assertThat(DiningEnrichment.sanitizeSlots(List.of(" Morning", "lunch", "evening", "morning"), Venue.MEAL))
                .containsExactly("morning", "evening");
        assertThat(DiningEnrichment.sanitizeSlots(List.of("lunch"), Venue.BAR))
                .containsExactly("evening", "night");
        assertThat(DiningEnrichment.sanitizeSlots(List.of(), Venue.BAKERY))
                .containsExactly("morning", "afternoon");
    }

    // ── 응답 해석 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("업종이 정해져 있으면 응답의 venue는 무시한다")
    void interpret_knownVenueWins() throws Exception {
        DiningEnrichment.Result r = DiningEnrichment.interpret(
                json("{\"venue\":\"카페\",\"tags\":[\"커피\",\"칼국수\"],\"recommended_time_slots\":[\"evening\"]}"),
                Venue.MEAL, false);

        assertThat(r.tags()).containsExactly("식당", "칼국수");
        assertThat(r.timeSlots()).containsExactly("evening");
        assertThat(r.durationMinutes()).isEqualTo(Venue.MEAL.defaultMinutes);
    }

    @Test
    @DisplayName("총칭이면 응답의 venue를 쓰고, 없거나 모르는 값이면 식당으로 본다")
    void interpret_genericUsesModelVenue() throws Exception {
        DiningEnrichment.Result cafe = DiningEnrichment.interpret(
                json("{\"venue\":\" 카페 \",\"tags\":[\"커피\"],\"recommended_time_slots\":[]}"), null, false);
        DiningEnrichment.Result unknown = DiningEnrichment.interpret(
                json("{\"venue\":\"레스토랑\",\"tags\":[]}"), null, false);
        DiningEnrichment.Result missing = DiningEnrichment.interpret(json("{\"tags\":[\"국밥\"]}"), null, false);

        assertThat(cafe.tags()).containsExactly("카페", "커피");
        assertThat(cafe.timeSlots()).isEqualTo(Venue.CAFE.defaultSlots);
        assertThat(cafe.durationMinutes()).isEqualTo(Venue.CAFE.defaultMinutes);
        assertThat(unknown.tags()).containsExactly("식당");
        assertThat(missing.tags()).containsExactly("식당", "국밥");
    }

    @Test
    @DisplayName("설명은 유명한 곳만 받고, 문자열 \"null\"·빈 값은 없는 것으로 본다")
    void interpret_descriptionOnlyWhenFamous() throws Exception {
        JsonNode withDesc = json("{\"tags\":[],\"description\":\"칼국수로 알려진 식당입니다.\"}");

        assertThat(DiningEnrichment.interpret(withDesc, Venue.MEAL, false).description()).isNull();
        assertThat(DiningEnrichment.interpret(withDesc, Venue.MEAL, true).description())
                .isEqualTo("칼국수로 알려진 식당입니다.");
        assertThat(DiningEnrichment.interpret(json("{\"description\":\"null\"}"), Venue.MEAL, true).description())
                .isNull();
        assertThat(DiningEnrichment.interpret(json("{\"description\":\"  \"}"), Venue.MEAL, true).description())
                .isNull();
    }

    @Test
    @DisplayName("Wikidata sitelinks가 1 이상이어야 유명한 곳이다")
    void isFamous() {
        assertThat(DiningEnrichment.isFamous(null)).isFalse();
        assertThat(DiningEnrichment.isFamous(0)).isFalse();
        assertThat(DiningEnrichment.isFamous(3)).isTrue();
    }

    // ── 프롬프트 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("업종이 정해져 있으면 venue를 묻지 않고, 유명하지 않으면 설명을 묻지 않는다")
    void buildPrompt_knownVenue_notFamous() {
        String prompt = DiningEnrichment.buildPrompt("한본새", KOREAN, null, Venue.MEAL, false);

        assertThat(prompt).contains("한본새", KOREAN, "업종: 식당");
        assertThat(prompt).doesNotContain("\"venue\"", "description", "admission_fee", "recommended_duration_minutes");
    }

    @Test
    @DisplayName("총칭이면 venue를 묻고, 유명하면 설명을 묻고, 기존 설명을 넘긴다")
    void buildPrompt_generic_famous_withExistingDescription() {
        String prompt = DiningEnrichment.buildPrompt("명동교자", GENERIC, "칼국수 전문점", null, true);

        assertThat(prompt).contains("업종: 미정", "\"venue\"", "\"description\"", "기존 설명: 칼국수 전문점");
    }
}
