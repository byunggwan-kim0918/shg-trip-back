package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryScorerTest {

    private final ItineraryScorer scorer = new ItineraryScorer();

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String SIGHT = "Landmarks and Outdoors > Scenic Lookout";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";

    @Test
    @DisplayName("115 재구성 일정: 골든 스냅샷과 같은 숙소 수를 세고 상한(2곳) 초과를 깎는다")
    void scores115AgainstGolden() {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        SelectionOutput selection = Itinerary115Fixture.selection();
        ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant"), false);
        List<StepData> steps = new RouteOptimizer().schedule(selection, candidates, args).steps();

        ScoreReport report = scorer.score(new ItineraryScorer.Input(
                steps, "normal", "car", args.startDate(), candidates, selection));

        assertThat(report.metrics().get("lodgings").longValue()).isEqualTo(3);
        assertThat(report.components().get("lodging")).isLessThan(1.0);
        assertThat(report.total()).isBetween(0.0, 100.0);
        assertThat(report.estimated()).isEmpty();
        assertThat(report.metrics()).containsKey("selectionRetention");
    }

    @Test
    @DisplayName("점심 창을 덮는 날에 식사가 없으면 하드 위반")
    void missingLunchIsHardViolation() {
        List<StepData> day = List.of(
                step(1, 1, "09:00", "11:00", "전망대", SIGHT, null, null),
                step(2, 1, "11:20", "13:40", "해변", SIGHT, 20, 10.0),
                step(3, 1, "14:00", "16:00", "오름", SIGHT, 20, 10.0));

        ScoreReport report = scorer.score(new ItineraryScorer.Input(day, "normal", "car", null, null, null));

        assertThat(report.violations()).contains("day1 점심 없음");
        assertThat(report.estimated()).contains("prominence", "intent").doesNotContain("pace");
    }

    @Test
    @DisplayName("휴무 요일에 넣은 장소는 하드 위반 — 후보에 영업시간이 있을 때만 판정")
    void closedDayIsHardViolation() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        PlaceCandidate museum = candidate(1, "박물관", "Arts and Entertainment > Museum", "월요일 휴무");
        List<StepData> day = List.of(step(1, 1, "10:00", "11:30", "박물관", museum.category(), null, null));

        ScoreReport report = scorer.score(new ItineraryScorer.Input(
                day, "normal", "car", monday, List.of(museum), null));

        assertThat(report.violations()).anyMatch(v -> v.contains("휴무일 방문(박물관)"));
    }

    @Test
    @DisplayName("비교는 하드 위반 수가 먼저 — 점수가 높아도 위반이 많으면 진다")
    void hardViolationsDominateTotal() {
        ScoreReport fewerViolations = new ScoreReport(0, 40, java.util.Map.of(), List.of(), List.of(), java.util.Map.of());
        ScoreReport higherTotal = new ScoreReport(1, 90, java.util.Map.of(), List.of("x"), List.of(), java.util.Map.of());

        assertThat(fewerViolations.betterThan(higherTotal)).isTrue();
        assertThat(higherTotal.betterThan(fewerViolations)).isFalse();
    }

    @Test
    @DisplayName("3박에 숙소 3곳이면 상한(2곳) 대비 2/3점, 같은 숙소 연박이면 만점")
    void lodgingCountAgainstCap() {
        List<StepData> threeHotels = fourDaysEndingAt(List.of("호텔A", "호텔B", "호텔C"));
        List<StepData> twoHotels = fourDaysEndingAt(List.of("호텔A", "호텔A", "호텔B"));

        double three = scorer.score(new ItineraryScorer.Input(threeHotels, "normal", "car", null, null, null))
                .components().get("lodging");
        double two = scorer.score(new ItineraryScorer.Input(twoHotels, "normal", "car", null, null, null))
                .components().get("lodging");

        assertThat(three).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(two).isEqualTo(1.0);
    }

    private List<StepData> fourDaysEndingAt(List<String> hotels) {
        List<StepData> steps = new ArrayList<>();
        int order = 1;
        for (int d = 1; d <= 4; d++) {
            steps.add(step(order++, d, "12:00", "13:00", "식당" + d, RESTAURANT, null, null));
            if (d < 4) steps.add(step(order++, d, "20:00", "20:30", hotels.get(d - 1), HOTEL, 10, 5.0));
        }
        return steps;
    }

    private static StepData step(int order, int day, String start, String end, String name, String category,
                                 Integer travelMin, Double km) {
        return new StepData(order, day, start, end, new PlaceData(name, null, category, "Jeju", "KR"),
                List.of(), travelMin == null ? null : "CAR", travelMin,
                km == null ? null : BigDecimal.valueOf(km), null, null, null);
    }

    private static PlaceCandidate candidate(int index, String name, String category, String openingHours) {
        return new PlaceCandidate(index, (long) index, name, null, category, List.of(), "Jeju", "KR",
                BigDecimal.valueOf(33.5), BigDecimal.valueOf(126.5), null, null, 0.9, null, openingHours,
                null, 90, false, null, null, null, null);
    }
}
