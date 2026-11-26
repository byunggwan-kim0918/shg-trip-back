package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TripFeasibilityQuestionsTest {

    private static final java.util.function.Function<String, java.util.Optional<Destination>> NO_AREAS =
            name -> java.util.Optional.empty();


    private static final Destination BUSAN = DomesticCities.find("부산").orElseThrow();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static Events dayTrip() {
        return Events.of().user(DraftField.DESTINATION, "부산")
                .user(DraftField.START_DATE, "2026-11-20", "11월 20일 당일")
                .user(DraftField.END_DATE, "2026-11-20", "11월 20일 당일")
                .user(DraftField.TRANSPORT, "car", "차로 가요");
    }

    @Test
    @DisplayName("데이터에서 찾지 못한 꼭 갈 곳은 바꾸기·빼기와 함께 그대로 두기를 고를 수 있다")
    void missingPlaceOffersKeep() {
        Events e = dayTrip().user(DraftField.MUST_VISIT, "나카스 야타이", "나카스 야타이는 꼭");

        OpenQuestion q = TripFeasibilityQuestions.next(e.draft(), Map.of(), Map.of(), Set.of(), "KR", null, NO_AREAS);

        assertThat(q.askKey()).isEqualTo(TripFeasibilityQuestions.PREFIX + "missing:나카스야타이");
        assertThat(q.examples()).contains("빼고 갈게", "그대로 둘게");
        assertThat(q.description()).contains("넣지 못했다고 알려 드릴게요");
    }

    @Test
    @DisplayName("당일 여행에 저녁 출발이 일러 해 진 뒤 시간이 없으면 저녁 바람을 그 턴에 한 번만 묻는다")
    void eveningWishWithoutEveningIsAskedOnce() {
        Events e = dayTrip().user(DraftField.DEPARTURE_TIME, "16:00", "4시 출발")
                .user(DraftField.MUST_VISIT, "광안리 야경", "광안리 야경은 꼭");

        OpenQuestion q = TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(), "KR", BUSAN, NO_AREAS);

        assertThat(q.askKey()).isEqualTo(TripFeasibilityQuestions.PREFIX + "evening:광안리야경");
        assertThat(q.examples()).contains("그대로 둘게");
        assertThat(TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(q.askKey()), "KR", BUSAN, NO_AREAS)).isNull();

        Events late = dayTrip().user(DraftField.DEPARTURE_TIME, "22:00", "10시 출발")
                .user(DraftField.MUST_VISIT, "광안리 야경", "광안리 야경은 꼭");
        assertThat(TripFeasibilityQuestions.next(late.draft(), null, null, Set.of(), "KR", BUSAN, NO_AREAS)).isNull();
    }

    @Test
    @DisplayName("당일 여행의 일출 바람은 해 뜨기 전에 도착하지 않으면 묻고, 하루 넘게 묵으면 묻지 않는다")
    void sunriseWishOnDayTrip() {
        Events e = dayTrip().user(DraftField.PRIORITIES, "해운대 일출 보기");

        assertThat(TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(), "KR", BUSAN, NO_AREAS).askKey())
                .isEqualTo(TripFeasibilityQuestions.PREFIX + "sunrise:해운대일출보기");
        assertThat(TripFeasibilityQuestions.next(e.user(DraftField.ARRIVAL_TIME, "05:00", "새벽 5시 도착").draft(),
                null, null, Set.of(), "KR", BUSAN, NO_AREAS)).isNull();

        Events overnight = Events.busanTrip().user(DraftField.PRIORITIES, "해운대 일출 보기");
        assertThat(TripFeasibilityQuestions.next(overnight.draft(), null, null, Set.of(), "KR", BUSAN, NO_AREAS)).isNull();
    }

    @Test
    @DisplayName("넣을 수 없다는 되묻기 뒤에도 남긴 바람만 그대로 둘 요청이 되어 생성 요청까지 간다")
    void acknowledgedWishesReachRequest() {
        Events e = Events.busanTrip().user(DraftField.MUST_VISIT, "나카스 야타이", "나카스 야타이는 꼭")
                .user(DraftField.MUST_VISIT, "감천문화마을", "감천문화마을도");
        Set<String> asked = Set.of(TripFeasibilityQuestions.PREFIX + "missing:나카스야타이",
                TripFeasibilityQuestions.PREFIX + "missing:오타루운하");

        assertThat(TripFeasibilityQuestions.acknowledged(e.draft(), asked)).containsExactly("나카스 야타이");

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), Map.of(), TODAY, asked);
        assertThat(r.request().acceptedUnmet()).containsExactly("나카스 야타이");
        assertThat(TripRequestMapper.map(e.draft(), Map.of(), TODAY, Set.of()).request().acceptedUnmet()).isNull();
    }

    @Test
    @DisplayName("묵는 곳에서 당일로 다녀오기 먼 들를 지역은 빼거나 그곳에 묵는 안을 내고, 한 번만 묻는다")
    void farVisitAreaAsksAdjustment() {
        var registry = com.shg.trip.shgtrip.domain.place.destination.Destinations.fromClasspath();
        java.util.function.Function<String, java.util.Optional<Destination>> areaOf = n -> registry.resolveArea(n, "JP");
        Events e = Events.of().user(DraftField.DESTINATION, "일본")
                .user(DraftField.START_DATE, "2027-07-15").user(DraftField.END_DATE, "2027-07-19")
                .user(DraftField.VISIT_AREAS, "도쿄").user(DraftField.VISIT_AREAS, "후쿠오카")
                .user(DraftField.STAYS, "도쿄|4");

        OpenQuestion q = TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(), "JP", null, areaOf);

        assertThat(q).isNotNull();
        assertThat(q.examples()).containsExactly("후쿠오카는 빼줘", "후쿠오카에서 1박 할게", "그대로 둘게");
        assertThat(TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(q.askKey()), "JP", null, areaOf)).isNull();
        Events hokkaido = Events.of().user(DraftField.DESTINATION, "홋카이도")
                .user(DraftField.START_DATE, "2027-07-15").user(DraftField.END_DATE, "2027-07-19")
                .user(DraftField.VISIT_AREAS, "삿포로").user(DraftField.VISIT_AREAS, "오타루")
                .user(DraftField.VISIT_AREAS, "후라노").user(DraftField.VISIT_AREAS, "비에이")
                .user(DraftField.STAYS, "삿포로|2,후라노|1,삿포로|1");
        assertThat(TripFeasibilityQuestions.next(hokkaido.draft(), null, null, Set.of(), "JP", null, areaOf)).isNull();
    }

    @Test
    @DisplayName("해외 여행에서 항공편 시각을 모르면 한 번 묻고, 시각을 알거나 배로 가거나 국내면 묻지 않는다")
    void flightTimesAskedOnceForFlyingAbroad() {
        Events abroad = Events.of().user(DraftField.DESTINATION, "홋카이도");

        OpenQuestion q = FlightTimesQuestion.next(abroad.draft(), Set.of(), "JP");
        assertThat(q).isNotNull();
        assertThat(q.slot()).isEqualTo(Slot.EXTRAS);
        assertThat(FlightTimesQuestion.next(abroad.draft(), Set.of(q.askKey()), "JP")).isNull();
        assertThat(FlightTimesQuestion.next(abroad.draft(), Set.of(), "KR")).isNull();
        assertThat(FlightTimesQuestion.next(Events.of().user(DraftField.ARRIVAL_TIME, "10:00")
                .user(DraftField.DEPARTURE_TIME, "18:00").draft(), Set.of(), "JP")).isNull();
        assertThat(FlightTimesQuestion.next(Events.of().user(DraftField.ARRIVAL_NOTE, "부산에서 배로 가요").draft(),
                Set.of(), "JP")).isNull();
        assertThat(FlightTimesQuestion.next(Events.of().user(DraftField.ARRIVAL_TIME, "10:00").draft(), Set.of(), "JP")
                .title()).contains("출발");
    }
}
