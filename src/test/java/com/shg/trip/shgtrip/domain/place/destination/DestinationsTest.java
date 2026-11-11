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
    @DisplayName("이름이 겹치면 읽지 않는다. 이름이 places.region 값이다")
    void rejectsDuplicateNames() {
        Destination a = new Destination("JP", "Osaka", "오사카", 34.69, 135.50, 20, "city", null, null, null);
        assertThatThrownBy(() -> new Destinations(List.of(a, a))).isInstanceOf(IllegalStateException.class);
    }
}
