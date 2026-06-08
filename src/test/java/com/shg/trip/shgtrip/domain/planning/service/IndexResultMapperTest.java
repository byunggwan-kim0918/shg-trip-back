package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexResultMapperTest {

    private IndexResultMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new IndexResultMapper();
    }

    private List<PlaceCandidate> createCandidates(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new PlaceCandidate(
                        i,
                        (long) i,
                        "Place" + i,
                        "Address" + i,
                        "Category" + (i % 3 == 0 ? "Food" : i % 3 == 1 ? "Tour" : "Shop"),
                        List.of("tag" + i),
                        "Region" + i,
                        "Country",
                        BigDecimal.valueOf(35.0 + i * 0.01),
                        BigDecimal.valueOf(139.0 + i * 0.01),
                        "Description for place " + i,
                        BigDecimal.valueOf(4.0 + i * 0.1),
                        0.9 - i * 0.01
                ))
                .toList();
    }

    @Test
    @DisplayName("기본 변환: placeIndex로 후보 장소를 정확히 조회한다")
    void mergeIndexOutput_basic() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                2, List.of(3, 4),
                "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO,
                "Visit place", BigDecimal.valueOf(5000)
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Test Trip", "Tokyo", BigDecimal.valueOf(100000),
                List.of("culture"), List.of(step)
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        assertThat(result.title()).isEqualTo("Test Trip");
        assertThat(result.destination()).isEqualTo("Tokyo");
        assertThat(result.estimatedCost()).isEqualTo(BigDecimal.valueOf(100000));
        assertThat(result.tags()).containsExactly("culture");
        assertThat(result.steps()).hasSize(1);

        StepData resultStep = result.steps().get(0);
        assertThat(resultStep.stepOrder()).isEqualTo(1);
        assertThat(resultStep.dayNumber()).isEqualTo(1);
        assertThat(resultStep.startTime()).isEqualTo("09:00");
        assertThat(resultStep.endTime()).isEqualTo("11:00");
        assertThat(resultStep.place().name()).isEqualTo("Place2");
        assertThat(resultStep.place().category()).isEqualTo(candidates.get(1).category());
        assertThat(resultStep.place().region()).isEqualTo("Region2");
        assertThat(resultStep.place().country()).isEqualTo("Country");
        assertThat(resultStep.transportationMode()).isEqualTo("WALK");
        assertThat(resultStep.transportationDuration()).isEqualTo(10);
        assertThat(resultStep.notes()).isEqualTo("Visit place");
        assertThat(resultStep.estimatedCost()).isEqualTo(BigDecimal.valueOf(5000));
    }

    @Test
    @DisplayName("alternatives 변환: alternativeIndices로 대안 장소를 정확히 조회한다")
    void mergeIndexOutput_alternatives() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                1, List.of(2, 3, 4),
                "WALK", 5, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of(step)
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        List<AlternativeData> alts = result.steps().get(0).alternatives();
        assertThat(alts).hasSize(3);
        assertThat(alts.get(0).name()).isEqualTo("Place2");
        assertThat(alts.get(1).name()).isEqualTo("Place3");
        assertThat(alts.get(2).name()).isEqualTo("Place4");
    }

    @Test
    @DisplayName("경계값: 인덱스 1과 마지막 인덱스가 정상 처리된다")
    void mergeIndexOutput_boundaryIndices() {
        List<PlaceCandidate> candidates = createCandidates(10);
        IndexStepData step1 = new IndexStepData(
                1, 1, "09:00", "10:00",
                1, List.of(10),
                "WALK", 5, null, null, null, null
        );
        IndexStepData step2 = new IndexStepData(
                2, 1, "10:00", "11:00",
                10, List.of(1),
                "BUS", 15, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Osaka", BigDecimal.ZERO, List.of(), List.of(step1, step2)
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).place().name()).isEqualTo("Place1");
        assertThat(result.steps().get(0).alternatives().get(0).name()).isEqualTo("Place10");
        assertThat(result.steps().get(1).place().name()).isEqualTo("Place10");
        assertThat(result.steps().get(1).alternatives().get(0).name()).isEqualTo("Place1");
    }

    @Test
    @DisplayName("placeIndex가 범위를 벗어나면 IllegalArgumentException을 던진다")
    void mergeIndexOutput_placeIndexOutOfBounds_throws() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                6, List.of(),
                "WALK", 5, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of(step)
        );

        assertThatThrownBy(() -> mapper.mergeIndexOutput(output, candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    @DisplayName("placeIndex 0은 범위를 벗어나 예외를 던진다")
    void mergeIndexOutput_placeIndexZero_throws() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                0, List.of(),
                "WALK", 5, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of(step)
        );

        assertThatThrownBy(() -> mapper.mergeIndexOutput(output, candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    @DisplayName("alternativeIndex가 범위를 벗어나면 해당 대안만 skip한다")
    void mergeIndexOutput_alternativeIndexOutOfBounds_skips() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                1, List.of(2, 99, 3, 0),
                "WALK", 5, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of(step)
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        // 99와 0이 skip되어 2개만 남음
        List<AlternativeData> alts = result.steps().get(0).alternatives();
        assertThat(alts).hasSize(2);
        assertThat(alts.get(0).name()).isEqualTo("Place2");
        assertThat(alts.get(1).name()).isEqualTo("Place3");
    }

    @Test
    @DisplayName("빈 alternatives 리스트는 빈 리스트를 반환한다")
    void mergeIndexOutput_emptyAlternatives() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                1, List.of(),
                "WALK", 5, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of(step)
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        assertThat(result.steps().get(0).alternatives()).isEmpty();
    }

    @Test
    @DisplayName("null alternatives는 빈 리스트를 반환한다")
    void mergeIndexOutput_nullAlternatives() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                1, null,
                "WALK", 5, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of(step)
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        assertThat(result.steps().get(0).alternatives()).isEmpty();
    }

    @Test
    @DisplayName("null output이면 IllegalArgumentException을 던진다")
    void mergeIndexOutput_nullOutput_throws() {
        List<PlaceCandidate> candidates = createCandidates(5);

        assertThatThrownBy(() -> mapper.mergeIndexOutput(null, candidates))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 candidates 리스트면 IllegalArgumentException을 던진다")
    void mergeIndexOutput_emptyCandidates_throws() {
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of()
        );

        assertThatThrownBy(() -> mapper.mergeIndexOutput(output, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null steps 리스트는 빈 steps를 가진 ItineraryData를 반환한다")
    void mergeIndexOutput_nullSteps() {
        List<PlaceCandidate> candidates = createCandidates(5);
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), null
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        assertThat(result.steps()).isEmpty();
        assertThat(result.title()).isEqualTo("Trip");
    }

    @Test
    @DisplayName("여러 step이 올바르게 변환된다")
    void mergeIndexOutput_multipleSteps() {
        List<PlaceCandidate> candidates = createCandidates(10);
        List<IndexStepData> steps = List.of(
                new IndexStepData(1, 1, "09:00", "10:30", 1, List.of(5, 6), "WALK", 10, null, null, "Morning", BigDecimal.valueOf(1000)),
                new IndexStepData(2, 1, "11:00", "12:30", 3, List.of(7, 8), "SUBWAY", 20, null, null, "Lunch", BigDecimal.valueOf(15000)),
                new IndexStepData(3, 1, "13:00", "15:00", 5, List.of(9, 10), "BUS", 15, null, null, "Afternoon", BigDecimal.valueOf(5000))
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Multi Step Trip", "Kyoto", BigDecimal.valueOf(50000), List.of("temple", "food"), steps
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(0).place().name()).isEqualTo("Place1");
        assertThat(result.steps().get(1).place().name()).isEqualTo("Place3");
        assertThat(result.steps().get(2).place().name()).isEqualTo("Place5");
        assertThat(result.steps().get(0).alternatives()).hasSize(2);
        assertThat(result.steps().get(1).alternatives()).hasSize(2);
        assertThat(result.steps().get(2).alternatives()).hasSize(2);
    }

    @Test
    @DisplayName("PlaceData의 address는 PlaceCandidate에서 전달된다")
    void mergeIndexOutput_placeData_addressFromCandidate() {
        List<PlaceCandidate> candidates = createCandidates(3);
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00",
                1, List.of(2),
                "WALK", 5, null, null, null, null
        );
        IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                "Trip", "Seoul", BigDecimal.ZERO, List.of(), List.of(step)
        );

        ItineraryData result = mapper.mergeIndexOutput(output, candidates);

        assertThat(result.steps().get(0).place().address()).isEqualTo("Address1");
        assertThat(result.steps().get(0).alternatives().get(0).address()).isEqualTo("Address2");
    }
}
