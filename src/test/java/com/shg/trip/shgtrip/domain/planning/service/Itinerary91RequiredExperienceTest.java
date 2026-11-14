package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest.RequiredExperience;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 선택 단계가 광안리를 고르지 않아도 "광안리 야경" 필수 경험이 일정에 들어가는지 91 기록으로 본다. */
class Itinerary91RequiredExperienceTest {

    private static final int GWANGALLI_BEACH = 65;

    private final TraceFile trace = Itinerary91Fixture.load();

    @Test
    @DisplayName("선택 결과에서 광안리해수욕장과 시간대 표시를 빼도 필수 경험으로 주입돼 저녁에 들어간다")
    void requiredExperienceDoesNotDependOnSelection() {
        SelectionOutput base = trace.selection();
        List<SelectionOutput.DayPlan> days = base.days().stream()
                .map(d -> new SelectionOutput.DayPlan(d.dayNumber(), d.arrivalHubIndex(),
                        d.placeIndices().stream().filter(i -> i != GWANGALLI_BEACH).toList(),
                        d.accommodationIndex(), d.departureHubIndex()))
                .toList();
        SelectionOutput withoutBeach = new SelectionOutput(base.concept(), days, base.pairs(), base.spareIndices(),
                List.of(), List.of(), List.of());

        RequiredExperiences.Match match = RequiredExperiences.attach(
                List.of(new RequiredExperience("광안리 야경", "광안리", "evening")), trace.candidates());
        SelectionOutput selection = new IndexResultMapper().injectRequiredPlaces(withoutBeach, match.candidates());
        List<StepData> steps = new RouteOptimizer().schedule(selection, match.candidates(), trace.args()).steps();

        StepData beach = steps.stream().filter(s -> "광안리해수욕장".equals(s.place().name())).findFirst().orElse(null);
        assertThat(beach).isNotNull();
        assertThat(beach.startTime()).isGreaterThanOrEqualTo("17:30");
        assertThat(new ItineraryRules().evaluate(steps, match.candidates(), new ItineraryRules.Context(
                trace.args().pace(), trace.args().transportPref(), trace.args().startDate(), trace.dayCount(),
                RouteOptimizer.departureHubDeadline(trace.args()), RequiredExperiences.places(match.candidates())))
                .violations()).noneMatch(v -> v.rule() == Rule.REQUIRED_EXPERIENCE_MISSING);
    }
}
