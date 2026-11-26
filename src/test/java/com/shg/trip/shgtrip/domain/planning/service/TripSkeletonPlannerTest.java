package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TripSkeletonPlannerTest {

    private static final com.shg.trip.shgtrip.domain.place.destination.Destinations REGISTRY =
            com.shg.trip.shgtrip.domain.place.destination.Destinations.fromClasspath();

    @Test
    @DisplayName("홋카이도: 묵기로 한 도시를 밤마다 숙소 지역으로 두고, 마지막 날은 숙소를 두지 않는다")
    void plannedStaysOverrideDraftLodging() {
        var sapporo = REGISTRY.findByName("삿포로").orElseThrow();
        var furano = REGISTRY.findByName("후라노").orElseThrow();
        DraftPlan draft = new DraftPlan("홋카이도", List.of(), List.of(
                new DraftPlan.Day(1, "삿포로", "삿포로", List.of(), List.of()),
                new DraftPlan.Day(2, "후라노", "후라노", List.of(), List.of()),
                new DraftPlan.Day(3, "비에이", "후라노", List.of(), List.of()),
                new DraftPlan.Day(4, "오타루", "오타루", List.of(), List.of()),
                new DraftPlan.Day(5, "삿포로", null, List.of(), List.of())));

        DraftPlan out = TripSkeletonPlanner.withPlannedStays(draft,
                Map.of(1, sapporo, 2, sapporo, 3, furano, 4, sapporo), 5);

        assertThat(out.days()).extracting(DraftPlan.Day::lodgingArea)
                .containsExactly("삿포로", "삿포로", "후라노", "삿포로", null);
        assertThat(out.days()).extracting(DraftPlan.Day::area)
                .containsExactly("삿포로", "후라노", "비에이", "오타루", "삿포로");
    }

    @Test
    @DisplayName("빠진 지역을 넣으려고 바꿀 날은 다른 들를 지역을 그날만 들르는 날을 고르지 않는다")
    void areaDayKeepsOtherAreasOnlyDay() {
        var sapporo = REGISTRY.findByName("삿포로").orElseThrow();
        var furano = REGISTRY.findByName("후라노").orElseThrow();
        var biei = REGISTRY.findByName("비에이").orElseThrow();
        PlaceCandidate clock = new PlaceCandidate(1, 1L, "시계탑", null, "Landmarks", List.of(), "Sapporo", "JP",
                java.math.BigDecimal.valueOf(43.0625), java.math.BigDecimal.valueOf(141.3536), null, null, 0.0, null, null,
                null, null, false, null, null, null, null, "SIGHT", null, false, null, null, null, null);
        PlaceCandidate farm = new PlaceCandidate(2, 2L, "팜 도미타", null, "Garden", List.of(), "Furano", "JP",
                java.math.BigDecimal.valueOf(43.4185), java.math.BigDecimal.valueOf(142.4266), null, null, 0.0, null, null,
                null, null, false, null, null, null, null, "SIGHT", null, false, null, null, null, null);
        List<List<DayBuilder.Stop>> stops = List.of(new ArrayList<>(List.of(new DayBuilder.Stop(clock, "sight", null, List.of()))),
                new ArrayList<>(List.of(new DayBuilder.Stop(farm, "sight", null, List.of()))));
        List<com.shg.trip.shgtrip.domain.place.destination.Destination> cityByDay = List.of(sapporo, furano);

        java.util.Set<Integer> keep = DraftItineraryPlanner.onlyDaysOfOtherAreas(stops, List.of(sapporo, furano, biei), biei);

        assertThat(keep).containsExactlyInAnyOrder(0, 1);
        assertThat(DraftItineraryPlanner.dayForArea(stops, cityByDay, furano, keep, false)).isNull();
        assertThat(DraftItineraryPlanner.dayForArea(stops, cityByDay, furano, java.util.Set.of(), false)).isEqualTo(1);
    }

    @Test
    @DisplayName("초안 프롬프트에 들를 지역·숙박 순서·지역별 이동을 적고, 구간 수단은 출발지가 든 지역을 따른다")
    void layoutLinesInPrompt() {
        var sapporo = REGISTRY.findByName("삿포로").orElseThrow();
        var otaru = REGISTRY.findByName("오타루").orElseThrow();
        var furano = REGISTRY.findByName("후라노").orElseThrow();
        Map<Integer, com.shg.trip.shgtrip.domain.place.destination.Destination> nights = new java.util.LinkedHashMap<>();
        nights.put(1, sapporo);
        nights.put(2, sapporo);
        nights.put(3, furano);
        nights.put(4, sapporo);
        Map<com.shg.trip.shgtrip.domain.place.destination.Destination, String> modes = new java.util.LinkedHashMap<>();
        modes.put(sapporo, "transit");
        modes.put(furano, "car");
        StringBuilder sb = new StringBuilder();

        DraftItineraryPlanner.layoutLines(new DraftItineraryPlanner.Layout(List.of(sapporo, otaru, furano), nights,
                modes), sb);

        assertThat(sb.toString()).contains("들를 지역: 삿포로, 오타루, 후라노\n")
                .contains("숙박: 삿포로 2박 → 후라노 1박 → 삿포로 1박\n")
                .contains("지역별 이동: 삿포로 대중교통, 후라노 자동차\n");
        var biei = REGISTRY.findByName("비에이").orElseThrow();
        var layout = new DraftItineraryPlanner.Layout(List.of(), Map.of(), Map.of(sapporo, "any", biei, "car"));
        assertThat(layout.modeAt(new double[]{43.493, 142.614}, "any")).as("아오이이케").isEqualTo("car");
        assertThat(layout.modeAt(new double[]{43.0687, 141.3508}, "car")).as("삿포로역").isEqualTo("any");
        assertThat(layout.modeAt(new double[]{35.68, 139.76}, "walk")).as("말한 지역 밖").isEqualTo("walk");
        StringBuilder none = new StringBuilder();
        DraftItineraryPlanner.layoutLines(DraftItineraryPlanner.Layout.NONE, none);
        assertThat(none).isEmpty();
    }

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

    @Test
    @DisplayName("초안이 날에 두지 않은 요구는 문장에 도시 이름이 없어도 장소와 가까운 권역의 날에 넣는다")
    void missingWantGoesToNearestAreaDay() {
        var sapporo = REGISTRY.findByName("삿포로").orElseThrow();
        var furano = REGISTRY.findByName("후라노").orElseThrow();
        var biei = REGISTRY.findByName("비에이").orElseThrow();
        DraftPlan.Want farm = new DraftPlan.Want("w1", "place", "팜 토미타", "팜 토미타", null, null, List.of());
        DraftPlan.Want lake = new DraftPlan.Want("w2", "place", "아오이이케", "아오이이케", null, null, List.of());
        DraftPlan.Item bieiLunch = new DraftPlan.Item("lunch", "meal", "비에이 야채 요리", "비에이", false, null, null, null);
        DraftPlan draft = new DraftPlan("홋카이도", List.of(farm, lake), List.of(
                new DraftPlan.Day(1, "삿포로", null, List.of(), List.of()),
                new DraftPlan.Day(2, "삿포로", null, List.of(), List.of()),
                new DraftPlan.Day(3, "후라노", null, List.of(), List.of()),
                new DraftPlan.Day(4, "삿포로", null, List.of(bieiLunch), List.of()),
                new DraftPlan.Day(5, "삿포로", null, List.of(), List.of())));

        DraftPlan out = TripSkeletonPlanner.withAllWants(draft, 5, List.of("삿포로", "후라노"), new ArrayList<>(),
                Map.of("w1", new double[]{43.4185, 142.4266}, "w2", new double[]{43.493, 142.614}),
                List.of(sapporo, furano, biei));

        assertThat(out.days().get(2).items()).extracting(DraftPlan.Item::want).containsExactly("w1");
        assertThat(out.days().get(3).items()).as("권역은 삿포로지만 항목이 비에이인 날").extracting(DraftPlan.Item::want)
                .contains("w2");
    }

    @Test
    @DisplayName("요구 문장에 여행 도시 이름이 있으면 이름으로 찾은 좌표가 다른 도시를 가리켜도 그 도시의 날에 넣는다")
    void cityNamedWantKeepsCityDay() {
        var busan = com.shg.trip.shgtrip.domain.place.destination.DomesticCities.find("부산").orElseThrow();
        var gyeongju = com.shg.trip.shgtrip.domain.place.destination.DomesticCities.find("경주").orElseThrow();
        DraftPlan.Want want = new DraftPlan.Want("w1", "place", "부산 시장 구경", "시장", null, null, List.of());
        DraftPlan draft = new DraftPlan("부산·경주", List.of(want), List.of(
                new DraftPlan.Day(1, "부산", null, List.of(), List.of()),
                new DraftPlan.Day(2, "부산", null, List.of(), List.of()),
                new DraftPlan.Day(3, "경주", null, List.of(), List.of()),
                new DraftPlan.Day(4, "경주", null, List.of(), List.of())));

        DraftPlan out = TripSkeletonPlanner.withAllWants(draft, 4, List.of("부산", "경주"), new ArrayList<>(),
                Map.of("w1", new double[]{gyeongju.lat(), gyeongju.lng()}), List.of(busan, gyeongju));

        assertThat(out.days().get(1).items()).extracting(DraftPlan.Item::want).containsExactly("w1");
    }

    @Test
    @DisplayName("숙소 자리 조건은 묵기로 한 도시 안에서 지킬 수 있으면 남기고, 그 도시 어디에서도 못 지키면 버린다")
    void lodgingNeedKeptWhenCompatibleWithPlannedCity() {
        var biei = REGISTRY.findByName("비에이").orElseThrow();
        var sapporo = REGISTRY.findByName("삿포로").orElseThrow();
        var need = new DraftItineraryPlanner.LodgingNeed(new double[]{43.493, 142.614}, 25, true, "청의 호수 일출");
        List<String> notes = new ArrayList<>();

        assertThat(DraftItineraryPlanner.withinPlannedCities(Map.of(1, need), Map.of(1, biei), notes)).containsKey(1);
        assertThat(DraftItineraryPlanner.withinPlannedCities(Map.of(1, need), Map.of(1, sapporo), notes)).isEmpty();
        assertThat(notes).singleElement().asString().contains("삿포로");
    }

    @Test
    @DisplayName("일출과 숙박 계획이 부딪히면 한쪽을 버리지 않고, 묵기로 한 도시에서 닿는지 보고 못 닿으면 조정을 알린다")
    void sunriseAgainstPlannedStay() {
        var sapporo = REGISTRY.findByName("삿포로").orElseThrow();
        var biei = REGISTRY.findByName("비에이").orElseThrow();
        double[] aoiike = {43.493, 142.614};
        DraftPlan.Want sunrise = new DraftPlan.Want("w1", "place", "청의 호수 일출", "청의 호수", null, "sunrise",
                List.of("청의 호수"));
        DraftPlan.Item item = new DraftPlan.Item("morning", "sight", "청의 호수", "비에이", true, "w1", null, null);
        DraftPlan draft = new DraftPlan("홋카이도", List.of(sunrise), List.of(
                new DraftPlan.Day(1, "삿포로", null, List.of(), List.of()),
                new DraftPlan.Day(2, "비에이", null, List.of(item), List.of())));

        List<String> conflicts = new ArrayList<>();
        java.util.Set<String> infeasible = new java.util.HashSet<>();
        TripSkeletonPlanner.withTimedWantsOnFeasibleDays(draft,
                hokkaido(new DraftItineraryPlanner.Layout(List.of(), Map.of(1, sapporo), Map.of())), 2,
                new ArrayList<>(), conflicts, infeasible, Map.of("w1", aoiike));
        assertThat(infeasible).containsExactly("w1");
        assertThat(conflicts).singleElement().asString().contains("묵기로 한 도시", "숙박 계획을 바꾸거나");

        List<String> none = new ArrayList<>();
        DraftPlan kept = TripSkeletonPlanner.withTimedWantsOnFeasibleDays(draft,
                hokkaido(new DraftItineraryPlanner.Layout(List.of(), Map.of(1, biei), Map.of(biei, "car"))), 2,
                new ArrayList<>(), none, new java.util.HashSet<>(), Map.of("w1", aoiike));
        assertThat(none).as("비에이에 묵고 차로 다니면 일출 전에 닿는다").isEmpty();
        DraftPlan.Item free = new DraftPlan.Item("lunch", "meal", "스프카레", "삿포로", false, null, null, null);
        DraftPlan withFreeItem = new DraftPlan("홋카이도", List.of(sunrise), List.of(
                new DraftPlan.Day(1, "삿포로", null, List.of(free), List.of()),
                new DraftPlan.Day(2, "비에이", null, List.of(item), List.of())));
        assertThat(TripSkeletonPlanner.withTimedWantsOnFeasibleDays(withFreeItem, hokkaido(DraftItineraryPlanner.Layout.NONE),
                2, new ArrayList<>(), new ArrayList<>(), new java.util.HashSet<>(), Map.of()).days())
                .as("요구가 아닌 항목이 있어도 좌표 표를 찾다 멈추지 않는다").hasSize(2);
        assertThat(kept.days().get(1).items()).singleElement().extracting(DraftPlan.Item::want).isEqualTo("w1");
    }

    private static DraftItineraryPlanner.Request hokkaido(DraftItineraryPlanner.Layout layout) {
        VectorEnrichedInput input = new VectorEnrichedInput("홋카이도", List.of("nature"), List.of("attraction"), "normal",
                "any", null, java.time.LocalDate.of(2027, 7, 15), java.time.LocalDate.of(2027, 7, 16), "홋카이도",
                List.of(), "홋카이도", "JP", List.of("Sapporo", "Biei"), List.of(), Map.of(), null, null, null, null,
                Map.of(), VectorEnrichedInput.CompanionType.COUPLE, 2, List.of());
        return new DraftItineraryPlanner.Request(input, null, List.of(REGISTRY.findByName("삿포로").orElseThrow()), null,
                null, null, null, List.of(), List.of(), Map.of(), null, null, Map.of(), java.util.Set.of(), List.of(),
                layout);
    }

    @Test
    @DisplayName("현지어 이름은 일부만 맞는 다른 장소로 요구를 채웠다고 보지 않는다")
    void localNameIsNotLooselyMatchedForWant() {
        DraftPlaceResolver resolver = mock(DraftPlaceResolver.class);
        Place izakaya = Place.builder().id(7L).name("居酒屋白川郷").address("").category("Dining and Drinking > Bar")
                .region("Takayama").latitude(BigDecimal.valueOf(36.14)).longitude(BigDecimal.valueOf(137.25)).build();
        given(resolver.resolveNamed(anyString(), anyList(), anyString(), any(), any(), any()))
                .willReturn(new DraftPlaceResolver.Resolution(izakaya, "ok", PlaceNames.Match.FUZZY));
        DraftPlan.Want want = new DraftPlan.Want("w1", "place", "시라카와고", "시라카와고", null, null,
                List.of("시라카와고"), List.of("白川郷"), List.of());
        DraftPlan draft = new DraftPlan("다카야마", List.of(want), List.of(new DraftPlan.Day(1, "다카야마", null,
                List.of(new DraftPlan.Item("morning", "sight", "시라카와고", "시라카와고", true, "w1", null, null)),
                List.of())));
        VectorEnrichedInput input = new VectorEnrichedInput("다카야마", List.of("culture"), List.of("attraction"),
                "normal", "any", new BigDecimal("500000"), LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 1),
                "다카야마 여행", List.of(), "다카야마", "JP", List.of("Takayama"), List.of(), Map.of(), null, null, null,
                null, Map.of(), VectorEnrichedInput.CompanionType.COUPLE, 2, List.of());
        DraftItineraryPlanner.Request base = new DraftItineraryPlanner.Request(input, draft,
                List.of(com.shg.trip.shgtrip.domain.place.destination.Destinations.fromClasspath().find("Takayama")
                        .orElseThrow()), null, null, null, null, List.of(), List.of(), Map.of(), null, null, Map.of(),
                Set.of(), List.of());

        TripSkeletonPlanner.Skeleton out = new TripSkeletonPlanner(resolver, mock(TripHubs.class)).build(base);

        assertThat(out.conditions().wants()).singleElement()
                .satisfies(w -> assertThat(w.reach()).isEqualTo(TripConditions.Reach.NOT_FOUND));
    }
}
