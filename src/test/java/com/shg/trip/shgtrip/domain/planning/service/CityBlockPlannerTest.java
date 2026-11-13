package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static org.assertj.core.api.Assertions.assertThat;

class CityBlockPlannerTest {

    private static final List<String> SIGHTS = List.of(
            "Landmarks and Outdoors > Historic and Protected Site", "Arts and Entertainment > Museum > Art Museum",
            "Landmarks and Outdoors > Park", "Community and Government > Spiritual Center > Buddhist Temple",
            "Landmarks and Outdoors > Garden", "Landmarks and Outdoors > Scenic Lookout");
    private static final String JAPANESE = "Dining and Drinking > Restaurant > Asian Restaurant > Japanese Restaurant > ";
    private static final List<String> MEALS = List.of(JAPANESE + "Ramen Restaurant", JAPANESE + "Sushi Restaurant",
            JAPANESE + "Tempura Restaurant", JAPANESE + "Udon Restaurant", JAPANESE + "Yakitori Restaurant",
            JAPANESE + "Japanese Curry Restaurant");
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";
    private static final String AIRPORT = "Travel and Transportation > Transport Hub > Airport";

    private static final TripCities.City TOKYO = new TripCities.City("Tokyo", "도쿄", "JP", 35.6812, 139.7671);
    private static final TripCities.City OSAKA = new TripCities.City("Osaka", "오사카", "JP", 34.6937, 135.5023);
    private static final TripCities CITIES = new TripCities(List.of(TOKYO, OSAKA),
            Map.of(1, "Tokyo", 2, "Tokyo", 3, "Osaka", 4, "Osaka"));

    private static final int NARITA = 1;
    private static final int OSAKA_STRAY = 17;
    private static final int KANSAI = 15;

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    /** 1 나리타, 2~7 도쿄 관광, 8~13 도쿄 식당, 14 도쿄 호텔, 15 간사이, 16~21 오사카 관광, 22~27 오사카 식당, 28 오사카 호텔. */
    private static List<PlaceCandidate> candidates() {
        List<PlaceCandidate> out = new ArrayList<>();
        out.add(place(1, "Narita International Airport", AIRPORT, "TRANSIT", 35.7720, 140.3929));
        addCity(out, "Tokyo", TOKYO);
        out.add(place(15, "Kansai International Airport", AIRPORT, "TRANSIT", 34.4347, 135.2441));
        addCity(out, "Osaka", OSAKA);
        return out;
    }

    private static void addCity(List<PlaceCandidate> out, String name, TripCities.City center) {
        for (int k = 0; k < 6; k++) {
            out.add(place(out.size() + 1, name + " sight " + k, SIGHTS.get(k), "SIGHT",
                    center.lat() + 0.008 * (k - 3), center.lng() + 0.006 * (k % 3)));
        }
        for (int k = 0; k < 6; k++) {
            out.add(place(out.size() + 1, name + " meal " + k, MEALS.get(k), "MEAL",
                    center.lat() + 0.007 * (k - 3), center.lng() - 0.005 * (k % 3)));
        }
        out.add(place(out.size() + 1, name + " hotel", HOTEL, "LODGING", center.lat(), center.lng()));
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat, double lng) {
        return new PlaceCandidate(index, (long) index, name, name + " address", category, List.of(), null, "JP",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.5, null, null, null,
                "SIGHT".equals(role) ? 90 : null, false, null, null, null, null, role, null, false, null, null, null);
    }

    private static SelectionOutput selection() {
        return new SelectionOutput("도쿄와 오사카", List.of(
                new SelectionOutput.DayPlan(1, NARITA, List.of(2, 3, 8, 9, OSAKA_STRAY), 14, null),
                new SelectionOutput.DayPlan(2, null, List.of(4, 5, 10, 11), 14, null),
                new SelectionOutput.DayPlan(3, null, List.of(16, 18, 22, 23), 28, null),
                new SelectionOutput.DayPlan(4, null, List.of(19, 20, 24, 25), null, KANSAI)),
                List.of(), List.of(6, 7, 12, 13, 21, 26, 27));
    }

    private static ScheduleArgs args(TripCities cities) {
        return new ScheduleArgs("normal", "any", LocalDate.of(2026, 12, 1), List.of("culture"),
                new TransportationHub("Narita International Airport", "Kansai International Airport", "AIRPORT"),
                false, List.of("attraction"), false, null, null, "JP", cities, false, false);
    }

    private static List<StepData> day(List<StepData> steps, int dayNumber) {
        return steps.stream().filter(s -> s.dayNumber() == dayNumber).toList();
    }

    @Test
    @DisplayName("도시마다 그 도시 장소만 쓰고, 다른 도시 날에 놓인 장소는 자기 도시 날로 옮긴다")
    void eachDayStaysInItsCity() {
        Map<String, PlaceCandidate> byName = candidates().stream()
                .collect(Collectors.toMap(PlaceCandidate::name, Function.identity()));
        List<StepData> steps = routeOptimizer.schedule(selection(), candidates(), args(CITIES)).steps();

        for (StepData s : steps) {
            String city = CITIES.cityOf(byName.get(s.place().name()).latitude().doubleValue(),
                    byName.get(s.place().name()).longitude().doubleValue());
            assertThat(city).as("day%d %s", s.dayNumber(), s.place().name())
                    .isEqualTo(CITIES.dayCity().get(s.dayNumber()));
        }
        String stray = byName.values().stream().filter(c -> c.index() == OSAKA_STRAY).findFirst().orElseThrow().name();
        assertThat(steps).filteredOn(s -> s.place().name().equals(stray))
                .allSatisfy(s -> assertThat(s.dayNumber()).isGreaterThanOrEqualTo(3));
    }

    @Test
    @DisplayName("도착은 첫 도시 공항, 출발은 마지막 도시 공항이고 앞 도시 마지막 날은 숙소에서 끝난다")
    void hubsAndLodgingFollowCities() {
        RouteOptimizer.ScheduleResult result = routeOptimizer.schedule(selection(), candidates(), args(CITIES));
        List<StepData> steps = result.steps();

        assertThat(result.notices()).isEmpty();

        assertThat(day(steps, 1).get(0).place().name()).isEqualTo("Narita International Airport");
        List<StepData> last = day(steps, 4);
        assertThat(last.get(last.size() - 1).place().name()).isEqualTo("Kansai International Airport");
        List<StepData> day2 = day(steps, 2);
        assertThat(day2.get(day2.size() - 1).place().name()).isEqualTo("Tokyo hotel");
        assertThat(steps).noneMatch(s -> s.dayNumber() <= 2 && s.place().name().contains("Kansai"));
    }

    @Test
    @DisplayName("다음 도시 첫 스텝에 열차 구간을 싣고, 그 이동이 끝난 뒤에 시작한다")
    void transferLegOpensNextCity() {
        List<StepData> steps = routeOptimizer.schedule(selection(), candidates(), args(CITIES)).steps();
        CityBlockPlanner.IntercityLeg leg = CityBlockPlanner.legBetween(TOKYO, OSAKA);
        StepData first = day(steps, 3).get(0);

        assertThat(first.transportationMode()).isEqualTo("TRAIN");
        assertThat(first.transportationDuration()).isEqualTo(leg.minutes());
        assertThat(toMinutesOrZero(first.startTime())).isGreaterThanOrEqualTo(CityBlockPlanner.arrivalAfter(leg));
        assertThat(steps).extracting(StepData::stepOrder)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, steps.size()).boxed().toList());
    }

    @Test
    @DisplayName("도쿄→오사카 열차는 3시간 남짓, 1인 요금 10만~13만 원이다")
    void tokyoOsakaTrain() {
        CityBlockPlanner.IntercityLeg leg = CityBlockPlanner.legBetween(TOKYO, OSAKA);

        assertThat(leg.minutes()).isBetween(180, 210);
        assertThat(leg.costWon().intValue()).isBetween(100_000, 130_000);
    }

    @Test
    @DisplayName("일차별 도시를 이어진 구간으로 나눈다")
    void blocksFromDayCity() {
        assertThat(CityBlockPlanner.blocksOf(CITIES)).containsExactly(
                new CityBlockPlanner.Block("Tokyo", 1, 2), new CityBlockPlanner.Block("Osaka", 3, 4));
    }
}
