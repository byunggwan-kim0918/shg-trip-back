package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wikidata 매칭 규칙 테스트.
 *
 * <p>좌표만으로 매칭했다가 실측에서 깨진 케이스를 고정한다 — 제주월드컵경기장 옆 국수집이
 * 경기장 점수를 가져갔고, 정방폭포 하나가 주변 식당 7곳에 뿌려졌다.
 * HTTP 호출은 건드리지 않고 매칭·파싱만 본다.
 */
class WikidataSitelinksJobTest {

    private WikidataSitelinksJob job;

    @BeforeEach
    void setUp() {
        job = new WikidataSitelinksJob(mock(PlaceRepository.class), new ObjectMapper());
    }

    private Place place(String name, double lat, double lon) {
        return Place.builder()
                .name(name)
                .latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lon))
                .build();
    }

    private WikidataSitelinksJob.WikidataItem item(String id, String label, int sitelinks,
                                                   double lat, double lon) {
        return new WikidataSitelinksJob.WikidataItem(id, label, sitelinks, lat, lon);
    }

    @Nested
    @DisplayName("findBestMatch")
    class Matching {

        @Test
        @DisplayName("표제가 달라도 핵심 이름이 겹치면 매칭한다")
        void matchesWhenCoreNameOverlaps() {
            // 실측 좌표. TourAPI 제목에는 지정명이, Wikidata에는 분류어가 붙는다.
            Place p = place("성산일출봉 [유네스코 세계자연유산]", 33.45913, 126.93962);
            var match = WikidataSitelinksJob.findBestMatch(p, List.of(
                    item("Q494369", "성산일출봉 천연보호구역", 16, 33.4581, 126.9425)));

            assertThat(match).isNotNull();
            assertThat(match.sitelinks()).isEqualTo(16);
        }

        @Test
        @DisplayName("이름이 겹치지 않으면 반경 안이어도 매칭하지 않는다")
        void rejectsNearbyButUnrelated() {
            // 실측 사고: 경기장 옆 국수집이 sitelinks 28을 가져갔다.
            Place noodleShop = place("앙끄레국수 서귀포본점", 33.4500, 126.5000);
            var match = WikidataSitelinksJob.findBestMatch(noodleShop, List.of(
                    item("Q482585", "제주월드컵경기장", 28, 33.4501, 126.5001)));

            assertThat(match).isNull();
        }

        @Test
        @DisplayName("이름이 같아도 반경 밖이면 매칭하지 않는다 — 동명이소 방지")
        void rejectsSameNameFarAway() {
            Place p = place("중앙성당", 33.5000, 126.5000);
            var match = WikidataSitelinksJob.findBestMatch(p, List.of(
                    item("Q1", "중앙성당", 7, 33.6000, 126.6000)));   // 약 14km

            assertThat(match).isNull();
        }

        @Test
        @DisplayName("좌표가 없으면 매칭하지 않는다")
        void rejectsPlaceWithoutCoords() {
            Place p = Place.builder().name("성산일출봉").build();
            var match = WikidataSitelinksJob.findBestMatch(p, List.of(
                    item("Q494369", "성산일출봉", 16, 33.4581, 126.9425)));

            assertThat(match).isNull();
        }
    }

    @Nested
    @DisplayName("namesOverlap")
    class NameRule {

        @Test
        @DisplayName("두 글자 이름은 거부한다 — 아무 데나 걸린다")
        void rejectsTooShortNames() {
            assertThat(WikidataSitelinksJob.namesOverlap("우도", "우도면")).isFalse();
            assertThat(WikidataSitelinksJob.namesOverlap("산", "한라산")).isFalse();
        }

        @Test
        @DisplayName("서로를 포함하지 않아도 핵심 토큰을 공유하면 겹친 것으로 본다")
        void sharedTokenCounts() {
            // 두 문자열은 서로를 포함하지 않는다 — 토큰 "성산일출봉"만 공유한다.
            assertThat(WikidataSitelinksJob.namesOverlap(
                    "성산일출봉 [유네스코 세계자연유산]", "성산일출봉 천연보호구역")).isTrue();
        }

        @Test
        @DisplayName("한쪽에만 수식어가 붙어도 핵심 토큰이 같으면 잡는다")
        void sharedTokenWithExtraQualifier() {
            assertThat(WikidataSitelinksJob.namesOverlap("한라산 백록담", "백록담")).isTrue();
        }

        @Test
        @DisplayName("행정구역 이름이 상호 안쪽에 박혀 있어도 매칭하지 않는다")
        void rejectsAdministrativeNameInsideBusinessName() {
            // 실측 사고: 통짜 포함 규칙 때문에 "육도담 제주시청본점"이 "제주시"(sitelinks 51)를,
            // "서귀포시립기당미술관"이 "서귀포시"(41)를 가져갔다.
            assertThat(WikidataSitelinksJob.namesOverlap("육도담 제주시청본점", "제주시")).isFalse();
            assertThat(WikidataSitelinksJob.namesOverlap("서귀포시립기당미술관", "서귀포시")).isFalse();
        }

        @Test
        @DisplayName("무관한 이름은 겹치지 않는다")
        void rejectsUnrelated() {
            assertThat(WikidataSitelinksJob.namesOverlap(
                    "앙끄레국수 서귀포본점", "제주월드컵경기장")).isFalse();
        }

        @Test
        @DisplayName("같은 도시 접두어만 공유하는 다른 장소는 거부한다")
        void rejectsSharedCityPrefixOnly() {
            // 실측에서 정방폭포가 주변 식당들에 뿌려진 유형. 토큰이 각각 하나뿐이고 서로 다르다.
            assertThat(WikidataSitelinksJob.namesOverlap(
                    "서귀포유람선", "서귀포해양도립공원")).isFalse();
        }
    }

    @Nested
    @DisplayName("parseItems")
    class Parsing {

        @Test
        @DisplayName("SPARQL JSON에서 id·라벨·sitelinks·좌표를 뽑는다")
        void parsesSparqlJson() throws Exception {
            String json = """
                    {"results":{"bindings":[
                      {"item":{"value":"http://www.wikidata.org/entity/Q494369"},
                       "itemLabel":{"value":"성산일출봉 천연보호구역"},
                       "sitelinks":{"value":"16"},
                       "lat":{"value":"33.4581"},"lon":{"value":"126.9425"}}
                    ]}}
                    """;
            List<WikidataSitelinksJob.WikidataItem> items = job.parseItems(json);

            assertThat(items).hasSize(1);
            assertThat(items.get(0).id()).isEqualTo("Q494369");
            assertThat(items.get(0).label()).isEqualTo("성산일출봉 천연보호구역");
            assertThat(items.get(0).sitelinks()).isEqualTo(16);
            assertThat(items.get(0).lat()).isEqualTo(33.4581);
        }

        @Test
        @DisplayName("Q로 시작하지 않는 항목은 건너뛴다")
        void skipsNonEntityRows() throws Exception {
            String json = """
                    {"results":{"bindings":[
                      {"item":{"value":"http://www.wikidata.org/entity/P123"},
                       "itemLabel":{"value":"속성"},"sitelinks":{"value":"3"},
                       "lat":{"value":"33.0"},"lon":{"value":"126.0"}}
                    ]}}
                    """;
            assertThat(job.parseItems(json)).isEmpty();
        }

        @Test
        @DisplayName("빈 결과도 안전하게 처리한다")
        void handlesEmptyResults() throws Exception {
            assertThat(job.parseItems("{\"results\":{\"bindings\":[]}}")).isEmpty();
        }
    }
}
