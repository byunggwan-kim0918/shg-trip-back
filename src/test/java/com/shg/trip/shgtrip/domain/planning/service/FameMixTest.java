package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FameMixTest {

    private static PlaceCandidate sight(long id, String name, Integer sitelinks) {
        return new PlaceCandidate((int) id, id, name, name + " 주소", "Landmarks and Outdoors > Tourist Attraction", List.of(),
                "Jeju", "KR", BigDecimal.valueOf(33.4), BigDecimal.valueOf(126.5), null, null, 0.5, null, null, null,
                null, false, null, null, null, sitelinks, "SIGHT", null, false, null, null, null, null);
    }

    private static final PlaceCandidate HALLASAN = sight(1, "한라산", 47);
    private static final PlaceCandidate SEONGSAN = sight(2, "성산일출봉", 16);
    private static final PlaceCandidate SAEBYEOL = sight(3, "새별오름", 5);
    private static final PlaceCandidate PARK = sight(4, "제주조각공원", 0);
    private static final PlaceCandidate OREUM = sight(5, "이름 없는 오름", 0);

    @Test
    @DisplayName("취향에 quiet가 있으면 한적한 쪽이고, 없으면 섞는 쪽이다")
    void fromThemes() {
        assertThat(FameMix.of(List.of("nature", "quiet"))).isEqualTo(FameMix.QUIET);
        assertThat(FameMix.of(List.of("food"))).isEqualTo(FameMix.BALANCED);
        assertThat(FameMix.of(null)).isEqualTo(FameMix.BALANCED);
    }

    @Test
    @DisplayName("섞는 쪽은 그날 대표 명소가 이미 둘이면 덜 알려진 곳부터, 아니면 유명한 곳부터 채운다")
    void balancedDependsOnTheDay() {
        assertThat(FameMix.BALANCED.fillDirection(List.of(HALLASAN, SEONGSAN))).isEqualTo(-1);
        assertThat(FameMix.BALANCED.fillDirection(List.of(HALLASAN, PARK))).isEqualTo(1);
        assertThat(FameMix.BALANCED.fillDirection(List.of())).isEqualTo(1);
        assertThat(FameMix.QUIET.fillDirection(List.of())).isEqualTo(-1);
    }

    @Test
    @DisplayName("근처 후보는 성향 쪽에서 고르고, 덜 알려진 쪽으로 고를 때만 유명한 곳 두 곳을 받침으로 남긴다")
    void pickNearbyLeansButKeepsOpposite() {
        List<PlaceCandidate> pool = new java.util.ArrayList<>(List.of(HALLASAN, SEONGSAN, SAEBYEOL));
        IntStream.rangeClosed(10, 19).forEach(i -> pool.add(sight(i, "무명" + i, 0)));
        long seed = FameMix.seedOf("제주", "2027-05-01");

        List<String> quiet = names(FameMix.pickNearby(pool, -1, seed, 8));
        List<String> famous = names(FameMix.pickNearby(pool, 1, seed, 8));

        assertThat(quiet).hasSize(8);
        assertThat(quiet.subList(0, 6)).allMatch(n -> n.startsWith("무명"));
        assertThat(quiet.subList(6, 8)).containsExactly("한라산", "성산일출봉");
        assertThat(famous).startsWith("한라산", "성산일출봉", "새별오름");
        assertThat(famous.subList(3, 8)).allMatch(n -> n.startsWith("무명"));
    }

    @Test
    @DisplayName("흔들림은 유명도가 거의 같은 곳끼리만 순서를 바꾸고, 같은 요청은 JVM이 달라도 같은 순서다")
    void varietyOnlyAmongNearTies() {
        List<PlaceCandidate> ties = IntStream.rangeClosed(10, 30).mapToObj(i -> sight(i, "무명" + i, 0)).toList();

        assertThat(FameMix.seedOf("제주", "2027-05-01", "COUPLE", "nature,quiet"))
                .isEqualTo((long) "제주\u001f2027-05-01\u001fCOUPLE\u001fnature,quiet".hashCode());
        assertThat(names(FameMix.pickNearby(ties, 1, 1L, 21))).isEqualTo(names(FameMix.pickNearby(ties, 1, 1L, 21)));
        assertThat(names(FameMix.pickNearby(ties, 1, 1L, 21))).isNotEqualTo(names(FameMix.pickNearby(ties, 1, 2L, 21)));
        for (long seed = 0; seed < 50; seed++) {
            assertThat(FameMix.nearbyScore(SEONGSAN, 1, seed)).isGreaterThan(FameMix.nearbyScore(PARK, 1, seed));
        }
    }

    @Test
    @DisplayName("대표 명소는 언어판 3개 이상(알려진 정도 포함)이고 경기장·대학은 세지 않는다")
    void notable() {
        PlaceCandidate famousForest = new PlaceCandidate(9, 9L, "사려니숲길", "주소", "Landmarks and Outdoors > Hiking Trail",
                List.of(), "Jeju", "KR", BigDecimal.ONE, BigDecimal.ONE, null, null, 0.5, null, null, null, null, false,
                null, null, null, 0, "SIGHT", null, false, null, null, null, (short) 2);
        PlaceCandidate stadium = new PlaceCandidate(8, 8L, "제주월드컵경기장", "주소", "Arts and Entertainment > Stadium",
                List.of(), "Jeju", "KR", BigDecimal.ONE, BigDecimal.ONE, null, null, 0.5, null, null, null, null, false,
                null, null, null, 30, "SIGHT", null, false, null, null, null, null);

        assertThat(FameMix.isNotable(famousForest)).isTrue();
        assertThat(FameMix.isNotable(SAEBYEOL)).isTrue();
        assertThat(FameMix.isNotable(PARK)).isFalse();
        assertThat(FameMix.isNotable(stadium)).isFalse();
    }

    @Test
    @DisplayName("초안 요청에는 한적한 곳 위주일 때만 성향을 적는다")
    void draftLine() {
        assertThat(FameMix.BALANCED.draftLine()).isNull();
        assertThat(FameMix.QUIET.draftLine()).isEqualTo("한적한 곳 위주");
    }

    private static List<String> names(List<PlaceCandidate> places) {
        return places.stream().map(PlaceCandidate::name).toList();
    }
}
