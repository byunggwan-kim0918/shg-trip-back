package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Severity;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Verdict;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 실생성 사례 91의 결함(같은 날 같은 종류 끼니·카페 편중·긴 이동·다른 도시 주소)을 판정 함수가 잡는지 본다. */
class Itinerary91RulesTest {

    private final TraceFile trace = Itinerary91Fixture.load();
    private final ItineraryRules rules = new ItineraryRules();

    @Test
    @DisplayName("저장된 91: 같은 날 같은 종류 끼니·해산물 반복·카페만 도는 날·136km·다른 도시 주소를 모두 잡는다")
    void savedItineraryViolations() {
        Verdict verdict = evaluate();

        assertThat(days(verdict, Rule.SAME_DAY_MEAL_KIND)).containsExactly(2, 3, 4, 5);
        assertThat(messages(verdict, Rule.MEAL_KIND_REPEATED)).containsExactly("같은 종류 seafood 10끼(상한 3)");
        assertThat(days(verdict, Rule.CAFE_PER_DAY)).containsExactly(3, 4);
        assertThat(messages(verdict, Rule.DAY_DISTANCE_OVER_CAP)).containsExactly("day5 하루 이동 136km(상한 90km)");
        assertThat(messages(verdict, Rule.ADDRESS_REGION_MISMATCH))
                .containsExactly("day4 주소와 지역 불일치(타타스베이글)");
        assertThat(verdict.has(Severity.HARD)).isFalse();
        assertThat(verdict.unknownKindMeals()).isZero();
    }

    private Verdict evaluate() {
        return rules.evaluate(trace.savedSteps(), trace.candidates(), new ItineraryRules.Context(
                trace.args().pace(), trace.args().transportPref(), trace.args().startDate(), trace.dayCount(),
                RouteOptimizer.departureHubDeadline(trace.args())));
    }

    private static java.util.List<Integer> days(Verdict verdict, Rule rule) {
        return verdict.violations().stream().filter(v -> v.rule() == rule).map(Violation::day).toList();
    }

    private static java.util.List<String> messages(Verdict verdict, Rule rule) {
        return verdict.violations().stream().filter(v -> v.rule() == rule).map(Violation::message).toList();
    }
}
