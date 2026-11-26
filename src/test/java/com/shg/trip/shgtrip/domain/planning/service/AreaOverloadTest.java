package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AreaOverloadTest {

    private static final Destinations REGISTRY = Destinations.fromClasspath();
    private static final Destination SAPPORO = REGISTRY.findByName("삿포로").orElseThrow();
    private static final Destination OTARU = REGISTRY.findByName("오타루").orElseThrow();
    private static final Destination FURANO = REGISTRY.findByName("후라노").orElseThrow();
    private static final Destination BIEI = REGISTRY.findByName("비에이").orElseThrow();
    private static final LocalDate START = LocalDate.of(2027, 7, 15);

    @Test
    @DisplayName("홋카이도 4박: 묵는 삿포로·후라노에서 오타루·비에이를 당일로 다녀오면 기간 안에 들어간다")
    void hokkaidoFits() {
        assertThat(TripFeasibility.areaOverload(List.of(SAPPORO, OTARU, FURANO, BIEI), List.of(SAPPORO, FURANO), START,
                START.plusDays(4), null, null, "any")).isEmpty();
    }

    @Test
    @DisplayName("묵는 곳에서 편도로도 먼 지역은 당일로 다녀오기 어렵다고 보고, 지역 수만으로는 막지 않는다")
    void farFromStayIsFlagged() {
        Destination fukuoka = REGISTRY.findByName("후쿠오카").orElseThrow();
        Destination tokyo = REGISTRY.findByName("도쿄").orElseThrow();

        assertThat(TripFeasibility.areaOverload(List.of(tokyo, fukuoka), List.of(tokyo), START, START.plusDays(4),
                null, null, "any")).hasValueSatisfying(o -> assertThat(o.far()).containsExactly(fukuoka));
        assertThat(TripFeasibility.areaOverload(List.of(SAPPORO, OTARU, FURANO, BIEI), List.of(), START,
                START.plusDays(3), null, null, "any")).as("묵을 곳을 안 정한 4곳 3박").isEmpty();
    }

    @Test
    @DisplayName("기간에 비해 볼 시간이 모자라면 오가는 시간이 긴 지역부터 덜 넣을 곳으로 고른다")
    void tooManyForShortTrip() {
        assertThat(TripFeasibility.areaOverload(List.of(SAPPORO, OTARU, FURANO, BIEI), List.of(SAPPORO), START,
                START, LocalTime.of(14, 0), null, "any")).hasValueSatisfying(o -> {
            assertThat(o.needMinutes()).isGreaterThan(o.haveMinutes());
            assertThat(o.unlikely()).doesNotContain(SAPPORO).isNotEmpty();
            assertThat(o.unlikely().get(0)).isIn(FURANO, BIEI);
        });
    }
}
