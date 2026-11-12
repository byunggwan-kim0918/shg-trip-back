package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.Assignment;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.Candidate;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.WikidataItem;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wikidata 매칭 규칙 테스트.
 *
 * <p>실측에서 깨진 케이스를 고정한다 — 제주월드컵경기장 옆 국수집이 경기장 점수를 가져갔고,
 * 정방폭포 하나가 주변 식당 7곳에 뿌려졌다. 그리고 장소 순서대로 매칭하던 시절엔
 * 명소 이름을 단 식당이 먼저 적재되면 명소의 항목을 가로챌 수 있었다.
 * HTTP 호출은 건드리지 않고 매칭·파싱만 본다.
 */
class WikidataSitelinksJobTest {

    private static final String LANDMARK = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";

    private WikidataSitelinksJob job;

    @BeforeEach
    void setUp() {
        job = new WikidataSitelinksJob(mock(PlaceRepository.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class), Destinations.fromClasspath());
    }

    private Candidate place(long id, String name, double lat, double lon, String category) {
        return new Candidate(id, name, lat, lon, category);
    }

    private WikidataItem item(String id, String label, int sitelinks, double lat, double lon) {
        return new WikidataItem(id, label, sitelinks, lat, lon);
    }

    /** 장소 id → 붙은 sitelinks */
    private Map<Long, Integer> assign(List<Candidate> places, List<WikidataItem> items) {
        return WikidataSitelinksJob.assign(places, items).stream()
                .collect(Collectors.toMap(Assignment::placeId, a -> a.item().sitelinks()));
    }

    @Test
    @DisplayName("야시장 행은 식음료 아래 카테고리여도 식당 행으로 보지 않는다(야시장 항목은 비식음료 분류다)")
    void nightMarketIsNotDiningRow() {
        assertThat(WikidataSitelinksJob.isDiningRow("Dining and Drinking > Night Market")).isFalse();
        assertThat(WikidataSitelinksJob.isDiningRow("Dining and Drinking > Restaurant")).isTrue();
        assertThat(WikidataSitelinksJob.isDiningRow("Landmarks and Outdoors > Park")).isFalse();
    }

    @Nested
    @DisplayName("assign")
    class Matching {

        @Test
        @DisplayName("표제가 달라도 핵심 이름이 겹치면 매칭한다")
        void matchesWhenCoreNameOverlaps() {
            // 실측 좌표. TourAPI 제목에는 지정명이, Wikidata에는 분류어가 붙는다.
            var result = assign(
                    List.of(place(1, "성산일출봉 [유네스코 세계자연유산]", 33.45913, 126.93962, LANDMARK)),
                    List.of(item("Q494369", "성산일출봉 천연보호구역", 16, 33.4581, 126.9425)));

            assertThat(result).containsEntry(1L, 16);
        }

        @Test
        @DisplayName("이름이 겹치지 않으면 반경 안이어도 매칭하지 않는다")
        void rejectsNearbyButUnrelated() {
            // 실측 사고: 경기장 옆 국수집이 sitelinks 28을 가져갔다.
            var result = assign(
                    List.of(place(1, "앙끄레국수 서귀포본점", 33.4500, 126.5000, RESTAURANT)),
                    List.of(item("Q482585", "제주월드컵경기장", 28, 33.4501, 126.5001)));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("이름이 같아도 반경 밖이면 매칭하지 않는다 — 동명이소 방지")
        void rejectsSameNameFarAway() {
            var result = assign(
                    List.of(place(1, "중앙성당", 33.5000, 126.5000, LANDMARK)),
                    List.of(item("Q1", "중앙성당", 7, 33.6000, 126.6000)));   // 약 14km

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("명소 이름을 단 식당이 먼저 적재돼도 명소가 항목을 가져간다")
        void landmarkBeatsRestaurantRegardlessOfLoadOrder() {
            // 식당 id가 더 작다 = 먼저 적재됐다. 예전 구현(장소 id 순)에선 식당이 이겼다.
            var result = assign(
                    List.of(place(1, "경복궁 한식당", 37.5790, 126.9760, RESTAURANT),
                            place(2, "경복궁", 37.5796, 126.9770, LANDMARK)),
                    List.of(item("Q485", "경복궁", 60, 37.5788, 126.9770)));

            assertThat(result).containsOnlyKeys(2L);
        }

        @Test
        @DisplayName("명소 표제에 군더더기가 더 붙어 있어도 카테고리가 식당을 이긴다")
        void landmarkWithVerboseTitleStillBeatsRestaurant() {
            // 이름 유사도만 보면 식당(토큰 2개 중 1개 공유)이 명소(3개 중 1개)보다 높다.
            var result = assign(
                    List.of(place(1, "경복궁 한식당", 37.5790, 126.9760, RESTAURANT),
                            place(2, "경복궁 (Gyeongbokgung Palace)", 37.5796, 126.9770, LANDMARK)),
                    List.of(item("Q485", "경복궁", 60, 37.5788, 126.9770)));

            assertThat(result).containsOnlyKeys(2L);
        }

        @Test
        @DisplayName("명소 이름을 단 주변 식당에는 붙지 않는다 — 정방폭포가 식당 7곳에 뿌려지지 않는다")
        void oneItemOnePlace() {
            List<Candidate> places = new java.util.ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                places.add(place(i, "정방폭포 횟집" + i, 33.2450 + i * 0.0005, 126.5720, RESTAURANT));
            }
            places.add(place(8, "정방폭포", 33.2448, 126.5718, LANDMARK));

            var result = assign(places, List.of(item("Q1", "정방폭포", 20, 33.2449, 126.5719)));

            assertThat(result).containsOnlyKeys(8L);
        }

        @Test
        @DisplayName("장소 하나엔 맞는 항목 중 가장 유명한 하나만 붙고, 각 장소는 제 이름의 항목을 받는다")
        void placeKeepsMostFamousItem() {
            // "한라산" 장소에는 두 항목이 다 맞는다(둘 다 "한라산"으로 시작). "한라산 국립공원" 장소는
            // "한라산" 항목에 맞지 않는다 — 항목에 없는 "국립공원"이 붙어 있다.
            var result = assign(
                    List.of(place(1, "한라산", 33.3620, 126.5300, LANDMARK),
                            place(2, "한라산 국립공원", 33.3617, 126.5292, LANDMARK)),
                    List.of(item("Q2", "한라산 국립공원", 30, 33.3617, 126.5292),
                            item("Q1", "한라산", 46, 33.3620, 126.5300)));

            assertThat(result).containsEntry(1L, 46).containsEntry(2L, 30).hasSize(2);
        }

        @Test
        @DisplayName("하위 시설은 본체 항목을 가져가지 못하고, 본체가 받는다 (2026-10 로컬 실측)")
        void subFacilityDoesNotTakeParentItem() {
            var result = assign(
                    List.of(place(1, "창덕궁 후원", 37.5826, 126.9918, LANDMARK),
                            place(2, "창덕궁", 37.5794, 126.9910, LANDMARK),
                            place(3, "제주국제공항 고인돌", 33.5070, 126.4930, "Landmarks and Outdoors > Park"),
                            place(4, "연세대학교 정문", 37.5600, 126.9370, "Landmarks and Outdoors > Monument"),
                            place(5, "제주시 수협종합어시장", 33.5170, 126.5260, "Retail > Food and Beverage Retail"),
                            place(6, "SBS 목동 옥상공원", 37.5290, 126.8720, "Landmarks and Outdoors > Garden")),
                    List.of(item("Q477157", "창덕궁", 50, 37.5794, 126.9910),
                            item("Q482497", "제주국제공항", 30, 33.5066, 126.4929),
                            item("Q39988", "연세대학교", 39, 37.5658, 126.9386),
                            item("Q42142", "제주시", 51, 33.5000, 126.5300),
                            item("Q928831", "SBS", 69, 37.5290, 126.8720)));

            assertThat(result).containsOnlyKeys(2L).containsEntry(2L, 50);
        }

        @Test
        @DisplayName("행정구역 행은 이름이 같아도 받지 않는다")
        void administrativeRowsAreNotMatched() {
            String municipality = "Landmarks and Outdoors > States and Municipalities";
            var result = assign(
                    List.of(place(1, "서울특별시 (Seoul)", 37.5665, 126.9780, municipality),
                            place(2, "종로구", 37.5730, 126.9790, municipality)),
                    List.of(item("Q8684", "서울특별시", 260, 37.5665, 126.9780),
                            item("Q36929", "종로구", 36, 37.5730, 126.9790)));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("두 글자 이름도 정확히 같으면 붙는다 — 종묘")
        void twoCharacterExactName() {
            var result = assign(
                    List.of(place(1, "종묘", 37.5746, 126.9940, LANDMARK),
                            place(2, "종묘상회", 37.5740, 126.9935, RESTAURANT)),
                    List.of(item("Q495422", "종묘", 35, 37.5746, 126.9941)));

            assertThat(result).containsOnlyKeys(1L);
        }

        @Test
        @DisplayName("띄어쓰기가 달라도 붙는다 — \"북촌한옥마을\" ↔ \"북촌 한옥마을\"")
        void spacingDifferenceIgnored() {
            var result = assign(
                    List.of(place(1, "북촌한옥마을", 37.5826, 126.9836, LANDMARK)),
                    List.of(item("Q1", "북촌 한옥마을", 20, 37.5823, 126.9850)));

            assertThat(result).containsEntry(1L, 20);
        }

        @Test
        @DisplayName("항목 이름의 뒤쪽(분류어)만 같은 장소에는 붙지 않는다")
        void trailingClassWordAloneDoesNotMatch() {
            var result = assign(
                    List.of(place(1, "해수욕장", 33.3940, 126.2397, LANDMARK),
                            place(2, "용궁사", 35.1880, 129.2230, LANDMARK)),
                    List.of(item("Q1", "협재 해수욕장", 10, 33.3940, 126.2397),
                            item("Q2", "해동 용궁사", 15, 35.1882, 129.2232)));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("같은 명소의 소스별 행에는 모두 붙고, 같은 이름의 식당에는 붙지 않는다")
        void duplicateRowsAllMatchedAtBestTier() {
            var result = assign(
                    List.of(place(1, "성산일출봉", 33.4590, 126.9400, LANDMARK),
                            place(2, "성산일출봉 [유네스코 세계자연유산]", 33.4591, 126.9396,
                                    "Landmarks and Outdoors > Tourist Attraction"),
                            place(3, "성산일출봉 (Seongsan Ilchulbong)", 33.4585, 126.9420,
                                    "Landmarks and Outdoors > Mountain"),
                            place(4, "성산일출봉", 33.4600, 126.9380, RESTAURANT)),
                    List.of(item("Q494369", "성산일출봉 천연보호구역", 16, 33.4581, 126.9425)));

            assertThat(result).containsOnlyKeys(1L, 2L, 3L);
        }

        @Test
        @DisplayName("라벨이 옛 이름이어도 한국어 별칭으로 붙는다 — YTN서울타워 ↔ N서울타워·남산서울타워")
        void matchesByAlias() {
            var tower = new WikidataItem("Q69134", "YTN서울타워", List.of("N서울타워", "남산서울타워"), 32,
                    List.of(new WikidataSitelinksJob.Point(37.5512, 126.9882)), false);
            var result = assign(
                    List.of(place(1, "N서울타워", 37.5512, 126.9882, "Landmarks and Outdoors > Tower"),
                            place(2, "남산서울타워", 37.5513, 126.9880, "Landmarks and Outdoors > Tourist Attraction")),
                    List.of(tower));

            assertThat(result).containsOnlyKeys(1L, 2L);
        }

        @Test
        @DisplayName("명소 항목은 같은 이름의 식당에 붙지 않는다 — 한식당 \"창덕궁\"이 궁궐 점수를 가져가던 사고")
        void landmarkItemNeverGoesToRestaurant() {
            var result = assign(
                    List.of(place(1, "창덕궁", 37.5790, 126.9915, "Dining and Drinking > Restaurant > Korean Restaurant"),
                            place(2, "창덕궁과 후원 [유네스코 세계유산]", 37.5794, 126.9910, LANDMARK)),
                    List.of(item("Q477157", "창덕궁", 50, 37.5794, 126.9917)));

            assertThat(result).containsOnlyKeys(2L);
        }

        @Test
        @DisplayName("식당 항목은 식당 행에만 붙는다")
        void foodItemOnlyGoesToRestaurant() {
            var hadonggwan = new WikidataItem("Q1", "하동관", List.of(), 3,
                    List.of(new WikidataSitelinksJob.Point(37.5640, 126.9840)), true);
            var result = assign(
                    List.of(place(1, "하동관", 37.5641, 126.9841, RESTAURANT),
                            place(2, "하동관", 37.5642, 126.9842, "Landmarks and Outdoors > Plaza")),
                    List.of(hadonggwan));

            assertThat(result).containsOnlyKeys(1L);
        }

        @Test
        @DisplayName("TourAPI의 \"창덕궁과 후원\"이 창덕궁 항목을 받는다")
        void compositeTourApiTitleMatches() {
            var result = assign(
                    List.of(place(1, "창덕궁과 후원 [유네스코 세계유산]", 37.5794, 126.9910, LANDMARK)),
                    List.of(item("Q477157", "창덕궁", 50, 37.5794, 126.9917)));

            assertThat(result).containsEntry(1L, 50);
        }

        @Test
        @DisplayName("별칭은 이름 전체로만 맞춘다 — \"여의도 순복음교회\"의 \"여의도\"가 여의도 섬에 붙지 않는다")
        void aliasMatchesOnlyWhole() {
            var church = new WikidataItem("Q1", "여의도순복음교회", List.of("여의도 순복음교회"), 21,
                    List.of(new WikidataSitelinksJob.Point(37.5285, 126.9230)), false);
            var result = assign(
                    List.of(place(1, "여의도 (Yeouido)", 37.5260, 126.9250, "Landmarks and Outdoors > Island"),
                            place(2, "여의도 순복음교회", 37.5286, 126.9231, "Community and Government > Spiritual Center")),
                    List.of(church));

            assertThat(result).containsOnlyKeys(2L);
        }

        @Test
        @DisplayName("영문 라벨은 앞부분만으로 붙지 않는다 — \"T World\" ↔ \"World Youth Day 2027\"")
        void englishLabelNeedsWholeName() {
            var result = assign(
                    List.of(place(1, "T World", 37.5600, 126.9800, "Landmarks and Outdoors > Structure"),
                            place(2, "World", 37.5601, 126.9801, "Landmarks and Outdoors > Structure")),
                    List.of(item("Q1", "World Youth Day 2027", 10, 37.5600, 126.9800)));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("행정구역 이름의 Foursquare 행·버스 정류장은 받지 않고, TourAPI 관광지 행은 받는다")
        void foursquareAdminNamedRowsSkipped() {
            var result = assign(
                    List.of(new Candidate(1, "서울특별시 종로구", 37.5730, 126.9790,
                                    "Landmarks and Outdoors > Other Great Outdoors", "foursquare"),
                            new Candidate(2, "청담동", 37.5250, 127.0450, "Retail > Boutique", "foursquare"),
                            new Candidate(4, "서교동과 동교동 사이", 37.5550, 126.9200,
                                    "Landmarks and Outdoors > Pedestrian Plaza", "foursquare"),
                            new Candidate(5, "디지털미디어시티 (ID: 80-019)", 37.5770, 126.8900,
                                    "Travel and Transportation > Transport Hub > Bus Station", "foursquare"),
                            new Candidate(3, "명동", 37.5636, 126.9826,
                                    "Landmarks and Outdoors > Tourist Attraction", "tourapi")),
                    List.of(new WikidataItem("Q36929", "종로구", List.of("서울특별시 종로구"), 36,
                                    List.of(new WikidataSitelinksJob.Point(37.5730, 126.9790)), false),
                            item("Q2", "청담동", 15, 37.5250, 127.0450),
                            item("Q4", "서교동", 7, 37.5551, 126.9201),
                            item("Q5", "디지털 미디어 시티 랜드마크 빌딩", 8, 37.5771, 126.8901),
                            item("Q3", "명동", 24, 37.5636, 126.9826)));

            assertThat(result).containsOnlyKeys(3L);
        }

        @Test
        @DisplayName("이름 앞뒤의 도시 이름은 무시한다 — \"제주 삼성혈\" ↔ \"삼성혈\"")
        void cityWordIgnored() {
            var result = assign(
                    List.of(place(1, "제주 삼성혈", 33.5050, 126.5300, LANDMARK)),
                    List.of(item("Q7410332", "삼성혈", 7, 33.5052, 126.5298)));

            assertThat(result).containsEntry(1L, 7);
        }

        @Test
        @DisplayName("같은 이름이면 숙소가 그 안의 식당보다 우선한다")
        void lodgingBeatsRestaurantInside() {
            var result = assign(
                    List.of(place(1, "롯데호텔 서울 라세느", 37.5651, 126.9810, RESTAURANT),
                            place(2, "롯데호텔 서울", 37.5652, 126.9811, HOTEL)),
                    List.of(item("Q3", "롯데호텔 서울", 12, 37.5650, 126.9810)));

            assertThat(result).containsOnlyKeys(2L);
        }

        @Test
        @DisplayName("카테고리 등급: 명소 0, 숙소·상점·교통 1, 식당·카페 2")
        void categoryTiers() {
            assertThat(WikidataSitelinksJob.categoryTier(LANDMARK)).isZero();
            assertThat(WikidataSitelinksJob.categoryTier("Arts and Entertainment > Museum")).isZero();
            assertThat(WikidataSitelinksJob.categoryTier(HOTEL)).isEqualTo(1);
            assertThat(WikidataSitelinksJob.categoryTier("Retail > Market")).isEqualTo(1);
            assertThat(WikidataSitelinksJob.categoryTier(RESTAURANT)).isEqualTo(2);
            assertThat(WikidataSitelinksJob.categoryTier("Dining and Drinking > Cafe, Coffee, and Tea House")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("nameKey / leadingNames")
    class NameKeyRule {

        @Test
        @DisplayName("괄호 안과 도시 이름만 걷어내고, 괄호 밖 영문·한 글자 단어는 남긴다")
        void normalizes() {
            assertThat(WikidataSitelinksJob.nameKey("성산일출봉 [유네스코 세계자연유산]")).containsExactly("성산일출봉");
            assertThat(WikidataSitelinksJob.nameKey("탕평비 (성균관대학교 정문")).containsExactly("탕평비");
            assertThat(WikidataSitelinksJob.nameKey("한양대학교 후문 I")).containsExactly("한양대학교", "후문", "i");
            assertThat(WikidataSitelinksJob.nameKey("T World")).containsExactly("t", "world");
            assertThat(WikidataSitelinksJob.nameKey("롯데호텔 서울")).containsExactly("롯데호텔");
            assertThat(WikidataSitelinksJob.nameKey("창덕궁 후원")).containsExactly("창덕궁", "후원");
            assertThat(WikidataSitelinksJob.nameKey("N서울타워")).containsExactly("n서울타워");
            assertThat(WikidataSitelinksJob.nameKey("Shake Shack")).containsExactly("shake", "shack");
            assertThat(WikidataSitelinksJob.nameKey("서울")).isEmpty();
            // 실측에서 하위 시설·다른 업장이 본체 항목을 가져간 이름들 — 차이를 만드는 단어가 남아야 한다
            assertThat(WikidataSitelinksJob.nameKey("CGV 명동")).containsExactly("cgv", "명동");
            assertThat(WikidataSitelinksJob.nameKey("한양대학교 rtB")).containsExactly("한양대학교", "rtb");
            assertThat(WikidataSitelinksJob.nameKey("국립중앙도서관 앞 뜰(디지털 도서관 지붕 앞)"))
                    .containsExactly("국립중앙도서관", "앞", "뜰");
            assertThat(WikidataSitelinksJob.nameKey("남산 길")).containsExactly("남산", "길");
        }

        @Test
        @DisplayName("앞부분 묶음은 3자 이상 한글이거나 이름 전체여야 한다")
        void leadingNames() {
            assertThat(WikidataSitelinksJob.leadingNames(List.of("성산일출봉", "천연보호구역")))
                    .containsExactly("성산일출봉", "성산일출봉천연보호구역");
            assertThat(WikidataSitelinksJob.leadingNames(List.of("해동", "용궁사")))
                    .containsExactly("해동용궁사");
            assertThat(WikidataSitelinksJob.leadingNames(List.of("종묘"))).containsExactly("종묘");
            assertThat(WikidataSitelinksJob.leadingNames(List.of("tower", "palace", "2")))
                    .containsExactly("towerpalace2");
        }

        @Test
        @DisplayName("\"A과 B\" 꼴 장소 이름은 A도 키로 쓴다")
        void compositePlaceKeys() {
            assertThat(WikidataSitelinksJob.placeKeys("창덕궁과 후원 [유네스코 세계유산]"))
                    .containsExactly("창덕궁과후원", "창덕궁");
            assertThat(WikidataSitelinksJob.placeKeys("오목대와 이목대")).containsExactly("오목대와이목대", "오목대");
            assertThat(WikidataSitelinksJob.placeKeys("사과와 배")).containsExactly("사과와배");   // A가 3자 미만
            assertThat(WikidataSitelinksJob.placeKeys("성산일출봉")).containsExactly("성산일출봉");
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
            assertThat(items.get(0).points()).containsExactly(new WikidataSitelinksJob.Point(33.4581, 126.9425));
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

        @Test
        @DisplayName("좌표가 여러 개인 항목은 한 항목으로 합친다")
        void mergesRowsOfSameItem() throws Exception {
            String json = """
                    {"results":{"bindings":[
                      {"item":{"value":"http://www.wikidata.org/entity/Q495437"},"itemLabel":{"value":"청계천"},
                       "sitelinks":{"value":"33"},"lat":{"value":"37.569"},"lon":{"value":"126.978"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q495437"},"itemLabel":{"value":"청계천"},
                       "sitelinks":{"value":"33"},"lat":{"value":"37.571"},"lon":{"value":"127.040"}}
                    ]}}
                    """;
            List<WikidataSitelinksJob.WikidataItem> items = job.parseItems(json);

            assertThat(items).hasSize(1);
            assertThat(items.get(0).points()).hasSize(2);
            // 둘째 좌표 근처의 장소도 반경 안으로 본다
            assertThat(items.get(0).distanceMeters(37.5712, 127.0398)).isLessThan(100);
        }

        @Test
        @DisplayName("별칭·분류 응답을 항목별로 모으고, 분류로 식음료 여부를 정한다")
        void parsesDetailsAndFoodFlag() throws Exception {
            String json = """
                    {"results":{"bindings":[
                      {"item":{"value":"http://www.wikidata.org/entity/Q69134"},"alias":{"value":"N서울타워"},
                       "class":{"value":"http://www.wikidata.org/entity/Q11303"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q69134"},"alias":{"value":"남산서울타워"},
                       "class":{"value":"http://www.wikidata.org/entity/Q11303"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q1"},
                       "class":{"value":"http://www.wikidata.org/entity/Q11707"}}
                    ]}}
                    """;
            Map<String, WikidataSitelinksJob.ItemDetail> details = job.parseDetails(json);

            assertThat(details.get("Q69134").aliases()).containsExactly("N서울타워", "남산서울타워");
            var tower = item("Q69134", "YTN서울타워", 32, 37.5512, 126.9882)
                    .withDetail(details.get("Q69134"), java.util.Set.of("Q11707"));
            var restaurant = item("Q1", "하동관", 3, 37.56, 126.98)
                    .withDetail(details.get("Q1"), java.util.Set.of("Q11707"));
            assertThat(tower.food()).isFalse();
            assertThat(tower.names()).containsExactly("YTN서울타워", "N서울타워", "남산서울타워");
            assertThat(restaurant.food()).isTrue();
        }
    }

    @Nested
    @DisplayName("assignOverseas")
    class OverseasMatching {

        private static final String TEMPLE = "Community and Government > Spiritual Center > Buddhist Temple";
        private static final String MARKET = "Retail > Market";
        private static final String INFO = "Travel and Transportation > Rest Area";
        private static final String CLUB = "Arts and Entertainment > Music Venue > Rock Club";
        private static final String FISH_SHOP = "Retail > Food and Beverage Retail > Fish Market";

        private WikidataItem overseasItem(String id, int sitelinks, double lat, double lon,
                                          Map<String, String> labels, List<String> aliases,
                                          boolean food, boolean settlement) {
            return new WikidataItem(id, labels.values().iterator().next(), aliases, sitelinks,
                    List.of(new WikidataSitelinksJob.Point(lat, lon)), food, labels, settlement, java.util.Set.of());
        }

        private Map<Long, String> assignOverseas(List<Candidate> places, List<WikidataItem> items) {
            return WikidataSitelinksJob.assignOverseas(places, items).stream()
                    .collect(Collectors.toMap(Assignment::placeId, a -> a.item().id()));
        }

        @Test
        @DisplayName("괄호 속 영문을 뗀 현지 이름이 그 나라 라벨과 같으면 이름 전체 일치로 붙는다")
        void matchesLocalLabel() {
            var item = overseasItem("Q1", 60, 35.7148, 139.7967,
                    Map.of("ja", "浅草寺", "en", "Sensō-ji", "ko", "센소지"), List.of(), false, false);
            var result = WikidataSitelinksJob.assignOverseas(
                    List.of(place(1, "浅草寺 (Senso-ji Temple)", 35.7147, 139.7966, TEMPLE)), List.of(item));

            assertThat(result).singleElement().satisfies(a -> {
                assertThat(a.placeId()).isEqualTo(1L);
                assertThat(a.exact()).isTrue();
                assertThat(a.item().koLabel()).isEqualTo("센소지");
            });
        }

        @Test
        @DisplayName("라벨이 정식 이름이고 통칭이 별칭인 명소도 붙는다")
        void matchesAliasInAnyLanguage() {
            var item = overseasItem("Q2", 57, 35.0394, 135.7292,
                    Map.of("ja", "鹿苑寺", "en", "Kinkaku-ji"), List.of("金閣寺"), false, false);

            assertThat(assignOverseas(List.of(place(1, "金閣寺", 35.0393, 135.7293, TEMPLE)), List.of(item)))
                    .containsEntry(1L, "Q2");
        }

        @Test
        @DisplayName("병기 이름은 뒤 묶음이 같은 항목의 다른 언어 이름일 때 첫 묶음으로 붙는다")
        void matchesBilingualName() {
            var bund = overseasItem("Q3", 40, 31.2400, 121.4900,
                    Map.of("zh-hans", "外滩", "en", "The Bund"), List.of(), false, false);
            var ryoanji = overseasItem("Q31", 32, 35.0345, 135.7182,
                    Map.of("ja", "龍安寺", "en", "Ryōan-ji"), List.of(), false, false);

            var result = WikidataSitelinksJob.assignOverseas(List.of(
                    place(1, "外滩 The Bund", 31.2401, 121.4901, LANDMARK),
                    place(2, "龍安寺 Ryoan-ji Temple", 35.0344, 135.7181, TEMPLE)), List.of(bund, ryoanji));

            assertThat(result).extracting(Assignment::placeId).containsExactlyInAnyOrder(1L, 2L);
            assertThat(result).allSatisfy(a -> assertThat(a.exact()).isFalse());
        }

        @Test
        @DisplayName("명소·동네 이름 뒤에 시설명이 붙은 행은 그 명소에 붙지 않는다")
        void facilityNamedAfterLandmarkNotMatched() {
            var kuromon = overseasItem("Q10855506", 3, 34.6655, 135.5067,
                    Map.of("ja", "黒門市場", "en", "Kuromon Market"), List.of(), false, false);
            var amemura = overseasItem("Q4745722", 8, 34.6727, 135.4985,
                    Map.of("ja", "アメリカ村", "en", "Amerikamura"), List.of(), false, false);

            assertThat(assignOverseas(List.of(
                    place(1, "黒門市場 Information Center", 34.6656, 135.5068, INFO),
                    place(2, "アメリカ村 CLAPPER", 34.6728, 135.4986, CLUB),
                    place(3, "黒門市場 鮮魚店", 34.6654, 135.5066, FISH_SHOP)), List.of(kuromon, amemura)))
                    .isEmpty();
        }

        @Test
        @DisplayName("발음 구별 기호 없이 적은 베트남 이름도 붙는다")
        void matchesVietnameseWithoutDiacritics() {
            var item = overseasItem("Q4", 25, 10.7725, 106.6980,
                    Map.of("vi", "Chợ Bến Thành", "en", "Bến Thành Market"), List.of(), false, false);

            assertThat(assignOverseas(List.of(
                    place(1, "Ben Thanh Market", 10.7724, 106.6981, MARKET),
                    place(2, "Chợ Bến Thành", 10.7726, 106.6979, MARKET)), List.of(item)))
                    .containsOnlyKeys(1L, 2L);
        }

        @Test
        @DisplayName("도시·행정구역 항목은 이름이 같은 행에도 붙지 않는다")
        void settlementItemsNotMatched() {
            var tokyo = overseasItem("Q1490", 300, 35.6895, 139.6917,
                    Map.of("ja", "東京都", "en", "Tokyo"), List.of("東京"), false, true);

            assertThat(assignOverseas(List.of(place(1, "TOKYO", 35.6890, 139.6920, LANDMARK)), List.of(tokyo)))
                    .isEmpty();
        }

        @Test
        @DisplayName("하위 시설은 본체 항목을 가져가지 않는다")
        void subFacilityNotMatched() {
            var tower = overseasItem("Q5", 69, 35.6586, 139.7454,
                    Map.of("ja", "東京タワー", "en", "Tokyo Tower"), List.of(), false, false);

            assertThat(assignOverseas(List.of(place(1, "東京タワー展望台", 35.6586, 139.7454, LANDMARK)), List.of(tower)))
                    .isEmpty();
        }

        @Test
        @DisplayName("지점명을 뗀 식당 이름은 식음료 항목(체인)에만 붙는다")
        void restaurantBranchMatchesFoodItem() {
            var ichiran = overseasItem("Q6", 12, 33.5900, 130.3990,
                    Map.of("ja", "一蘭", "ko", "이치란"), List.of(), true, false);

            var result = WikidataSitelinksJob.assignOverseas(
                    List.of(place(1, "一蘭 天神西通り店", 33.5910, 130.3980, RESTAURANT)), List.of(ichiran));

            assertThat(result).singleElement().satisfies(a -> assertThat(a.exact()).isFalse());
        }

        @Test
        @DisplayName("병기 뒤 묶음이 항목 이름 + 분류어가 아닌 딸린 시설은 본체에 붙지 않는다")
        void bilingualFacilityNotMatched() {
            var todaiji = overseasItem("Q193072", 44, 34.6890, 135.8398,
                    Map.of("ja", "東大寺", "en", "Tōdai-ji"), List.of("Todaiji"), false, false);
            var tower = overseasItem("Q183536", 69, 35.6586, 139.7454,
                    Map.of("ja", "東京タワー", "en", "Tokyo Tower"), List.of(), false, false);

            assertThat(assignOverseas(List.of(
                    place(1, "東大寺 Todaiji Nandaimon", 34.6880, 135.8399, LANDMARK),
                    place(2, "東京タワー Tokyo Tower Main Deck", 35.6586, 139.7454, LANDMARK)), List.of(todaiji, tower)))
                    .isEmpty();
        }

        @Test
        @DisplayName("한 장소가 여러 키로 걸리면 이름 전체 일치로 기록한다")
        void exactKeyPreferred() {
            var item = overseasItem("Q7", 20, 35.0, 135.0,
                    Map.of("ja", "仁和寺", "en", "Ninna-ji"), List.of("仁和寺 Ninna-ji Temple"), false, false);

            assertThat(WikidataSitelinksJob.assignOverseas(
                    List.of(place(1, "仁和寺 Ninna-ji Temple", 35.0, 135.0, TEMPLE)), List.of(item)))
                    .singleElement().satisfies(a -> assertThat(a.exact()).isTrue());
        }

        @Test
        @DisplayName("한국 지역 규칙은 그대로다: 한자 이름 행은 한국어 라벨 항목에 붙지 않는다")
        void koreanRulesUnchanged() {
            var item = overseasItem("Q8", 50, 35.7148, 139.7967,
                    Map.of("ja", "浅草寺", "ko", "센소지"), List.of(), false, false).withLabel("센소지");

            assertThat(WikidataSitelinksJob.assign(
                    List.of(place(1, "浅草寺", 35.7147, 139.7966, TEMPLE)), List.of(item))).isEmpty();
        }
    }

    @Nested
    @DisplayName("해외 기록·범위")
    class OverseasWriteAndScope {

        private final PlaceRepository repository = mock(PlaceRepository.class);
        private final WikidataSitelinksJob scoped = new WikidataSitelinksJob(repository, new ObjectMapper(),
                mock(PlatformTransactionManager.class), Destinations.fromClasspath());

        private Assignment assignment(long placeId, String koLabel, boolean exact) {
            var item = new WikidataItem("Q" + placeId, "x", List.of(), 10, List.of(new WikidataSitelinksJob.Point(0, 0)),
                    false, koLabel == null ? Map.of() : Map.of("ko", koLabel), false, java.util.Set.of());
            return new Assignment(placeId, item, exact);
        }

        @Test
        @DisplayName("해외는 이름 전체가 같은 매칭만 한국어 라벨로 name_ko를 채운다")
        void fillsNameKoOnlyForExactOverseasMatches() {
            scoped.writeAssignments("Osaka", List.of(
                    assignment(1, "구로몬 시장", true),
                    assignment(2, "와이탄", false),
                    assignment(3, null, true)), true);

            org.mockito.Mockito.verify(repository).resetWikidataForRegion("Osaka");
            org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(3))
                    .updateWikidataMatch(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyInt());
            org.mockito.Mockito.verify(repository).fillNameKo(1L, "구로몬 시장");
            org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).fillNameKo(
                    org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("한국 지역은 name_ko를 쓰지 않는다")
        void koreaNeverWritesNameKo() {
            scoped.writeAssignments("Seoul", List.of(assignment(1, "경복궁", true)), false);

            org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).fillNameKo(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("한국 지역은 장소 좌표 상자, 해외 지역은 여행지 반경 상자로 범위를 잡고 국가·지역 설정을 지킨다")
        void regionBoxesRespectScope() {
            org.mockito.Mockito.when(repository.findRegionBoundingBoxes(org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(
                            new Object[]{"Seoul", 37.4, 126.8, 37.7, 127.2, 50000L},
                            new Object[]{"Osaka", 34.6, 135.4, 34.8, 135.6, 4000L}));

            var all = scoped.loadRegionBoxes(List.of()).stream().map(WikidataSitelinksJob.RegionBox::region).toList();
            assertThat(all).contains("Seoul", "Osaka", "Shanghai", "Jiufen").doesNotHaveDuplicates();
            // 해외 지역은 백분위 상자가 아니라 여행지 반경 상자로 한 번만 잡힌다
            assertThat(scoped.loadRegionBoxes(List.of()).stream().filter(b -> b.region().equals("Osaka")).findFirst()
                    .orElseThrow().maxLat()).isGreaterThan(34.8);

            assertThat(scoped.loadRegionBoxes(List.of("KR")))
                    .extracting(WikidataSitelinksJob.RegionBox::region).containsExactly("Seoul");
            assertThat(scoped.loadRegionBoxes(List.of("TW")))
                    .extracting(WikidataSitelinksJob.RegionBox::region).contains("Taipei", "Jiufen").doesNotContain("Seoul", "Osaka");

            org.springframework.test.util.ReflectionTestUtils.setField(scoped, "targetRegions", List.of("Jiufen", "Seoul"));
            assertThat(scoped.loadRegionBoxes(List.of()))
                    .extracting(WikidataSitelinksJob.RegionBox::region).containsExactlyInAnyOrder("Seoul", "Jiufen");
        }

        @Test
        @DisplayName("국가 설정은 대소문자를 가리지 않고, ALL은 전 국가, 빈 값·모르는 코드는 실패다")
        void countryScope() {
            var ds = Destinations.fromClasspath();
            assertThat(WikidataSitelinksJob.countryScope(List.of(" jp", "CN "), ds)).containsExactly("JP", "CN");
            assertThat(WikidataSitelinksJob.countryScope(List.of("all"), ds)).isEmpty();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> WikidataSitelinksJob.countryScope(List.of(" "), ds))
                    .isInstanceOf(BatchStageFailed.class).hasMessageContaining("ALL");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> WikidataSitelinksJob.countryScope(List.of("JP", "HK"), ds))
                    .isInstanceOf(BatchStageFailed.class).hasMessageContaining("HK");
        }
    }

    @Nested
    @DisplayName("해외 조회")
    class OverseasQuery {

        @Test
        @DisplayName("언어별 라벨, 도시 여부(인구가 있고 섬·관광지 분류가 아님), 국가를 항목별로 모은다")
        void parsesLabelsSettlementAndCountry() throws Exception {
            String json = """
                    {"results":{"bindings":[
                      {"item":{"value":"http://www.wikidata.org/entity/Q1"},"label":{"xml:lang":"ja","value":"浅草寺"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q1"},"label":{"xml:lang":"ko","value":"센소지"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q1"},"alias":{"xml:lang":"en","value":"Asakusa Kannon"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q1"},"country":{"value":"http://www.wikidata.org/entity/Q17"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q1490"},"label":{"xml:lang":"en","value":"Tokyo"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q1490"},"pop":{"value":"14000000"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q989803"},"pop":{"value":"354"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q989803"},"placeLike":{"value":"http://www.wikidata.org/entity/Q23442"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q781255"},"pop":{"value":"65211"}},
                      {"item":{"value":"http://www.wikidata.org/entity/Q781255"},"placeLike":{"value":"http://www.wikidata.org/entity/Q570116"}}
                    ]}}
                    """;
            Map<String, WikidataSitelinksJob.ItemDetail> details = job.parseDetails(json);

            assertThat(details.get("Q1").labels()).containsEntry("ja", "浅草寺").containsEntry("ko", "센소지");
            assertThat(details.get("Q1").aliases()).containsExactly("Asakusa Kannon");
            assertThat(details.get("Q1").countries()).containsExactly("Q17");
            assertThat(details.get("Q1").settlement()).isFalse();
            assertThat(details.get("Q1490").settlement()).isTrue();
            // 에노시마(섬)·시탕(AAAAA 관광지 고진): 인구가 있지만 명소다
            assertThat(details.get("Q989803").settlement()).isFalse();
            assertThat(details.get("Q781255").settlement()).isFalse();
        }

        @Test
        @DisplayName("해외 조회는 라벨 서비스를 부르지 않고, 한국 조회는 그대로 부른다")
        void labelServiceOnlyForKorea() {
            var box = new WikidataSitelinksJob.RegionBox("Tokyo", 35.4, 139.5, 35.9, 140.0);
            assertThat(WikidataSitelinksJob.buildSparql(box, false)).doesNotContain("wikibase:label", "?itemLabel");
            assertThat(WikidataSitelinksJob.buildSparql(box))
                    .contains("?itemLabel ?sitelinks", "wikibase:language \"ko,ja,en\"");
        }

        @Test
        @DisplayName("여행지 반경 상자를 한 변 0.25도 이하 격자로 나눠 빈틈 없이 덮는다")
        void tilesCoverCircleBox() {
            var tokyo = Destinations.fromClasspath().find("Tokyo").orElseThrow();
            var box = WikidataSitelinksJob.circleBox(tokyo);
            var tiles = WikidataSitelinksJob.tiles(box, 0.25);

            assertThat(tiles).allSatisfy(t -> {
                assertThat(t.maxLat() - t.minLat()).isLessThanOrEqualTo(0.25);
                assertThat(t.maxLon() - t.minLon()).isLessThanOrEqualTo(0.25);
            });
            assertThat(tiles.stream().mapToDouble(WikidataSitelinksJob.RegionBox::minLat).min().orElseThrow())
                    .isEqualTo(box.minLat());
            assertThat(tiles.stream().mapToDouble(WikidataSitelinksJob.RegionBox::maxLon).max().orElseThrow())
                    .isCloseTo(box.maxLon(), org.assertj.core.data.Offset.offset(1e-9));
            assertThat(box.minLat()).isLessThan(tokyo.lat() - tokyo.radiusKm() / 111.0);
        }

        @Test
        @DisplayName("격자 경계에 걸쳐 두 번 온 항목은 좌표를 합친다")
        void mergesPointsAcrossTiles() {
            var a = item("Q1", "x", 10, 35.0, 135.0);
            var b = item("Q1", "x", 10, 35.3, 135.0);

            assertThat(a.withPointsOf(b).points()).hasSize(2);
            assertThat(a.withPointsOf(a).points()).hasSize(1);
        }

        @Test
        @DisplayName("이름 조회에 상세가 없는 항목은 라벨 없이 남아 아무 장소에도 붙지 않는다")
        void itemWithoutDetailKeepsNoNames() throws Exception {
            String json = """
                    {"results":{"bindings":[
                      {"item":{"value":"http://www.wikidata.org/entity/Q1"},"label":{"xml:lang":"ja","value":"浅草寺"}}
                    ]}}
                    """;
            var items = WikidataSitelinksJob.withNames(
                    List.of(item("Q1", "", 50, 35.7148, 139.7967), item("Q2", "", 40, 35.7148, 139.7967)),
                    java.util.Set.of(), "JP", query -> json, new ObjectMapper());

            assertThat(items).extracting(WikidataItem::label).containsExactly("浅草寺", "");
            assertThat(WikidataSitelinksJob.assignOverseas(
                    List.of(place(1, "浅草寺", 35.7147, 139.7966, LANDMARK)), items))
                    .singleElement().extracting(a -> a.item().id()).isEqualTo("Q1");
        }

        @Test
        @DisplayName("이름 조회는 받는 언어만 걸러 라벨·별칭·분류·인구·섬/관광지·국가를 UNION으로 묻는다")
        void namesQuery() {
            String sparql = WikidataSitelinksJob.buildNamesSparql(List.of(item("Q1", "x", 1, 0, 0)));
            assertThat(sparql).contains("wd:Q1", "rdfs:label", "skos:altLabel", "wdt:P1082",
                    "wdt:P31/wdt:P279? ?placeLike", "wd:Q23442 wd:Q570116", "wdt:P17", "\"zh-hant\"", "\"vi\"");
            assertThat(sparql).doesNotContain("P279*");
        }
    }
}
