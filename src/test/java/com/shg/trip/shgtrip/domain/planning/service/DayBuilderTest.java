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
                false, hardEnd, null, false, "walk", spares, earlier, Integer.MAX_VALUE, 1.0, null, "KR");
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
                spares, List.of(), 2, 1.0, null, "KR");
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
}
