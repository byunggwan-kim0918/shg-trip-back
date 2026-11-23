package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
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
    private final DraftPlaceResolver resolver = new DraftPlaceResolver(repository);

    private void rows(Place... places) {
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willAnswer(inv -> {
            String q = inv.getArgument(0);
            int min = inv.getArgument(1);
            Collection<String> roles = inv.getArgument(3);
            return List.of(places).stream().filter(p -> roles.contains(p.getPlaceRole()))
                    .filter(p -> contains(p.getName(), q, min) || contains(p.getNameKo(), q, min)).toList();
        });
        given(repository.findActiveByNameEdges(anyString(), anyString(), any(), any())).willReturn(List.of());
    }

    private static boolean contains(String name, String q, int min) {
        if (name == null) return false;
        String k = DraftPlaceResolver.key(name);
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

    private static Place place(long id, String name, String nameKo, String category, String role, double lat,
                               double lng) {
        return Place.builder().id(id).name(name).nameKo(nameKo).address("주소 " + id).category(category)
                .region("Test").country(nameKo == null ? "KR" : "JP").placeRole(role)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng)).build();
    }
}
