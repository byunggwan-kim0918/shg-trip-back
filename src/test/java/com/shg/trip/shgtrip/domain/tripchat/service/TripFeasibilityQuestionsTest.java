package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TripFeasibilityQuestionsTest {

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

        OpenQuestion q = TripFeasibilityQuestions.next(e.draft(), Map.of(), Map.of(), Set.of(), "KR", null);

        assertThat(q.askKey()).isEqualTo(TripFeasibilityQuestions.PREFIX + "missing:나카스야타이");
        assertThat(q.examples()).contains("빼고 갈게", "그대로 둘게");
        assertThat(q.description()).contains("넣지 못했다고 알려 드릴게요");
    }

    @Test
    @DisplayName("당일 여행에 저녁 출발이 일러 해 진 뒤 시간이 없으면 저녁 바람을 그 턴에 한 번만 묻는다")
    void eveningWishWithoutEveningIsAskedOnce() {
        Events e = dayTrip().user(DraftField.DEPARTURE_TIME, "16:00", "4시 출발")
                .user(DraftField.MUST_VISIT, "광안리 야경", "광안리 야경은 꼭");

        OpenQuestion q = TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(), "KR", BUSAN);

        assertThat(q.askKey()).isEqualTo(TripFeasibilityQuestions.PREFIX + "evening:광안리야경");
        assertThat(q.examples()).contains("그대로 둘게");
        assertThat(TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(q.askKey()), "KR", BUSAN)).isNull();

        Events late = dayTrip().user(DraftField.DEPARTURE_TIME, "22:00", "10시 출발")
                .user(DraftField.MUST_VISIT, "광안리 야경", "광안리 야경은 꼭");
        assertThat(TripFeasibilityQuestions.next(late.draft(), null, null, Set.of(), "KR", BUSAN)).isNull();
    }

    @Test
    @DisplayName("당일 여행의 일출 바람은 해 뜨기 전에 도착하지 않으면 묻고, 하루 넘게 묵으면 묻지 않는다")
    void sunriseWishOnDayTrip() {
        Events e = dayTrip().user(DraftField.PRIORITIES, "해운대 일출 보기");

        assertThat(TripFeasibilityQuestions.next(e.draft(), null, null, Set.of(), "KR", BUSAN).askKey())
                .isEqualTo(TripFeasibilityQuestions.PREFIX + "sunrise:해운대일출보기");
        assertThat(TripFeasibilityQuestions.next(e.user(DraftField.ARRIVAL_TIME, "05:00", "새벽 5시 도착").draft(),
                null, null, Set.of(), "KR", BUSAN)).isNull();

        Events overnight = Events.busanTrip().user(DraftField.PRIORITIES, "해운대 일출 보기");
        assertThat(TripFeasibilityQuestions.next(overnight.draft(), null, null, Set.of(), "KR", BUSAN)).isNull();
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
}
