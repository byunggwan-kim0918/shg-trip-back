package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DayBuilderTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2027, 5, 19);
    private static final LocalDate WINTER_WEDNESDAY = LocalDate.of(2027, 1, 13);
    private static long nextId = 1;

    private static PlaceCandidate place(String name, String category, String role, double lat, double lng,
                                        String hours) {
        return new PlaceCandidate(1, nextId++, name, null, category, List.of(), "Busan", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.0, null, hours, null, null, false,
                null, null, null, null, role, null, false, null, null, null, null);
    }

    private static PlaceCandidate sight(String name, double lat, double lng) {
        return place(name, "Arts and Entertainment > Museum", "SIGHT", lat, lng, null);
    }

    private static PlaceCandidate restaurant(String name, double lat, double lng) {
        return place(name, "Dining and Drinking > Restaurant > Korean Restaurant", "MEAL", lat, lng, null);
    }

    private static DayBuilder.Frame frame(LocalDate date, int start, int end, boolean hardEnd,
                                          List<DayBuilder.Stop> spares, List<PlaceCandidate> earlier) {
        return new DayBuilder.Frame(1, date, start, end, 22 * 60, 19 * 60 + 30, 5 * 60 + 15, null, null, null,
                false, hardEnd, null, false, "walk", spares, earlier, Integer.MAX_VALUE, 1.0, null, "KR", 0);
    }

    private static DayBuilder.Stop stop(PlaceCandidate p, String kind, String slot) {
        return new DayBuilder.Stop(p, kind, slot, List.of());
    }

    private static List<String> names(DayBuilder.Built built) {
        return built.steps().stream().map(s -> s.place().name()).toList();
    }

    @Test
    @DisplayName("점심은 11시 전에 시작하지 않고 저녁은 17시 30분 전에 시작하지 않는다")
    void mealsWaitForMealtime() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate lunch = restaurant("쌍둥이돼지국밥", 35.1295, 129.0935);
        PlaceCandidate dinner = restaurant("할매재첩국", 35.1300, 129.0940);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 9 * 60, 21 * 60, false, List.of(), List.of()),
                List.of(stop(museum, "sight", "morning"), stop(lunch, "meal", "lunch"), stop(dinner, "meal", "dinner")));

        StepData lunchStep = built.steps().get(1);
        StepData dinnerStep = built.steps().get(2);
        assertThat(lunchStep.startTime()).isGreaterThanOrEqualTo("11:00");
        assertThat(dinnerStep.startTime()).isGreaterThanOrEqualTo("17:30");
    }

    @Test
    @DisplayName("그날 쉬는 곳은 빼고 예비 장소로 바꾼다")
    void closedPlaceIsReplacedBySpare() {
        PlaceCandidate closed = place("수요휴관관", "Arts and Entertainment > Museum", "SIGHT", 35.13, 129.09,
                "수요일: 휴무일");
        PlaceCandidate spare = sight("국립해양박물관", 35.0785, 129.0800);

        DayBuilder.Built built = DayBuilder.build(
                frame(WEDNESDAY, 9 * 60, 21 * 60, false, List.of(stop(spare, "sight", "morning")), List.of()),
                List.of(stop(closed, "sight", "morning")));

        assertThat(names(built)).containsExactly("국립해양박물관");
        assertThat(built.dropped()).anyMatch(d -> d.contains("수요휴관관"));
    }

    @Test
    @DisplayName("출발일에는 끼니도 마감 시각을 넘기지 않는다")
    void hardEndDropsLateMeal() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate lunch = restaurant("쌍둥이돼지국밥", 35.1295, 129.0935);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 9 * 60 + 30, 11 * 60 + 30, true, List.of(),
                List.of()), List.of(stop(museum, "sight", "morning"), stop(lunch, "meal", "lunch")));

        assertThat(names(built)).doesNotContain("쌍둥이돼지국밥");
    }

    @Test
    @DisplayName("끼니 칸 순서로 돌면 그날 끝을 넘기는 요청 장소는 끼니보다 먼저 돌아 살린다")
    void protectedStopGoesBeforeMealWhenDayIsShort() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate lunch = restaurant("쌍둥이돼지국밥", 35.1295, 129.0935);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 9 * 60 + 30, 13 * 60, true, List.of(), List.of()),
                List.of(stop(lunch, "meal", "lunch"),
                        new DayBuilder.Stop(museum, "sight", "afternoon", List.of(), true)));

        assertThat(names(built)).containsExactly("부산박물관", "쌍둥이돼지국밥");
    }

    @Test
    @DisplayName("해 진 뒤에는 낮 야외 장소를 시작하지 않는다")
    void daytimeOutdoorNotAfterDark() {
        PlaceCandidate beach = place("송정해수욕장", "Landmarks and Outdoors > Beach", "SIGHT", 35.1786, 129.1999,
                null);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 20 * 60, 22 * 60, false, List.of(), List.of()),
                List.of(stop(beach, "sight", "afternoon")));

        assertThat(names(built)).doesNotContain("송정해수욕장");
    }

    @Test
    @DisplayName("앞선 날에 간 곳과 이름만 다른 같은 장소는 다시 넣지 않는다")
    void sameSpotUnderAnotherNameIsSkipped() {
        PlaceCandidate first = place("첨성대", "Landmarks and Outdoors > Historic and Protected Site", "SIGHT",
                35.8347, 129.2190, null);
        PlaceCandidate again = place("경주 첨성대", "Landmarks and Outdoors > Historic and Protected Site", "SIGHT",
                35.8348, 129.2191, null);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 10 * 60, 21 * 60, false, List.of(),
                List.of(first)), List.of(stop(again, "sight", "morning")));

        assertThat(names(built)).isEmpty();
    }

    @Test
    @DisplayName("영업시간을 모르는 박물관은 겨울에 16시 이후 입장하지 않는다")
    void museumWithoutHoursClosesEarlyInWinter() {
        PlaceCandidate museum = sight("국립경주박물관", 35.8292, 129.2280);

        DayBuilder.Built built = DayBuilder.build(frame(WINTER_WEDNESDAY, 16 * 60 + 10, 21 * 60, false, List.of(),
                List.of()), List.of(stop(museum, "sight", "afternoon")));

        assertThat(names(built)).isEmpty();
    }

    @Test
    @DisplayName("일출 장소는 해 뜨기 30분 전에 시작한다")
    void sunriseStartsBeforeSunrise() {
        PlaceCandidate peak = place("성산일출봉", "Landmarks and Outdoors > Mountain", "SIGHT", 33.4581, 126.9425,
                null);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 4 * 60 + 15, 21 * 60, false, List.of(), List.of()),
                List.of(stop(peak, "sunrise", "morning")));

        assertThat(built.steps().get(0).startTime()).isEqualTo("04:45");
    }

    @Test
    @DisplayName("도보 여행은 2km까지 걷고, 3km까지는 택시, 그보다 멀면 대중교통이다")
    void walkLegSwitchesToTransit() {
        PlaceCandidate a = sight("A", 35.1000, 129.0000);
        PlaceCandidate near = sight("B", 35.1100, 129.0000);
        PlaceCandidate far = sight("C", 35.1300, 129.0000);

        assertThat(DayBuilder.legOf(a, near, "walk", false).mode()).isEqualTo("WALK");
        assertThat(DayBuilder.legOf(a, far, "walk", false).mode()).isEqualTo("BUS");
        assertThat(DayBuilder.legOf(a, near, "walk", true).mode()).isEqualTo("TAXI");
    }

    @Test
    @DisplayName("빈 시간은 페이스가 정한 하루 관광 수까지만 예비 장소로 채운다")
    void gapFillStopsAtPaceLimit() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate lunch = restaurant("쌍둥이돼지국밥", 35.1295, 129.0935);
        PlaceCandidate dinner = restaurant("할매재첩국", 35.1300, 129.0940);
        List<DayBuilder.Stop> spares = List.of(stop(sight("유엔기념공원", 35.1330, 129.0970), "sight", "afternoon"),
                stop(sight("용호만", 35.1250, 129.0990), "sight", "afternoon"),
                stop(sight("이기대", 35.1210, 129.0930), "sight", "afternoon"));
        DayBuilder.Frame open = frame(WEDNESDAY, 9 * 60, 21 * 60, false, spares, List.of());
        DayBuilder.Frame capped = new DayBuilder.Frame(open.dayNumber(), open.date(), open.startMin(), open.endMin(),
                open.lodgingBy(), open.sunset(), open.sunrise(), null, null, null, false, false, null, false, "walk",
                spares, List.of(), 2, 1.0, null, "KR", 0);
        List<DayBuilder.Stop> stops = List.of(stop(museum, "sight", "morning"), stop(lunch, "meal", "lunch"),
                stop(dinner, "meal", "dinner"));

        long cappedSights = DayBuilder.build(capped, stops).stops().stream().filter(x -> !x.meal()).count();
        long openSights = DayBuilder.build(open, stops).stops().stream().filter(x -> !x.meal()).count();

        assertThat(cappedSights).isEqualTo(2);
        assertThat(openSights).isGreaterThan(2);
    }

    @Test
    @DisplayName("끼니 식당이 그날 쉬면 함께 골라 둔 대안 식당으로 바꾼다")
    void closedMealUsesAlternative() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate closed = place("수요휴무식당", "Dining and Drinking > Restaurant > Korean Restaurant", "MEAL",
                35.1295, 129.0935, "수요일: 휴무일");
        PlaceCandidate open = restaurant("쌍둥이돼지국밥", 35.1298, 129.0938);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 9 * 60, 21 * 60, false, List.of(), List.of()),
                List.of(stop(museum, "sight", "morning"),
                        new DayBuilder.Stop(closed, "meal", "lunch", List.of(open), true)));

        assertThat(names(built)).containsExactly("부산박물관", "쌍둥이돼지국밥");
    }

    @Test
    @DisplayName("앞날에 쓴 식당이 다시 끼니로 오면 대안 식당으로 바꾼다")
    void reusedMealUsesAlternative() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate yesterday = restaurant("할매재첩국", 35.1295, 129.0935);
        PlaceCandidate other = restaurant("쌍둥이돼지국밥", 35.1298, 129.0938);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 9 * 60, 21 * 60, false, List.of(), List.of(yesterday)),
                List.of(stop(museum, "sight", "morning"),
                        new DayBuilder.Stop(yesterday, "meal", "lunch", List.of(other), false)));

        assertThat(names(built)).containsExactly("부산박물관", "쌍둥이돼지국밥");
    }

    @Test
    @DisplayName("위치를 모르는 곳은 오가는 시간을 알 수 없어 넣지 않고 뺀 이유를 남긴다")
    void placeWithoutCoordsIsDropped() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate unknown = sight("어딘지 모를 전시관", 0, 0);
        PlaceCandidate lunch = restaurant("쌍둥이돼지국밥", 35.1295, 129.0935);

        DayBuilder.Built built = DayBuilder.build(frame(WEDNESDAY, 9 * 60, 21 * 60, false, List.of(), List.of()),
                List.of(stop(museum, "sight", "morning"), stop(unknown, "sight", "morning"),
                        stop(lunch, "meal", "lunch")));

        assertThat(names(built)).containsExactly("부산박물관", "쌍둥이돼지국밥");
        assertThat(built.dropped()).contains("어딘지 모를 전시관(위치 모름)");
        assertThat(built.steps().get(1).transportationDuration()).isNotNull();
    }

    @Test
    @DisplayName("하루 안에 오갈 거리가 아니면 이동을 만들지 않고, 위치를 모르는 곳은 같은 자리로 보지 않는다")
    void unreachableLegIsNotZeroMinutes() {
        PlaceCandidate seoul = sight("경복궁", 37.5796, 126.9770);
        PlaceCandidate busan = sight("해운대해수욕장", 35.1587, 129.1604);
        PlaceCandidate unknown = sight("어딘지 모를 전시관", 0, 0);

        assertThat(DayBuilder.legOf(seoul, busan, "car", false)).isNull();
        assertThat(DayBuilder.legOf(seoul, unknown, "car", false)).isNull();
        assertThat(DayBuilder.sameSpotAsAny(unknown, List.of(seoul))).isFalse();
    }

    @Test
    @DisplayName("배치안 감점은 끼니를 거른 안을 관광지 하나를 뺀 안보다 나쁘게 본다")
    void scorePrefersKeepingMeals() {
        PlaceCandidate museum = sight("부산박물관", 35.1290, 129.0930);
        PlaceCandidate lunch = restaurant("쌍둥이돼지국밥", 35.1295, 129.0935);
        DayBuilder.Frame f = frame(WEDNESDAY, 9 * 60, 15 * 60, true, List.of(), List.of());
        DayBuilder.Stop museumStop = stop(museum, "sight", "morning");
        DayBuilder.Stop lunchStop = stop(lunch, "meal", "lunch");
        StepData museumStep = new StepData(1, 1, "10:00", "11:30", new com.shg.trip.shgtrip.domain.planning.dto.PlaceData(
                museum.name(), null, museum.category(), "Busan", "KR", null, null, museum.placeId(), null), List.of(),
                null, null, null, null, null, null);
        StepData lunchStep = new StepData(2, 1, "12:00", "13:00", new com.shg.trip.shgtrip.domain.planning.dto.PlaceData(
                lunch.name(), null, lunch.category(), "Busan", "KR", null, null, lunch.placeId(), null), List.of(),
                "WALK", 5, null, null, null, null);
        List<DayBuilder.Stop> planned = List.of(museumStop, lunchStop);

        double withoutLunch = DayScore.penalty(f, planned,
                new DayBuilder.Built(List.of(museumStep), List.of(), List.of(museum), List.of(museumStop)));
        double withoutMuseum = DayScore.penalty(f, planned,
                new DayBuilder.Built(List.of(lunchStep), List.of(), List.of(lunch), List.of(lunchStop)));

        assertThat(withoutLunch).isGreaterThan(withoutMuseum);
    }

    private static final LocalDate KYOTO_DEPARTURE = LocalDate.of(2027, 1, 8);
    private static final PlaceCandidate KYOTO_HOTEL = kyoto("교토 호텔", "Travel and Transportation > Lodging > Hotel",
            "LODGING", 35.0037, 135.7728);
    private static final PlaceCandidate KANSAI_AIRPORT = kyoto("Kansai International Airport",
            "Travel and Transportation > Transport Hub > Airport", "TRANSIT", 34.432, 135.2304);
    /** 17:00 국제선 출발에서 수속 120분을 뺀 허브 기한. */
    private static final int KIX_DEADLINE = 15 * 60;

    private static PlaceCandidate kyoto(String name, String category, String role, double lat, double lng) {
        return new PlaceCandidate(1, nextId++, name, null, category, List.of(), "Kyoto", "JP",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, role, null, false, null, null, null, null);
    }

    private static PlaceCandidate kyotoSight(String name, double lat, double lng) {
        return kyoto(name, "Landmarks and Outdoors > Garden", "SIGHT", lat, lng);
    }

    /** 교토 숙소에서 나서 간사이공항으로 끝나는 출발일. endMin은 허브까지 기본 몫으로 잡던 그날 끝이다. */
    private static DayBuilder.Frame kyotoDepartureDay(int start, List<DayBuilder.Stop> spares) {
        return new DayBuilder.Frame(3, KYOTO_DEPARTURE, start, TripHubs.latestLeave(KIX_DEADLINE, null), 22 * 60,
                17 * 60 + 10, 7 * 60 + 5, KYOTO_HOTEL, null, KANSAI_AIRPORT, true, true, null, false, "car", spares,
                List.of(), 3, 1.25, null, "JP", KIX_DEADLINE);
    }

    private static void assertReachesHub(DayBuilder.Built built) {
        StepData hub = built.steps().get(built.steps().size() - 1);
        assertThat(hub.place().name()).isEqualTo(KANSAI_AIRPORT.name());
        int arrive = ScheduleTimes.toMinutesOrNull(hub.startTime());
        assertThat(arrive + TripHubs.estimatedLegSlack(hub.transportationDuration())).isLessThanOrEqualTo(KIX_DEADLINE);
    }

    @Test
    @DisplayName("출발일 마지막 장소는 그곳에서 공항까지 실제 구간으로 끝을 잡아, 먼 공항으로 가는 날 출발편을 놓치지 않는다")
    void departureDayUsesLegToHubFromEachPlace() {
        PlaceCandidate bamboo = kyotoSight("竹林の小径", 35.0176, 135.6765);
        PlaceCandidate soba = kyoto("そばしき 嵐山", "Dining and Drinking > Restaurant > Soba Restaurant", "MEAL",
                35.0144, 135.6775);
        List<DayBuilder.Stop> spares = List.of(
                stop(kyotoSight("天龍寺", 35.0158, 135.6738), "sight", "afternoon"),
                stop(kyotoSight("渡月橋", 35.0129, 135.6777), "sight", "afternoon"),
                stop(kyotoSight("野宮神社", 35.0177, 135.6744), "sight", "afternoon"),
                stop(kyotoSight("常寂光寺", 35.0214, 135.6698), "sight", "afternoon"),
                stop(kyotoSight("大船鉾", 35.0031, 135.7567), "sight", "afternoon"));

        DayBuilder.Built built = DayBuilder.build(kyotoDepartureDay(9 * 60 + 30, spares),
                List.of(stop(bamboo, "sight", "morning"), stop(soba, "meal", "lunch")));

        assertReachesHub(built);
        assertThat(names(built)).as("공항으로 떠나기 전 점심").contains("そばしき 嵐山");
        assertThat(built.dropped()).noneMatch(d -> d.contains("숙소 도착 늦음"));
    }

    @Test
    @DisplayName("넣지 못한 요청 장소는 이유와 그 갈래를 남기고, 요청하지 않은 장소는 남기지 않는다")
    void missedRequestKeepsCause() {
        PlaceCandidate farTemple = kyotoSight("鞍馬寺", 35.1180, 135.7710);
        PlaceCandidate farGarden = kyotoSight("貴船神社", 35.1215, 135.7629);
        DayBuilder.Frame f = kyotoDepartureDay(11 * 60, List.of());

        DayBuilder.Built built = DayBuilder.build(f, List.of(
                new DayBuilder.Stop(farTemple, "sight", "morning", List.of(), true),
                stop(farGarden, "sight", "morning")));

        assertReachesHub(built);
        assertThat(built.missed()).singleElement().satisfies(m -> {
            assertThat(m.stop().place().name()).isEqualTo("鞍馬寺");
            assertThat(m.reason()).isEqualTo(DayBuilder.DEPARTURE_TOO_SOON);
            assertThat(m.cause()).isEqualTo(DraftItineraryPlanner.MissCause.DEPARTURE);
        });
    }

    @Test
    @DisplayName("요청 장소를 살리려고 다른 곳을 뺐는데 요청 장소마저 늦어서 빠지면 뺐던 곳을 되살린다")
    void yieldIsUndoneWhenRequestIsLostAnyway() {
        PlaceCandidate haneda = kyoto("Haneda Airport", "Travel and Transportation > Transport Hub > Airport",
                "TRANSIT", 35.5494, 139.7798);
        PlaceCandidate airportHotel = kyoto("羽田エクセルホテル東急", "Travel and Transportation > Lodging > Hotel",
                "LODGING", 35.5490, 139.7865);
        PlaceCandidate oshino = kyoto("忍野八海", "Landmarks and Outdoors > Historic and Protected Site", "SIGHT",
                35.4608, 138.8495);
        PlaceCandidate lookout = kyoto("天上山公園", "Landmarks and Outdoors > Scenic Lookout", "SIGHT", 35.5040,
                138.7721);
        PlaceCandidate fujiQ = kyoto("Fuji-Q Highland", "Arts and Entertainment > Amusement Park", "SIGHT", 35.4869,
                138.7805);
        PlaceCandidate udon = kyoto("村松うどん店", "Dining and Drinking > Restaurant > Japanese Restaurant", "MEAL",
                35.5557, 138.9091);
        PlaceCandidate museum = kyoto("JAL Sky Museum", "Arts and Entertainment > Museum", "SIGHT", 35.5412,
                139.7891);
        DayBuilder.Frame arrivalDay = new DayBuilder.Frame(1, LocalDate.of(2027, 3, 7), 15 * 60, 21 * 60 + 30,
                22 * 60, 17 * 60 + 40, 6 * 60, null, haneda, airportHotel, false, false, null, false, "any",
                List.of(stop(museum, "sight", "afternoon")), List.of(), 3, 1.0, null, "JP", 0);

        DayBuilder.Built built = DayBuilder.build(arrivalDay, List.of(
                stop(lookout, "sight", "afternoon"),
                new DayBuilder.Stop(udon, "meal", "dinner", List.of(), true),
                stop(fujiQ, "sight", "afternoon"),
                new DayBuilder.Stop(oshino, "sight", "afternoon", List.of(), true)));

        assertThat(names(built)).contains("JAL Sky Museum").doesNotContain("忍野八海");
        assertThat(built.missed()).extracting(m -> m.stop().place().name()).containsExactly("忍野八海");
    }

    @Test
    @DisplayName("공항 가까운 장소는 숙소에서 공항까지로 잡은 그날 끝보다 늦게 끝나도 넣는다")
    void placeNearHubMayEndAfterLodgingBasedEnd() {
        PlaceCandidate nearAirport = kyotoSight("りんくう公園", 34.4096, 135.2969);
        DayBuilder.Frame f = kyotoDepartureDay(10 * 60 + 30, List.of(stop(nearAirport, "sight", "afternoon")));
        int lodgingBasedEnd = TripHubs.latestLeave(KIX_DEADLINE,
                DayBuilder.legOf(KYOTO_HOTEL, KANSAI_AIRPORT, "car", false).durationMin());

        DayBuilder.Built built = DayBuilder.build(f, List.of());

        assertReachesHub(built);
        StepData park = built.steps().stream().filter(s -> s.place().name().equals("りんくう公園")).findFirst()
                .orElseThrow();
        assertThat(ScheduleTimes.toMinutesOrNull(park.endTime())).isGreaterThan(lodgingBasedEnd);
    }

    @Test
    @DisplayName("아침에 도시를 옮기는 날은 옛 도시 쪽 장소를 먼저 들르고, 새 도시 쪽으로 넘어가는 구간에 도시 간 이동을 붙인다")
    void transferLegAppliesWhenCrossingToNewCity() {
        PlaceCandidate furanoPension = kyoto("ペンションジェイくらぶ", "Travel and Transportation > Lodging > Hotel",
                "LODGING", 43.3426, 142.3834);
        PlaceCandidate sapporoHotel = kyoto("グランドメルキュール札幌大通公園", "Travel and Transportation > Lodging > Hotel",
                "LODGING", 43.0590, 141.3480);
        PlaceCandidate aoiIke = kyoto("青い池", "Landmarks and Outdoors > Lake", "SIGHT", 43.4935, 142.6144);
        PlaceCandidate tvTower = kyoto("さっぽろテレビ塔", "Landmarks and Outdoors > Tower", "SIGHT", 43.0611, 141.3564);
        TransportLeg train = new TransportLeg("TRAIN", 79, BigDecimal.valueOf(150), BigDecimal.ZERO);
        DayBuilder.Frame transferDay = new DayBuilder.Frame(4, LocalDate.of(2027, 7, 18), 9 * 60 + 30, 21 * 60 + 30,
                22 * 60, 19 * 60 + 11, 4 * 60, furanoPension, null, sapporoHotel, false, false, train, false, "car",
                List.of(), List.of(), 3, 1.25, null, "JP", 0);

        DayBuilder.Built built = DayBuilder.build(transferDay,
                List.of(stop(aoiIke, "sight", "morning"), stop(tvTower, "sight", "afternoon")));

        assertThat(names(built)).containsSubsequence("青い池", "さっぽろテレビ塔");
        StepData lake = built.steps().stream().filter(x -> x.place().name().equals("青い池")).findFirst().orElseThrow();
        StepData tower = built.steps().stream().filter(x -> x.place().name().equals("さっぽろテレビ塔")).findFirst()
                .orElseThrow();
        assertThat(lake.transportationMode()).isNotEqualTo("TRAIN");
        assertThat(tower.transportationMode()).isEqualTo("TRAIN");
    }

    private static PlaceCandidate withStay(String name, String category, String role, int minutes) {
        return new PlaceCandidate(1, nextId++, name, null, category, List.of(), "Tokyo", "JP",
                BigDecimal.valueOf(35.63), BigDecimal.valueOf(139.88), null, null, 0.0, null, null, null, minutes,
                false, null, null, null, null, role, null, false, null, null, null, null);
    }

    @Test
    @DisplayName("테마파크는 일반 관광 상한과 페이스 배수 없이 장소 데이터의 권장 체류를 따르고, 작은 테마 시설은 짧게 머문다")
    void themeParkFollowsRecommendedStay() {
        PlaceCandidate disneySea = withStay("東京ディズニーシー", "Arts and Entertainment > Amusement Park", "ACTIVITY",
                480);
        PlaceCandidate floralVillage = withStay("湯布院フローラルビレッジ", "Arts and Entertainment > Amusement Park",
                "SIGHT", 90);
        PlaceCandidate garden = withStay("浜離宮恩賜庭園", "Landmarks and Outdoors > Garden", "SIGHT", 240);

        assertThat(DayBuilder.minutesOf(stop(disneySea, "experience", "morning"), 1.25)).isEqualTo(480);
        assertThat(DayBuilder.minutesOf(stop(floralVillage, "sight", "afternoon"), 1.25)).isEqualTo(90);
        assertThat(DayBuilder.minutesOf(stop(garden, "sight", "morning"), 1.25)).isEqualTo(DayBuilder.MAX_STAY);
    }
}
