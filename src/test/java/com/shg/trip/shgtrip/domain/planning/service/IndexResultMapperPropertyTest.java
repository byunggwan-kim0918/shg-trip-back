package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// Feature: llm-optimization, Property 11: 인덱스-장소 결합 라운드트립
class IndexResultMapperPropertyTest {

    private final IndexResultMapper mapper = new IndexResultMapper();

    /**
     * Property 11: 인덱스-장소 결합 라운드트립
     *
     * For any 유효한 IndexBasedItineraryOutput과 PlaceCandidate 리스트에 대해,
     * IndexResultMapper가 merge한 결과의 각 step.place는 해당 placeIndex가 가리키는
     * PlaceCandidate의 데이터(name, category, region)와 정확히 일치해야 한다.
     *
     */
    @Property(tries = 100)
    void mergedStepPlaceMatchesCandidateAtPlaceIndex(
            @ForAll("validOutputAndCandidates") OutputAndCandidates input
    ) {
        ItineraryData merged = mapper.mergeIndexOutput(input.output(), input.candidates());

        assertThat(merged.steps()).hasSameSizeAs(input.output().steps());

        for (int i = 0; i < merged.steps().size(); i++) {
            StepData mergedStep = merged.steps().get(i);
            IndexStepData indexStep = input.output().steps().get(i);
            PlaceCandidate expectedCandidate = input.candidates().get(indexStep.placeIndex() - 1);

            assertThat(mergedStep.place().name())
                    .as("step[%d].place.name should equal candidate[%d].name()", i, indexStep.placeIndex())
                    .isEqualTo(expectedCandidate.name());
            assertThat(mergedStep.place().category())
                    .as("step[%d].place.category should equal candidate[%d].category()", i, indexStep.placeIndex())
                    .isEqualTo(expectedCandidate.category());
            assertThat(mergedStep.place().region())
                    .as("step[%d].place.region should equal candidate[%d].region()", i, indexStep.placeIndex())
                    .isEqualTo(expectedCandidate.region());
        }
    }

    /**
     * Property 11: 인덱스-장소 결합 라운드트립 (alternatives)
     *
     * For any 유효한 IndexBasedItineraryOutput과 PlaceCandidate 리스트에 대해,
     * IndexResultMapper가 merge한 결과의 각 alternative의 name은
     * 해당 alternativeIndex가 가리키는 PlaceCandidate의 name과 정확히 일치해야 한다.
     *
     */
    @Property(tries = 100)
    void mergedAlternativeNameMatchesCandidateAtAlternativeIndex(
            @ForAll("validOutputAndCandidates") OutputAndCandidates input
    ) {
        ItineraryData merged = mapper.mergeIndexOutput(input.output(), input.candidates());

        for (int i = 0; i < merged.steps().size(); i++) {
            StepData mergedStep = merged.steps().get(i);
            IndexStepData indexStep = input.output().steps().get(i);
            List<Integer> altIndices = indexStep.alternativeIndices();

            if (altIndices == null || altIndices.isEmpty()) {
                assertThat(mergedStep.alternatives()).isEmpty();
                continue;
            }

            // Filter out out-of-bounds alternative indices (mapper skips them)
            List<Integer> validAltIndices = altIndices.stream()
                    .filter(idx -> idx >= 1 && idx <= input.candidates().size())
                    .toList();

            assertThat(mergedStep.alternatives()).hasSize(validAltIndices.size());

            for (int j = 0; j < validAltIndices.size(); j++) {
                int altIndex = validAltIndices.get(j);
                PlaceCandidate expectedCandidate = input.candidates().get(altIndex - 1);
                AlternativeData alternative = mergedStep.alternatives().get(j);

                assertThat(alternative.name())
                        .as("step[%d].alternative[%d] name should equal candidate[%d].name()", i, j, altIndex)
                        .isEqualTo(expectedCandidate.name());
            }
        }
    }

    // --- Test Data Types ---

    record OutputAndCandidates(
            IndexBasedItineraryOutput output,
            List<PlaceCandidate> candidates
    ) {}

    // --- Arbitrary Providers ---

    private static final List<String> CATEGORIES = List.of(
            "관광", "음식", "쇼핑", "카페", "숙소", "문화", "자연"
    );

    private static final List<String> REGIONS = List.of(
            "시부야", "하라주쿠", "아사쿠사", "신주쿠", "긴자", "우에노", "이케부쿠로"
    );

    @Provide
    Arbitrary<OutputAndCandidates> validOutputAndCandidates() {
        // First generate candidate count (5-80), then generate steps with valid indices
        Arbitrary<Integer> candidateCount = Arbitraries.integers().between(5, 80);

        return candidateCount.flatMap(numCandidates -> {
            List<PlaceCandidate> candidates = generateCandidates(numCandidates);

            // Generate 3-30 steps with valid indices within candidate bounds
            Arbitrary<Integer> stepCount = Arbitraries.integers().between(3, 30);

            return stepCount.flatMap(numSteps -> {
                // For each step, generate a valid placeIndex and valid alternativeIndices
                Arbitrary<IndexStepData> stepArb = generateValidStep(numCandidates);

                return stepArb.list().ofSize(numSteps).map(steps -> {
                    // Assign sequential stepOrder and dayNumber
                    List<IndexStepData> orderedSteps = new ArrayList<>();
                    for (int i = 0; i < steps.size(); i++) {
                        IndexStepData original = steps.get(i);
                        orderedSteps.add(new IndexStepData(
                                i + 1,                          // stepOrder
                                (i / 5) + 1,                    // dayNumber (5 steps per day)
                                original.startTime(),
                                original.endTime(),
                                original.placeIndex(),
                                original.alternativeIndices(),
                                original.transportationMode(),
                                original.transportationDuration(),
                                original.transportationDistance(),
                                original.transportationCost(),
                                original.notes(),
                                original.estimatedCost()
                        ));
                    }

                    IndexBasedItineraryOutput output = new IndexBasedItineraryOutput(
                            "테스트 여행",
                            "도쿄",
                            BigDecimal.valueOf(500000),
                            List.of("관광", "맛집"),
                            orderedSteps
                    );

                    return new OutputAndCandidates(output, candidates);
                });
            });
        });
    }

    private Arbitrary<IndexStepData> generateValidStep(int candidateSize) {
        Arbitrary<Integer> placeIndexArb = Arbitraries.integers().between(1, candidateSize);
        Arbitrary<Integer> altCountArb = Arbitraries.integers().between(0, Math.min(5, candidateSize - 1));

        return Combinators.combine(placeIndexArb, altCountArb).flatAs((placeIndex, altCount) -> {
            if (altCount == 0) {
                return Arbitraries.just(createStepData(placeIndex, List.of()));
            }
            // Generate altCount valid alternative indices
            Arbitrary<List<Integer>> altIndicesArb = Arbitraries.integers()
                    .between(1, candidateSize)
                    .list().ofSize(altCount);

            return altIndicesArb.map(altIndices -> createStepData(placeIndex, altIndices));
        });
    }

    private IndexStepData createStepData(int placeIndex, List<Integer> alternativeIndices) {
        return new IndexStepData(
                1,              // stepOrder (overwritten later)
                1,              // dayNumber (overwritten later)
                "09:00",
                "11:00",
                placeIndex,
                alternativeIndices,
                "WALK",
                15,
                BigDecimal.valueOf(1.2),
                BigDecimal.ZERO,
                "테스트 노트",
                BigDecimal.valueOf(5000)
        );
    }

    private List<PlaceCandidate> generateCandidates(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new PlaceCandidate(
                        i,
                        (long) i,
                        "장소_" + i,
                        "주소_" + i,
                        CATEGORIES.get((i - 1) % CATEGORIES.size()),
                        List.of("태그" + i, "태그" + (i + 1)),
                        REGIONS.get((i - 1) % REGIONS.size()),
                        "일본",
                        BigDecimal.valueOf(35.6 + i * 0.001),
                        BigDecimal.valueOf(139.7 + i * 0.001),
                        "설명_" + i,
                        BigDecimal.valueOf(4.0 + (i % 10) * 0.1),
                        0.95 - i * 0.001
                ))
                .toList();
    }
}
