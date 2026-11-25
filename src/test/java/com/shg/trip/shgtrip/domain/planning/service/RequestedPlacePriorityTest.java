package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 사용자가 요청한 장소가 모델이 고른 장소보다 먼저 일정에 남는지. */
class RequestedPlacePriorityTest {

    /** 수요일에 시작하는 사흘 여행. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2027, 5, 19);
    private static final Destination SEOUL = DomesticCities.find("서울").orElseThrow();
    private static long nextId = 1;

    private static PlaceCandidate sight(String name, String hours) {
        return new PlaceCandidate(1, nextId++, name, null, "Landmarks and Outdoors > Park", List.of(), "Seoul", "KR",
                BigDecimal.valueOf(37.57), BigDecimal.valueOf(126.98), null, null, 0.0, null, hours, null, null, false,
                null, null, null, null, "SIGHT", null, false, null, null, null, null);
    }

    private static DayBuilder.Stop stop(PlaceCandidate p, boolean asked) {
        return new DayBuilder.Stop(p, "sight", "afternoon", List.of(), asked);
    }

    private static DraftItineraryPlanner.Request threeDays(LocalTime departure) {
        VectorEnrichedInput input = new VectorEnrichedInput("서울", List.of("food"), List.of("attraction"), "normal",
                "any", new BigDecimal("500000"), WEDNESDAY, WEDNESDAY.plusDays(2), "서울 여행", List.of(), "서울", "KR",
                List.of("Seoul"), List.of("명소"), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.COUPLE, 2, List.of());
        return new DraftItineraryPlanner.Request(input, null, List.of(SEOUL), null, departure, null, null, List.of(),
                List.of(), Map.of(), null, null, Map.of(), Set.of(), List.of());
    }

    @Test
    @DisplayName("요청 장소가 앞날에 넣은 모델 추천 장소와 같은 곳으로 묶이면 추천 장소를 빼고 요청 장소를 넣는다")
    void requestTakesOverEarlierDraftPlace() {
        DayBuilder.Stop draft = stop(sight("強羅公園", null), false);
        List<List<DayBuilder.Stop>> byDay = List.of(new ArrayList<>(List.of(draft)), new ArrayList<>());
        List<String> notes = new ArrayList<>();

        boolean placeable = DraftItineraryPlanner.yieldToRequest(draft, sight("大涌谷", null), byDay,
                new ArrayList<>(), notes);

        assertThat(placeable).isTrue();
        assertThat(byDay.get(0)).isEmpty();
        assertThat(notes).containsExactly("1일차 뺌: 強羅公園(같은 곳을 요청함: 大涌谷)");
    }

    @Test
    @DisplayName("앞서 넣은 추천 장소가 요청한 곳 안의 시설이면 이미 요청을 채운 것이고, 지역 전체로 묶였을 뿐이면 채우지 못한 것이다")
    void earlierPlaceServesRequestOnlyWhenPartOfIt() {
        DayBuilder.Stop tower = stop(sight("한빛탑", null), false);
        DayBuilder.Stop park = stop(sight("強羅公園", null), false);

        assertThat(DraftItineraryPlanner.servesRequest(tower,
                new TripConditions.Want("엑스포과학공원", null, List.of(), null),
                new PlaceFactsReader.PlaceFact(3, "엑스포과학공원", true, Set.of()))).isTrue();
        assertThat(DraftItineraryPlanner.servesRequest(park,
                new TripConditions.Want("오와쿠다니", null, List.of("大涌谷"), null),
                new PlaceFactsReader.PlaceFact(1, "하코네", true, Set.of()))).isFalse();
    }

    @Test
    @DisplayName("앞서 넣은 곳도 요청 장소면 같은 요청이 두 번 온 것이라 앞의 것을 남긴다")
    void earlierRequestKeepsPlace() {
        DayBuilder.Stop asked = stop(sight("大涌谷", null), true);
        List<List<DayBuilder.Stop>> byDay = List.of(new ArrayList<>(List.of(asked)));

        boolean placeable = DraftItineraryPlanner.yieldToRequest(asked, sight("大涌谷自然研究路", null), byDay,
                new ArrayList<>(), new ArrayList<>());

        assertThat(placeable).isFalse();
        assertThat(byDay.get(0)).containsExactly(asked);
    }

    @Test
    @DisplayName("그날 넣지 못한 요청 장소는 같은 도시의 뒷날 가운데 그 장소가 쉬지 않고 볼 낮이 있는 날로 옮긴다")
    void missedRequestMovesToLaterOpenDay() {
        PlaceCandidate closedThursday = sight("목요휴관 정원", "목요일: 휴무일");
        List<Destination> cities = List.of(SEOUL, SEOUL, SEOUL);

        assertThat(DraftItineraryPlanner.laterDayFor(closedThursday, 1, cities, threeDays(null), 3)).isEqualTo(3);
        assertThat(DraftItineraryPlanner.laterDayFor(closedThursday, 1, cities, threeDays(LocalTime.of(10, 0)), 3))
                .as("아침 출발일은 한 곳을 볼 낮도 없다").isNull();
        assertThat(DraftItineraryPlanner.laterDayFor(closedThursday, 3, cities, threeDays(null), 3))
                .as("앞날은 이미 지어 다시 짓지 않는다").isNull();
    }

    @Test
    @DisplayName("넣지 못한 요청 장소는 여행자 안내에 까닭을 붙인다")
    void missedRequestExplainsCause() {
        PlaceCandidate place = sight("오와쿠다니", null);
        DraftItineraryPlanner.Result result = new DraftItineraryPlanner.Result(List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), List.of(new DraftItineraryPlanner.Omission(2, place,
                DayBuilder.DEPARTURE_TOO_SOON, DraftItineraryPlanner.MissCause.DEPARTURE)));

        assertThat(TripPlanner.missedWhy(new TripConditions.Want("오와쿠다니", null, List.of(), null), result))
                .contains("출발 시각");
        assertThat(TripPlanner.missedWhy(new TripConditions.Want("하코네 신사", null, List.of(), null), result))
                .isEmpty();
    }
}
