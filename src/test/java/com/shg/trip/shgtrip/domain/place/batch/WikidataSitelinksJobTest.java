package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.Assignment;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.Candidate;
import com.shg.trip.shgtrip.domain.place.batch.WikidataSitelinksJob.WikidataItem;
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
                mock(PlatformTransactionManager.class));
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
}
