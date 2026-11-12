package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.WikidataLandmarkSeeder.Landmark;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.Candidate;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.WikidataItem;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikidataLandmarkSeederTest {

    private static final String TEMPLE = "Community and Government > Spiritual Center > Buddhist Temple";
    private static final String SHRINE = "Community and Government > Spiritual Center > Shrine";
    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final Destinations DESTINATIONS = Destinations.fromClasspath();

    private static Landmark landmark(String qid, String label, String category, double lat, double lon, String... names) {
        return new Landmark(qid, label, 50, lat, lon, category, "JP", null, 0, null, List.of(names));
    }

    @Test
    @DisplayName("정식 이름 라벨이어도 통칭 별칭이 FSQ 이름과 같으면 이미 있는 명소다")
    void knownByAlias() {
        Landmark kinkakuji = landmark("Q1", "鹿苑寺", TEMPLE, 35.0394, 135.7292, "鹿苑寺", "金閣寺", "Kinkaku-ji", "킨카쿠지");

        assertThat(WikidataLandmarkSeeder.alreadyKnownOverseas(kinkakuji,
                List.of(new Candidate(1, "金閣寺 (Kinkaku-ji)", 35.0393, 135.7290, TEMPLE)))).isTrue();
    }

    @Test
    @DisplayName("인기도 매칭이 붙지 않는 이름(명소 이름으로 시작하는 기념품점, 별칭에 없는 줄임말)은 이미 있는 명소가 아니다")
    void notKnownWhenMatchingWouldNotAttach() {
        Landmark inari = landmark("Q1", "伏見稲荷大社", SHRINE, 34.9671, 135.7727, "伏見稲荷大社", "Fushimi Inari-taisha");

        assertThat(WikidataLandmarkSeeder.alreadyKnownOverseas(inari, List.of(
                new Candidate(1, "伏見稲荷 Souvenir Shop", 34.9675, 135.7720, "Retail > Gift Store > Souvenir Store"),
                new Candidate(2, "伏見稲荷", 34.9675, 135.7720, SHRINE)))).isFalse();
    }

    @Test
    @DisplayName("명소 이름을 품은 딸린 시설, 다른 대분류, 2km 밖 행은 같은 명소가 아니다")
    void facilityOtherCategoryOrFarIsNew() {
        Landmark kiyomizu = landmark("Q1", "清水寺", TEMPLE, 34.9949, 135.7850, "清水寺");

        assertThat(WikidataLandmarkSeeder.alreadyKnownOverseas(kiyomizu, List.of(
                new Candidate(1, "清水寺", 34.9950, 135.7851, RESTAURANT),
                new Candidate(2, "清水寺", 35.1000, 135.7850, TEMPLE),
                new Candidate(3, "清水寺 売店", 34.9950, 135.7851, TEMPLE),
                new Candidate(4, "清水寺成就院", 34.9950, 135.7851, TEMPLE)))).isFalse();
    }

    @Test
    @DisplayName("도시·행정구역 항목과 국가가 다른 항목(샤먼 반경의 진먼 섬)은 적재하지 않는다")
    void inCountry() {
        WikidataItem kinmenIsland = item(false, Set.of("Q865"));
        WikidataItem gulangyu = item(false, Set.of("Q148"));
        WikidataItem noCountry = item(false, Set.of());
        WikidataItem city = item(true, Set.of("Q148"));

        assertThat(WikidataLandmarkSeeder.inCountry(kinmenIsland, "CN")).isFalse();
        assertThat(WikidataLandmarkSeeder.inCountry(gulangyu, "CN")).isTrue();
        assertThat(WikidataLandmarkSeeder.inCountry(noCountry, "CN")).isTrue();
        assertThat(WikidataLandmarkSeeder.inCountry(city, "CN")).isFalse();
        assertThat(WikidataLandmarkSeeder.inCountry(kinmenIsland, "TW")).isTrue();
    }

    @Test
    @DisplayName("여행지 목록의 모든 나라에 Wikidata 국가 QID가 있다")
    void everyDestinationCountryHasQid() {
        assertThat(DESTINATIONS.all()).allSatisfy(d ->
                assertThat(WikidataLandmarkSeeder.COUNTRY_QIDS).containsKey(d.country()));
    }

    private static WikidataItem item(boolean settlement, Set<String> countries) {
        return new WikidataItem("Q1", "x", List.of(), 10, List.of(new WikidataSitelinksJob.Point(0, 0)), false,
                Map.of(), settlement, countries);
    }

    @Test
    @DisplayName("반경이 겹치면 가장 가까운 여행지에만 넣고, 아는 QID는 이름만 맞추고, 같은 실행의 중복 항목은 한 번만 넣는다")
    void storeAssignsNearestAndRefreshesKnown() {
        PlaceRepository repository = mock(PlaceRepository.class);
        when(repository.findWikidataCandidates("Nara")).thenReturn(List.of());
        var seeder = new WikidataLandmarkSeeder(repository, new ObjectMapper(), DESTINATIONS);
        var nara = DESTINATIONS.find("Nara").orElseThrow();

        Landmark todaiji = new Landmark("Q100", "東大寺", 44, 34.6890, 135.8398, TEMPLE, "JP", null, 0, "도다이지",
                List.of("東大寺"));
        Landmark kofukuji = new Landmark("Q101", "興福寺", 30, 34.6830, 135.8320, TEMPLE, "JP", null, 0, "고후쿠지",
                List.of("興福寺"));
        // 같은 절을 다루는 다른 항목
        Landmark kofukujiDuplicate = new Landmark("Q103", "興福寺", 6, 34.6831, 135.8321, TEMPLE, "JP", null, 0, null,
                List.of("興福寺"));
        // 오사카·나라 반경이 겹치는 곳이지만 오사카가 더 가깝다
        Landmark nearOsaka = new Landmark("Q102", "某寺", 10, 34.686, 135.700, TEMPLE, "JP", null, 0, null,
                List.of("某寺"));
        Set<String> knownQids = new java.util.HashSet<>(Set.of("Q100"));

        int inserted = seeder.store(nara, List.of(todaiji, kofukuji, kofukujiDuplicate, nearOsaka), knownQids);

        assertThat(inserted).isEqualTo(1);
        assertThat(knownQids).contains("Q101").doesNotContain("Q103", "Q102");
        verify(repository).refreshWikidataLandmark("https://www.wikidata.org/wiki/Q100", "東大寺", "도다이지", "Nara");
        ArgumentCaptor<Place> saved = ArgumentCaptor.forClass(Place.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("興福寺");
        assertThat(saved.getValue().getNameKo()).isEqualTo("고후쿠지");
        assertThat(saved.getValue().getRegion()).isEqualTo("Nara");
        assertThat(saved.getValue().getCountry()).isEqualTo("JP");
    }

    @Test
    @DisplayName("좌표가 여러 개인 명소는 나라 전체에서 거리/반경이 가장 작은 좌표, 가장 구체적인 클래스를 쓴다")
    void parseOverseasPicksBestPlacedPoint() throws Exception {
        var seeder = new WikidataLandmarkSeeder(mock(PlaceRepository.class), new ObjectMapper(), DESTINATIONS);
        // 첫 좌표는 오사카 반경 끝(0.9), 둘째 좌표는 나라 중심(0.01)
        String json = """
                {"results":{"bindings":[
                  {"item":{"value":"http://www.wikidata.org/entity/Q1"},"sitelinks":{"value":"9"},
                   "lat":{"value":"34.686"},"lon":{"value":"135.700"},"cls":{"value":"http://www.wikidata.org/entity/Q570116"}},
                  {"item":{"value":"http://www.wikidata.org/entity/Q1"},"sitelinks":{"value":"9"},
                   "lat":{"value":"34.686"},"lon":{"value":"135.806"},"cls":{"value":"http://www.wikidata.org/entity/Q5393308"}}
                ]}}
                """;

        assertThat(seeder.parseOverseas(json, "JP")).singleElement().satisfies(lm -> {
            assertThat(lm.lon()).isEqualTo(135.806);
            assertThat(lm.category()).endsWith("Buddhist Temple");
            assertThat(DESTINATIONS.nearest("JP", lm.lat(), lm.lon())).get()
                    .extracting(com.shg.trip.shgtrip.domain.place.destination.Destination::name).isEqualTo("Nara");
        });
    }

    @Test
    @DisplayName("구역형 명소 분류를 받고, 야시장은 시장보다 먼저 판정하며, 지정 목록의 분류는 모두 아는 분류다")
    void areaClassesAndExtraLandmarks() {
        assertThat(WikidataLandmarkSeeder.CLASS_CATEGORY).containsKeys(
                "Q1133961", "Q21000333", "Q62685721", "Q676050", "Q66364404", "Q202509", "Q174782", "Q23397",
                "Q1208212", "Q1574228", "Q93338609", "Q1962840");
        assertThat(WikidataLandmarkSeeder.CLASS_CATEGORY).doesNotContainKeys("Q79007", "Q83620", "Q34442", "Q123705");
        List<String> order = List.copyOf(WikidataLandmarkSeeder.CLASS_CATEGORY.keySet());
        assertThat(order.indexOf("Q1962840")).isLessThan(order.indexOf("Q330284"));
        assertThat(WikidataLandmarkSeeder.CLASS_CATEGORY.keySet())
                .containsAll(WikidataLandmarkSeeder.EXTRA_LANDMARKS.values());
        assertThat(WikidataLandmarkSeeder.buildExtraSparql())
                .contains("VALUES (?item ?cls)", "(wd:Q10878674 wd:Q62685721)", "(wd:Q1009423 wd:Q21000333)")
                .doesNotContain("FILTER(?sitelinks");
    }

    @Test
    @DisplayName("지정 목록 응답은 반경 조회와 같은 형식이라 지정한 분류의 카테고리로 읽는다")
    void parseExtraLandmark() throws Exception {
        var seeder = new WikidataLandmarkSeeder(mock(PlaceRepository.class), new ObjectMapper(), DESTINATIONS);
        String json = """
                {"results":{"bindings":[
                  {"item":{"value":"http://www.wikidata.org/entity/Q10878674"},"sitelinks":{"value":"3"},
                   "lat":{"value":"25.1094"},"lon":{"value":"121.8452"},"cls":{"value":"http://www.wikidata.org/entity/Q62685721"}}
                ]}}
                """;

        assertThat(seeder.parseOverseas(json, "TW")).singleElement().satisfies(lm -> {
            assertThat(lm.category()).isEqualTo("Landmarks and Outdoors > Pedestrian Plaza");
            assertThat(DESTINATIONS.nearest("TW", lm.lat(), lm.lon())).get()
                    .extracting(com.shg.trip.shgtrip.domain.place.destination.Destination::name).isEqualTo("Jiufen");
        });
    }

    @Test
    @DisplayName("반경 조회는 상위 행정구역을 따라가지 않고 라벨 서비스도 부르지 않는다")
    void aroundQueryIsLight() {
        var shenzhen = DESTINATIONS.find("Shenzhen").orElseThrow();
        String sparql = WikidataLandmarkSeeder.buildAroundSparql(shenzhen, 5);

        assertThat(sparql).contains("wikibase:around", "wikibase:radius \"25.000000\"", "FILTER(?sitelinks >= 5)");
        assertThat(sparql).doesNotContain("wikibase:label", "P131", "P1082");
    }

    @Test
    @DisplayName("홍콩·마카오 검사는 조회된 명소만 상위 행정구역으로 묻는다")
    void excludedAreaQuery() {
        assertThat(WikidataLandmarkSeeder.buildExcludedAreaSparql(List.of("Q1", "Q2")))
                .contains("VALUES ?item { wd:Q1 wd:Q2 }", "VALUES ?area { wd:Q8646 wd:Q14773 }", "wdt:P131+ ?area");
    }
}
