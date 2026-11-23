package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** 실제 DB에 있는 행 모양으로 초안 이름을 잇는다. 이름 조회 SQL(공백·문장부호를 뺀 이름의 담음)을 그대로 흉내 낸다. */
class DraftPlaceResolverMatchingTest {

    private static final String SIGHT = "Landmarks and Outdoors > Tourist Attraction";

    private final PlaceRepository repository = mock(PlaceRepository.class);
    private final DraftPlaceResolver resolver = new DraftPlaceResolver(repository, Destinations.fromClasspath());

    private void rows(Place... places) {
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willAnswer(inv -> {
            String q = inv.getArgument(0);
            int min = inv.getArgument(1);
            Collection<String> roles = inv.getArgument(3);
            return List.of(places).stream().filter(p -> p.getPlaceRole() == null || roles.contains(p.getPlaceRole()))
                    .filter(p -> contains(p.getName(), q, min) || contains(p.getNameKo(), q, min)).toList();
        });
        given(repository.findActiveByNameEdges(anyString(), anyString(), any(), any())).willReturn(List.of());
        given(repository.findAllById(any())).willAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            java.util.Set<Long> wanted = new java.util.HashSet<>();
            ids.forEach(wanted::add);
            return List.of(places).stream().filter(p -> wanted.contains(p.getId())).toList();
        });
    }

    private static boolean contains(String name, String q, int min) {
        if (name == null) return false;
        String k = PlaceNames.exactKey(name);
        return k.contains(q) || k.length() >= min && q.contains(k);
    }

    @Test
    @DisplayName("경포해변은 앞부분만 맞는 딸린 광장보다 별칭 이름의 해수욕장에 잇는다")
    void beachAliasBeatsAttachedPlaza() {
        rows(place(1, "경포해변 중앙광장", null, SIGHT, "SIGHT", 37.80, 128.90),
                place(2, "경포해수욕장", null, "Landmarks and Outdoors > Beach", "SIGHT", 37.80, 128.91));

        assertThat(resolver.resolve("경포해변", "sight", List.of("Gangneung"), List.of("강릉"), null).place())
                .extracting(Place::getId).isEqualTo(2L);
    }

    @Test
    @DisplayName("나카스 야타이는 마지막 낱말만 맞는 초밥 체인에, 캐널시티 하카타는 이름에 하카타가 든 축제에 잇지 않는다")
    void lastWordAloneDoesNotLink() {
        rows(place(1, "や台ずし", "야타이즈시", "Dining and Drinking > Bar > Sake Bar", "BAR", 33.59, 130.40),
                place(2, "博多祇園山笠", "하카타 기온 야마카사", SIGHT, "SIGHT", 33.59, 130.41),
                place(3, "キャナルシティ劇場", "캐널시티 극장", "Arts and Entertainment > Theater", "ACTIVITY", 33.59,
                        130.41));

        assertThat(resolver.resolve("나카스 야타이", "night", List.of("Fukuoka"), List.of("후쿠오카"), null).place())
                .isNull();
        assertThat(resolver.resolve("캐널시티 하카타", "sight", List.of("Fukuoka"), List.of("후쿠오카"), null).place())
                .isNull();
    }

    @Test
    @DisplayName("마지막 낱말로 찾은 곳은 도시 이름이 아닌 앞 낱말도 이름에 있을 때 잇는다")
    void lastWordNeedsAnotherWord() {
        rows(place(1, "北海道庁旧本庁舎", "홋카이도청 구 본청사", SIGHT, "SIGHT", 43.06, 141.35),
                place(2, "東京スカイツリー", "도쿄 스카이트리", SIGHT, "SIGHT", 35.71, 139.81),
                place(3, "福岡ドーム", "후쿠오카 돔", SIGHT, "SIGHT", 33.59, 130.36));

        assertThat(resolver.resolve("홋카이도 구 본청사", "sight", List.of("Sapporo"), List.of("삿포로"), null).place())
                .extracting(Place::getId).isEqualTo(1L);
        assertThat(resolver.resolve("시부야 스카이", "sight", List.of("Tokyo"), List.of("도쿄"), null).place()).isNull();
        assertThat(resolver.resolve("아크로스 후쿠오카", "sight", List.of("Fukuoka"), List.of("후쿠오카"), null).place())
                .isNull();
    }

    @Test
    @DisplayName("일본 지명의 다른 한국어 표기로 쓴 이름도 같은 곳에 잇는다")
    void japaneseSpellingVariants() {
        rows(place(1, "九州国立博物館", "규슈 국립박물관", "Arts and Entertainment > Museum", "SIGHT", 33.52, 130.54),
                place(2, "築地本願寺", "쓰키지 혼간지", SIGHT, "SIGHT", 35.67, 139.77));

        assertThat(resolver.resolve("큐슈 국립박물관", "sight", List.of("Fukuoka"), List.of("후쿠오카"), null).place())
                .extracting(Place::getId).isEqualTo(1L);
        assertThat(resolver.resolve("츠키지 혼간지", "sight", List.of("Tokyo"), List.of("도쿄"), null).place())
                .extracting(Place::getId).isEqualTo(2L);
    }

    @Test
    @DisplayName("마지막 낱말이 장소 이름 그대로면 잇는다")
    void lastWordWithSameNameLinks() {
        rows(place(1, "화서문", null, SIGHT, "SIGHT", 37.29, 127.01));

        assertThat(resolver.resolve("수원화성 화서문", "sight", List.of("Suwon"), List.of("수원"), null).place())
                .extracting(Place::getId).isEqualTo(1L);
    }

    @Test
    @DisplayName("요구를 채웠는지도 별칭 이름으로 본다")
    void wantMatchesAliasName() {
        Place beach = place(2, "경포해수욕장", null, "Landmarks and Outdoors > Beach", "SIGHT", 37.80, 128.91);

        assertThat(WantMatcher.matches("경포해변", SavedItineraryFacts.candidateOf(beach))).isTrue();
        assertThat(WantMatcher.matches("경포해변",
                SavedItineraryFacts.candidateOf(place(3, "안목해변", null, SIGHT, "SIGHT", 37.77, 128.95)))).isFalse();
    }

    @Test
    @DisplayName("이름이 그대로 같으면 EXACT, 한국어 이름이면 ALIAS, 앞부분만 맞으면 FUZZY다. 괄호 안 위치 표기는 별칭이 아니다")
    void matchLevels() {
        rows(place(1, "화서문", null, SIGHT, "SIGHT", 37.29, 127.01),
                place(2, "BIG-O쇼 (빅오쇼)", null, SIGHT, "ACTIVITY", 34.74, 127.75),
                place(3, "浅草寺", "센소지", SIGHT, "SIGHT", 35.71, 139.79),
                place(4, "수원화성 화서문 옹성", null, SIGHT, "SIGHT", 37.29, 127.01));

        assertThat(resolver.resolve("화서문", "sight", List.of("Suwon"), List.of("수원"), null).match())
                .isEqualTo(PlaceNames.Match.EXACT);
        assertThat(resolver.resolve("빅오쇼", "sight", List.of("Yeosu"), List.of("여수"), null).place()).isNull();
        assertThat(resolver.resolve("센소지", "sight", List.of("Tokyo"), List.of("도쿄"), null).match())
                .isEqualTo(PlaceNames.Match.ALIAS);
        assertThat(resolver.resolve("수원화성 화서문", "sight", List.of("Suwon"), List.of("수원"), null).match())
                .isEqualTo(PlaceNames.Match.FUZZY);
    }

    @Test
    @DisplayName("같은 곳으로 합쳐진 행에만 있는 한국어 이름으로 찾으면 대표 행을 돌려준다")
    void mergedRowNameFindsRepresentative() {
        Place representative = place(10, "承天寺", "조텐지", SIGHT, "SIGHT", 33.59, 130.41);
        Place merged = Place.builder().id(11L).name("承天寺 Jotenji").nameKo("쇼텐지").address("주소 11").category(SIGHT)
                .region("Fukuoka").country("JP").placeRole("SIGHT").mergedIntoId(10L).mergeRelation("SAME")
                .latitude(BigDecimal.valueOf(33.59)).longitude(BigDecimal.valueOf(130.41)).build();
        Place part = Place.builder().id(12L).name("承天寺 庭園").nameKo("쇼텐지 정원").address("주소 12").category(SIGHT)
                .region("Fukuoka").country("JP").placeRole("SIGHT").mergedIntoId(10L).mergeRelation("PART")
                .latitude(BigDecimal.valueOf(33.59)).longitude(BigDecimal.valueOf(130.41)).build();
        rows(representative, merged, part);

        var hit = resolver.resolve("쇼텐지", "sight", List.of("Fukuoka"), List.of("후쿠오카"), null);
        assertThat(hit.place()).extracting(Place::getId).isEqualTo(10L);
        assertThat(hit.match()).isEqualTo(PlaceNames.Match.ALIAS);
        assertThat(resolver.resolve("쇼텐지 정원", "sight", List.of("Fukuoka"), List.of("후쿠오카"), null).place())
                .extracting(Place::getId).isNotEqualTo(12L);
    }

    @Test
    @DisplayName("역할이 아직 없는 행은 카테고리로 짐작한 역할이 맞을 때만 잇는다")
    void roleLessRowsUseCategory() {
        rows(place(1, "제주민속촌", null, "Landmarks and Outdoors > Tourist Attraction", null, 33.32, 126.84),
                place(2, "민속촌의원", null, "Health and Medicine > Medical Center", null, 33.32, 126.84));

        assertThat(resolver.resolve("제주민속촌", "sight", List.of("Jeju"), List.of("제주"), null).place())
                .extracting(Place::getId).isEqualTo(1L);
        assertThat(resolver.resolve("민속촌의원", "sight", List.of("Jeju"), List.of("제주"), null).place()).isNull();
    }

    @Test
    @DisplayName("일본 여행지는 외래어 표기법 차이(첫소리·이중모음)도 이름 그대로이거나 앞부분일 때 잇고 근거를 VARIANT로 둔다")
    void japaneseOrthographyVariants() {
        rows(fukuoka(1, "光明禅寺", "고묘젠지"), fukuoka(2, "増上寺", "조죠지"),
                fukuoka(3, "狸小路", "다누키코지 2초메"));

        var komyo = resolver.resolve("코묘젠지", "sight", List.of("Fukuoka"), List.of("후쿠오카"), null);
        assertThat(komyo.place()).extracting(Place::getId).isEqualTo(1L);
        assertThat(komyo.match()).isEqualTo(PlaceNames.Match.VARIANT);
        assertThat(resolver.resolve("조조지", "sight", List.of("Tokyo"), List.of("도쿄"), null).place())
                .extracting(Place::getId).isEqualTo(2L);
        assertThat(resolver.resolve("타누키코지", "sight", List.of("Sapporo"), List.of("삿포로"), null).place())
                .extracting(Place::getId).isEqualTo(3L);
    }

    @Test
    @DisplayName("국내 여행지에는 일본어 표기 변형을 쓰지 않는다")
    void domesticNamesKeepSpelling() {
        rows(place(1, "토담골", null, SIGHT, "SIGHT", 37.5, 127.0));

        assertThat(resolver.resolve("도담골", "sight", List.of("Seoul"), List.of("서울"), null).place()).isNull();
    }

    @Test
    @DisplayName("같은 뜻 접미어로 쓴 이름(벽화골목·벽화마을)도 잇는다")
    void sameMeaningSuffix() {
        rows(place(1, "행궁동 벽화마을", null, SIGHT, "SIGHT", 37.28, 127.01));

        var hit = resolver.resolve("행궁동 벽화골목", "sight", List.of("Suwon"), List.of("수원"), null);
        assertThat(hit.place()).extracting(Place::getId).isEqualTo(1L);
        assertThat(hit.match()).isEqualTo(PlaceNames.Match.VARIANT);
    }

    @Test
    @DisplayName("음식 이름이 두 낱말에 걸쳐서만 맞는 식당은 빼낸다(멘타이코가 라멘 타이코에 걸리지 않는다)")
    void dishDoesNotStraddleWords() {
        Place ramen = place(1, "住吉ラーメン太閤", "스미요시 라멘 타이코", "Dining and Drinking > Restaurant", "MEAL", 33.59, 130.40);
        Place mentai = place(2, "めんたい重", "멘타이주", "Dining and Drinking > Restaurant", "MEAL", 33.59, 130.40);
        Place tagged = Place.builder().id(3L).name("やまや").nameKo("야마야").address("주소 3")
                .category("Dining and Drinking > Restaurant").region("Fukuoka").country("JP").placeRole("MEAL")
                .tags(List.of("멘타이코", "정식"))
                .latitude(BigDecimal.valueOf(33.59)).longitude(BigDecimal.valueOf(130.40)).build();
        given(repository.findMealsByDishNear(anyString(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).willReturn(List.of(ramen, mentai, tagged));

        assertThat(resolver.findMealsByDish("멘타이코", List.of("Fukuoka"), new double[]{33.59, 130.40}))
                .extracting(Place::getId).containsExactly(3L);
    }

    @Test
    @DisplayName("괄호에 상위 장소를 적은 딸린 행보다 그 이름 그대로인 장소를 먼저 고른다")
    void exactNameBeatsBracketParent() {
        Place parent = place(1, "대릉원", null, SIGHT, "SIGHT", 35.838, 129.211);
        Place part = Place.builder().id(2L).name("천마총(대릉원)").address("주소 2").category(SIGHT).region("Test")
                .country("KR").placeRole("SIGHT").wikidataSitelinks(9)
                .latitude(BigDecimal.valueOf(35.838)).longitude(BigDecimal.valueOf(129.211)).build();
        rows(parent, part, place(3, "박가 부대찌게(동성로)", null, "Dining and Drinking > Restaurant", "MEAL", 35.87, 128.6));

        var hit = resolver.resolve("대릉원", "sight", List.of("Gyeongju"), List.of("경주"), null);
        assertThat(hit.place()).extracting(Place::getId).isEqualTo(1L);
        assertThat(hit.match()).isEqualTo(PlaceNames.Match.EXACT);
        assertThat(resolver.resolve("동성로", "sight", List.of("Daegu"), List.of("대구"), null).place()).isNull();
    }

    private static Place fukuoka(long id, String name, String nameKo) {
        return Place.builder().id(id).name(name).nameKo(nameKo).address("주소 " + id).category(SIGHT)
                .region("Fukuoka").country("JP").placeRole("SIGHT")
                .latitude(BigDecimal.valueOf(33.59)).longitude(BigDecimal.valueOf(130.40)).build();
    }

    private static Place place(long id, String name, String nameKo, String category, String role, double lat,
                               double lng) {
        return Place.builder().id(id).name(name).nameKo(nameKo).address("주소 " + id).category(category)
                .region("Test").country(nameKo == null ? "KR" : "JP").placeRole(role)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng)).build();
    }
}
