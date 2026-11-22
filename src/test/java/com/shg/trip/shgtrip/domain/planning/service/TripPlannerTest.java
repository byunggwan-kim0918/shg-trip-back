package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Rule;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TripPlannerTest {

    @Test
    @DisplayName("편집은 지킬 수 없는 위반이 있을 때만 부르고, 그때 못 찾은 장소 메모를 함께 넘긴다")
    void editProblemsAreHardOnly() {
        DraftItineraryPlanner.Result result = new DraftItineraryPlanner.Result(List.of(), List.of(),
                List.of("요청 장소 '감천문화마을' 찾지 못함", "1일차 뺌: 카페(하루 끝 시각 초과)"), List.of(), List.of(),
                Map.of());
        Violation soft = new Violation(Rule.DINNER_MISSING, 1, "day1 저녁 없음");
        Violation hard = new Violation(Rule.WANT_MISSING, 0, "요청한 '감천문화마을'이(가) 없음", List.of("감천문화마을"),
                "'감천문화마을'을(를) 넣을 날짜와 시간대를 정한다");

        assertThat(TripPlanner.editProblems(built(result, List.of(soft)))).isEmpty();
        assertThat(TripPlanner.editProblems(built(result, List.of(soft, hard)))).containsExactly(
                "요청한 '감천문화마을'이(가) 없음 (고칠 방향: '감천문화마을'을(를) 넣을 날짜와 시간대를 정한다)",
                "요청 장소 '감천문화마을' 찾지 못함");
    }

    @Test
    @DisplayName("출발 허브 기한은 짓기 단계와 같은 허브 여유로 정하고, 허브로 끝나지 않으면 두지 않는다")
    void ruleContextHubDeadline() {
        PlaceCandidate station = new PlaceCandidate(2, 12L, "부산역", null, "Travel and Transportation > Rail Station",
                List.of(), "Busan", "KR", BigDecimal.valueOf(35.11), BigDecimal.valueOf(129.04), null, null, 0.0, null,
                null, null, null, false, null, null, null, null, "TRANSIT", null, false, null, null, null, null);
        StepData toStation = new StepData(1, 2, "14:00", "14:30",
                new PlaceData("부산역", null, station.category(), "Busan", "KR", null, null, 12L, null), List.of(),
                null, null, null, null, null, null);
        TripSkeletonPlanner.Skeleton skeleton = skeleton(LocalTime.of(15, 0));

        ItineraryRules.Context toHub = TripPlanner.ruleContext(skeleton, result(List.of(toStation), station));
        ItineraryRules.Context noHub = TripPlanner.ruleContext(skeleton, result(List.of(), station));

        // 15:00 출발, 역은 20분 전까지 닿는다
        assertThat(toHub.lastDayHubDeadline()).isEqualTo(14 * 60 + 40);
        assertThat(toHub.conditions()).isSameAs(skeleton.conditions());
        assertThat(toHub.cities()).extracting(ItineraryRules.City::nameKo).containsExactly("부산");
        assertThat(noHub.lastDayHubDeadline()).isNull();
    }

    private static DraftItineraryPlanner.Result result(List<StepData> steps, PlaceCandidate candidate) {
        return new DraftItineraryPlanner.Result(steps, candidate == null ? List.of() : List.of(candidate), List.of(),
                List.of(), List.of(), Map.of());
    }

    private static TripPlanner.Built built(DraftItineraryPlanner.Result result, List<Violation> violations) {
        return new TripPlanner.Built(null, result, null, Map.of(), violations, Map.of(), List.of());
    }

    @Test
    @DisplayName("편집한 일정은 지킬 수 없는 위반이 줄고 채운 요구가 줄지 않을 때만 쓴다")
    void editedItineraryOnlyWhenBetter() {
        DraftItineraryPlanner.Result result = result(List.of(), null);
        Violation closed = new Violation(Rule.CLOSED_DAY, 1, "휴무일");
        Violation missing = new Violation(Rule.WANT_MISSING, 0, "요청한 '반포'이(가) 없음", List.of("반포"));
        TripPlanner.Built first = built(result, List.of(closed));

        assertThat(TripPlanner.better(first, built(result, List.of()))).isNotSameAs(first);
        assertThat(TripPlanner.better(first, built(result, List.of(missing)))).isSameAs(first);
        assertThat(TripPlanner.better(first, built(result, List.of(closed)))).isSameAs(first);
    }

    static DraftItineraryPlanner.Request request(LocalTime arrival, LocalTime departure) {
        VectorEnrichedInput input = new VectorEnrichedInput("서울", List.of("food"), List.of("restaurant"), "normal",
                "any", new BigDecimal("500000"), LocalDate.of(2027, 2, 26), LocalDate.of(2027, 2, 27), "서울 여행",
                List.of(), "서울", "KR", List.of("Seoul"), List.of("맛집"), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.COUPLE, 2, List.of());
        return new DraftItineraryPlanner.Request(input, null, List.of(DomesticCities.find("서울").orElseThrow()),
                arrival, departure, null, null, List.of(), List.of(), Map.of(), null, null, Map.of(), Set.of(), List.of());
    }

    private static TripSkeletonPlanner.Skeleton skeleton(LocalTime departure) {
        VectorEnrichedInput input = new VectorEnrichedInput("부산", List.of("food"), List.of("restaurant"), "normal",
                "any", new BigDecimal("500000"), LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 2), "부산 여행",
                List.of(), "부산", "KR", List.of("Busan"), List.of("맛집"), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.COUPLE, 2, List.of());
        DraftItineraryPlanner.Request base = new DraftItineraryPlanner.Request(input, null,
                List.of(DomesticCities.find("부산").orElseThrow()), null, departure, null, null, List.of(), List.of(),
                Map.of(), null, null, Map.of(), Set.of(), List.of());
        return new TripSkeletonPlanner.Skeleton(base, List.of(), List.of(),
                new TripConditions(List.of(), Map.of(), List.of(), false, "any", null, departure, "normal"));
    }
}
