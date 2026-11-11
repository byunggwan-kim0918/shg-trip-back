package com.shg.trip.shgtrip.domain.place.destination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationsTest {

    private final Destinations destinations = Destinations.fromClasspath();

    @Test
    @DisplayName("여행지 목록을 읽고, 해외 지역만 목록에 있다")
    void loadsOverseasDestinationsOnly() {
        assertThat(destinations.all()).isNotEmpty();
        assertThat(destinations.all().stream().map(Destination::country).collect(Collectors.toSet()))
                .isEqualTo(Set.of("JP", "CN", "VN", "TW"));
        assertThat(destinations.isOverseas("Tokyo")).isTrue();
        assertThat(destinations.isOverseas("Seoul")).isFalse();
        assertThat(destinations.find("Jiufen")).get()
                .satisfies(d -> {
                    assertThat(d.nameKo()).isEqualTo("지우펀");
                    assertThat(d.parent()).isEqualTo("Taipei");
                    assertThat(d.isCity()).isFalse();
                });
    }

    @Test
    @DisplayName("반경이 겹치면 거리/반경이 작은 여행지에 배정한다(추출기와 같은 규칙)")
    void nearestByRadiusRatio() {
        // 오사카(반경 20km)·나라(10km)가 겹치는 구간
        assertThat(destinations.nearest("JP", 34.686, 135.712)).get().extracting(Destination::name).isEqualTo("Nara");
        assertThat(destinations.nearest("JP", 34.686, 135.700)).get().extracting(Destination::name).isEqualTo("Osaka");
        // 지우펀 옛거리는 타이베이 반경 밖, 지우펀 반경 안
        assertThat(destinations.nearest("TW", 25.1097, 121.8452)).get().extracting(Destination::name).isEqualTo("Jiufen");
    }

    @Test
    @DisplayName("다른 나라 여행지나 반경 밖에는 배정하지 않는다")
    void nearestRespectsCountryAndRadius() {
        assertThat(destinations.nearest("CN", 34.686, 135.712)).isEmpty();
        assertThat(destinations.nearest("JP", 37.5665, 126.9780)).isEmpty();
    }

    @Test
    @DisplayName("표준명·한국어 이름은 findByName, 별칭(하위 구역·옛 이름)은 resolve로만 찾는다")
    void findByNameAndResolve() {
        assertThat(destinations.findByName("ho chi minh city")).get().extracting(Destination::name).isEqualTo("Ho Chi Minh City");
        assertThat(destinations.findByName("호찌민")).get().extracting(Destination::name).isEqualTo("Ho Chi Minh City");
        assertThat(destinations.findByName("Hochiminh City")).get().extracting(Destination::name).isEqualTo("Ho Chi Minh City");
        assertThat(destinations.findByName("Saigon")).isEmpty();
        assertThat(destinations.resolve("Saigon")).get().extracting(Destination::name).isEqualTo("Ho Chi Minh City");
        assertThat(destinations.resolve("Asakusa")).get().extracting(Destination::name).isEqualTo("Tokyo");
        assertThat(destinations.resolve("Busan")).isEmpty();
        assertThat(destinations.resolve(null)).isEmpty();
        assertThat(destinations.resolve("  ")).isEmpty();
    }

    @Test
    @DisplayName("표준명·한국어 이름·별칭은 각자 자기 여행지로만 찾아지고, 한국 도시명과 겹치지 않는다")
    void namesAndAliasesResolveUniquely() {
        assertThat(destinations.all()).allSatisfy(d -> {
            assertThat(destinations.findByName(d.name())).as(d.name()).get().isEqualTo(d);
            assertThat(destinations.findByName(d.nameKo())).as(d.nameKo()).get().isEqualTo(d);
            assertThat(d.aliases()).allSatisfy(a -> assertThat(destinations.resolve(a)).as(a).get().isEqualTo(d));
        });
        for (String korea : List.of("Seoul", "Busan", "Jeju", "Incheon", "Daegu", "Daejeon", "Gwangju", "Suwon",
                "Gangneung", "Gyeongju", "Jeonju", "Yeosu", "서울", "부산", "제주")) {
            assertThat(destinations.resolve(korea)).as(korea).isEmpty();
        }
    }

    @Test
    @DisplayName("상위 여행지를 끝까지 따라간다. 목록에 없으면 그대로다")
    void rootOf() {
        assertThat(destinations.rootOf("Kinosaki")).isEqualTo("Osaka");
        assertThat(destinations.rootOf("Nara")).isEqualTo("Osaka");
        assertThat(destinations.rootOf("Osaka")).isEqualTo("Osaka");
        assertThat(destinations.rootOf("Busan")).isEqualTo("Busan");
    }

    @Test
    @DisplayName("관문 없는 근교 여행지는 상위 여행지에서 관문을 찾는다")
    void gatewayRegion() {
        assertThat(destinations.gatewayRegion("Hoi An")).isEqualTo("Da Nang");
        assertThat(destinations.gatewayRegion("Hakone")).isEqualTo("Hakone");
        assertThat(destinations.gatewayRegion("Tokyo")).isEqualTo("Tokyo");
        assertThat(destinations.gatewayRegion("Busan")).isEqualTo("Busan");
        assertThat(destinations.all()).filteredOn(d -> d.gateways().isEmpty() && d.parent() != null)
                .allSatisfy(d -> assertThat(destinations.find(destinations.gatewayRegion(d.name())))
                        .get().satisfies(p -> assertThat(p.gateways()).isNotEmpty()));
    }

    @Test
    @DisplayName("이름이 겹치면 읽지 않는다. 이름이 places.region 값이다")
    void rejectsDuplicateNames() {
        Destination a = new Destination("JP", "Osaka", "오사카", 34.69, 135.50, 20, "city", null, null, null);
        assertThatThrownBy(() -> new Destinations(List.of(a, a))).isInstanceOf(IllegalStateException.class);
    }
}
