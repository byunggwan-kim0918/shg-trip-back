package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 국내 두 도시 실생성 기록(로컬 테스트 계정)으로 도시 구간 분리와 이동 누락 판정을 확인한다. */
class DomesticMultiCityTest {

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();
    private final ItineraryRules rules = new ItineraryRules();
    private final Destinations destinations = Destinations.fromClasspath();

    private static final TraceFile SEOUL_BUSAN = TraceFixtures.load("/fixtures/live-223-seoul-busan.json");
    private static final TraceFile GANGNEUNG_GYEONGJU = TraceFixtures.load("/fixtures/live-225-gangneung-gyeongju.json");

    @Test
    @DisplayName("도시를 모르고 확정한 서울·부산 일정은 이동 없이 두 도시를 오가는 날이 핵심 위반으로 잡힌다")
    void mixedCitiesWithoutBlocksAreFlagged() {
        List<StepData> steps = routeOptimizer.schedule(SEOUL_BUSAN.selection(), SEOUL_BUSAN.candidates(),
                SEOUL_BUSAN.args()).steps();

        ItineraryRules.Verdict verdict = verdict(SEOUL_BUSAN, steps);
        assertThat(verdict.violations()).filteredOn(v -> v.rule() == ItineraryRules.Rule.TRAVEL_MISSING)
                .extracting(ItineraryRules.Violation::day).containsExactly(1, 2, 3, 6);
        assertThat(verdict.core()).isTrue();
    }

    @Test
    @DisplayName("서울 3일·부산 3일 배분이면 날마다 그 도시 장소만 쓰고 4일차를 KTX 이동으로 시작한다")
    void seoulBusanSplitsIntoCityBlocks() {
        Map<String, List<String>> allocation = new LinkedHashMap<>();
        allocation.put("1-3", List.of("Seoul"));
        allocation.put("4-6", List.of("Busan"));
        TripCities cities = TripCityDetector.detect("KR", List.of("Seoul", "Busan"), allocation, 6, destinations,
                SEOUL_BUSAN.args().hub());

        List<StepData> steps = routeOptimizer.schedule(SEOUL_BUSAN.selection(), SEOUL_BUSAN.candidates(),
                withCities(SEOUL_BUSAN.args(), cities)).steps();

        assertThat(cities.dayCity()).containsEntry(3, "Seoul").containsEntry(4, "Busan");
        assertEveryStepInItsCity(SEOUL_BUSAN, steps, cities);
        StepData transfer = day(steps, 4).get(0);
        assertThat(transfer.transportationMode()).isEqualTo("TRAIN");
        assertThat(transfer.transportationDuration()).isBetween(180, 230);
        assertThat(verdict(SEOUL_BUSAN, steps).violations()).extracting(ItineraryRules.Violation::rule)
                .doesNotContain(ItineraryRules.Rule.TRAVEL_MISSING);
        assertThat(new HardValidator().validate(new ItineraryData("t", "서울 부산", BigDecimal.ZERO, List.of(), steps))
                .valid()).isTrue();
    }

    @Test
    @DisplayName("차로 다니는 강릉 2일·경주 3일이면 3일차를 자동차 이동으로 시작하고 기름값은 사람 수로 곱하지 않는다")
    void gangneungGyeongjuDrivesBetweenCities() {
        Map<String, List<String>> allocation = new LinkedHashMap<>();
        allocation.put("1-2", List.of("Gangneung"));
        allocation.put("3-5", List.of("Gyeongju"));
        TripCities cities = TripCityDetector.detect("KR", List.of("Gangneung", "Gyeongju"), allocation, 5,
                destinations, null);

        List<StepData> steps = routeOptimizer.schedule(GANGNEUNG_GYEONGJU.selection(),
                GANGNEUNG_GYEONGJU.candidates(), withCities(GANGNEUNG_GYEONGJU.args(), cities)).steps();

        assertEveryStepInItsCity(GANGNEUNG_GYEONGJU, steps, cities);
        StepData transfer = day(steps, 3).get(0);
        assertThat(transfer.transportationMode()).isEqualTo("DRIVE");
        assertThat(OptimizedGenerationExecutor.scaleCostsForParty(List.of(transfer), 4).get(0).transportationCost())
                .isEqualByComparingTo(transfer.transportationCost());
        assertThat(verdict(GANGNEUNG_GYEONGJU, steps).violations()).extracting(ItineraryRules.Violation::rule)
                .doesNotContain(ItineraryRules.Rule.TRAVEL_MISSING, ItineraryRules.Rule.LEG_OVER_CAP);
    }

    private ItineraryRules.Verdict verdict(TraceFile trace, List<StepData> steps) {
        ScheduleArgs args = trace.args();
        return rules.evaluate(steps, trace.candidates(), new ItineraryRules.Context(args.pace(), args.transportPref(),
                args.startDate(), trace.dayCount(), RouteOptimizer.departureHubDeadline(args)));
    }

    private static void assertEveryStepInItsCity(TraceFile trace, List<StepData> steps, TripCities cities) {
        Map<Long, PlaceCandidate> byId = trace.candidates().stream()
                .collect(Collectors.toMap(PlaceCandidate::placeId, Function.identity(), (a, b) -> a));
        for (StepData s : steps) {
            PlaceCandidate c = byId.get(s.place().placeId());
            assertThat(cities.cityOf(c.latitude().doubleValue(), c.longitude().doubleValue()))
                    .as("day%d %s", s.dayNumber(), c.name())
                    .isEqualTo(cities.dayCity().get(s.dayNumber()));
        }
    }

    private static List<StepData> day(List<StepData> steps, int dayNumber) {
        return steps.stream().filter(s -> s.dayNumber() == dayNumber).toList();
    }

    private static ScheduleArgs withCities(ScheduleArgs a, TripCities cities) {
        return new ScheduleArgs(a.pace(), a.transportPref(), a.startDate(), a.themes(), a.hub(), a.compact(),
                a.categories(), a.couple(), a.arrivalTime(), a.departureTime(), a.country(), cities, false, false);
    }
}
