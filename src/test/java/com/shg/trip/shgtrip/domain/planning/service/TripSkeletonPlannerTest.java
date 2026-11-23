package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripSkeletonPlannerTest {

    @Test
    @DisplayName("초안이 어느 날에도 두지 않은 요구는 그 도시의 온전한 날에 넣는다")
    void missingWantIsPlaced() {
        DraftPlan.Want food = new DraftPlan.Want("w1", "food", "피순대 먹기", null, "피순대", null, List.of());
        DraftPlan.Want place = new DraftPlan.Want("w2", "place", "오목대", "오목대", null, null, List.of());
        DraftPlan.Item placed = new DraftPlan.Item("morning", "sight", "오목대", "한옥마을", true, "w2", null, null);
        DraftPlan draft = new DraftPlan("전주", List.of(food, place), List.of(
                new DraftPlan.Day(1, "전주 한옥마을", "한옥마을", List.of(placed), List.of()),
                new DraftPlan.Day(2, "전주 남부시장", null, List.of(), List.of())));
        List<String> notes = new ArrayList<>();

        DraftPlan out = TripSkeletonPlanner.withAllWants(draft, 2, List.of("전주"), notes);

        List<DraftPlan.Item> all = out.days().stream().flatMap(d -> d.items().stream()).toList();
        assertThat(all).filteredOn(i -> "w1".equals(i.want())).singleElement()
                .satisfies(i -> {
                    assertThat(i.kind()).isEqualTo("meal");
                    assertThat(i.name()).isEqualTo("피순대");
                });
        assertThat(all).filteredOn(i -> "w2".equals(i.want())).hasSize(1);
        assertThat(notes).hasSize(1);
    }

    @Test
    @DisplayName("초안이 빠뜨린 꼭 할 경험은 사용자가 정한 시각의 요구로 넣는다")
    void userWantMissingFromDraftIsAdded() {
        DraftPlan draft = new DraftPlan("부산", List.of(), List.of(new DraftPlan.Day(1, "해운대", null, List.of(), List.of())));
        Map<String, PlaceCandidate> pinned = new HashMap<>();
        List<String> notes = new ArrayList<>();

        Map<String, DraftItineraryPlanner.UserWant> userById = new HashMap<>();
        DraftItineraryPlanner.UserWant asked = new DraftItineraryPlanner.UserWant("광안리 야경", "광안리", "evening", null);

        DraftPlan out = TripSkeletonPlanner.withUserWants(draft, List.of(asked), pinned, userById, notes);

        assertThat(out.wants()).singleElement().satisfies(w -> {
            assertThat(w.id()).isEqualTo("user1");
            assertThat(w.kind()).isEqualTo("activity");
            assertThat(w.time()).isEqualTo("evening");
            assertThat(w.satisfy()).containsExactly("광안리");
        });
        assertThat(pinned).isEmpty();
        assertThat(userById).containsEntry("user1", asked);
        assertThat(notes).hasSize(1);
    }

    @Test
    @DisplayName("초안이 같은 요구를 적었으면 새로 넣지 않고 고른 장소와 사용자가 정한 시각을 붙인다")
    void userWantMatchingDraftIsPinned() {
        DraftPlan.Want w = new DraftPlan.Want("w1", "place", "해동용궁사 가보기", "해동용궁사", null, null, List.of());
        DraftPlan draft = new DraftPlan("부산", List.of(w), List.of());
        PlaceCandidate temple = new PlaceCandidate(0, 5L, "해동용궁사", "부산", "Spiritual Center", List.of(), "Busan",
                "KR", new BigDecimal("35.18"), new BigDecimal("129.22"), null, null, 1.0, null, null, null, null, false,
                null, null, null, null, "SIGHT", null, false, null);
        Map<String, PlaceCandidate> pinned = new HashMap<>();

        Map<String, DraftItineraryPlanner.UserWant> userById = new HashMap<>();

        DraftPlan out = TripSkeletonPlanner.withUserWants(draft,
                List.of(new DraftItineraryPlanner.UserWant("해동용궁사", "해동용궁사", "sunrise", temple)), pinned,
                userById, new ArrayList<>());

        assertThat(out.wants()).singleElement().satisfies(x -> {
            assertThat(x.id()).isEqualTo("w1");
            assertThat(x.time()).isEqualTo("sunrise");
        });
        assertThat(pinned).containsEntry("w1", temple);
        assertThat(userById).containsKey("w1");
    }

    @Test
    @DisplayName("도시 배분 요구에서 도시별 날 수를 읽는다")
    void readsCityDays() {
        assertThat(TripSkeletonPlanner.cityDaysOf("서울 1일, 전주 3일", List.of("서울", "전주")))
                .isEqualTo(Map.of("서울", 1, "전주", 3));
        assertThat(TripSkeletonPlanner.cityDaysOf("부산과 경주를 둘러보고 싶어요", List.of("부산", "경주"))).isEmpty();
    }

    @Test
    @DisplayName("출발 시각 없는 마지막 날에 둔 저녁 요구는 저녁이 있는 앞날로 옮긴다")
    void eveningWantLeavesDayWithoutEvening() {
        DraftPlan.Want night = new DraftPlan.Want("w1", "place", "반포한강공원 야경", "반포한강공원", null, "evening",
                List.of("반포한강공원"));
        DraftPlan.Item item = new DraftPlan.Item("evening", "night", "반포한강공원", "반포", true, "w1", null, null);
        DraftPlan draft = new DraftPlan("서울", List.of(night), List.of(
                new DraftPlan.Day(1, "종로", "종로", List.of(), List.of()),
                new DraftPlan.Day(2, "반포", null, List.of(item), List.of())));
        List<String> notes = new ArrayList<>();

        DraftPlan out = TripSkeletonPlanner.withTimedWantsOnFeasibleDays(draft,
                TripPlannerTest.request(java.time.LocalTime.of(13, 0), null), 2, notes, new ArrayList<>(),
                new java.util.HashSet<>());

        assertThat(out.days().get(0).items()).singleElement().satisfies(i -> {
            assertThat(i.want()).isEqualTo("w1");
            assertThat(i.slot()).isEqualTo("evening");
        });
        assertThat(out.days().get(1).items()).isEmpty();
        assertThat(notes).hasSize(1);
    }

    @Test
    @DisplayName("첫날 도착이 일출 뒤면 첫날에 둔 일출 요구를 다음 날로 옮기고, 하루 여행이면 넣을 날이 없다고 알린다")
    void sunriseWantLeavesLateArrivalDay() {
        DraftPlan.Want sunrise = new DraftPlan.Want("w1", "place", "해운대 일출", "해운대해수욕장", null, "sunrise",
                List.of("해운대해수욕장"));
        DraftPlan.Item item = new DraftPlan.Item("morning", "sight", "해운대해수욕장", "해운대", true, "w1", null, null);
        DraftPlan twoDays = new DraftPlan("부산", List.of(sunrise), List.of(
                new DraftPlan.Day(1, "해운대", "해운대", List.of(item), List.of()),
                new DraftPlan.Day(2, "해운대", null, List.of(), List.of())));
        java.util.Set<String> infeasible = new java.util.HashSet<>();

        DraftPlan moved = TripSkeletonPlanner.withTimedWantsOnFeasibleDays(twoDays,
                TripPlannerTest.request(java.time.LocalTime.of(13, 0), null), 2, new ArrayList<>(), new ArrayList<>(),
                infeasible);

        assertThat(moved.days().get(0).items()).isEmpty();
        assertThat(moved.days().get(1).items()).singleElement().satisfies(i -> assertThat(i.slot()).isEqualTo("morning"));
        assertThat(infeasible).isEmpty();

        DraftPlan oneDay = new DraftPlan("부산", List.of(sunrise), List.of(
                new DraftPlan.Day(1, "해운대", null, List.of(item), List.of())));
        List<String> conflicts = new ArrayList<>();
        TripSkeletonPlanner.withTimedWantsOnFeasibleDays(oneDay,
                TripPlannerTest.request(java.time.LocalTime.of(13, 0), null), 1, new ArrayList<>(), conflicts,
                infeasible);

        assertThat(infeasible).containsExactly("w1");
        assertThat(conflicts).singleElement().asString().contains("일출 전에 닿을 수 있는 날이 없어");
    }
}
