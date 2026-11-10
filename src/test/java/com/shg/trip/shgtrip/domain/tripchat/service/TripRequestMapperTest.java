package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripRequestMapperTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static TripRequestMapper.Result map(Events e) {
        return TripRequestMapper.map(e.draft(), Map.of(), TODAY);
    }

    @Test
    @DisplayName("대화 조건을 기존 생성 요청으로 옮기고 요청 검증을 통과한다")
    void mapsToValidRequest() {
        TripRequestMapper.Result r = map(Events.busanTrip());

        ItineraryGenerateRequest req = r.request();
        assertThat(r.ready()).isTrue();
        assertThat(req.destination()).isEqualTo("부산");
        assertThat(req.startDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(req.endDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(req.companion()).isEqualTo("couple");
        assertThat(req.transportPref()).isEqualTo("car");
        assertThat(req.pace()).isEqualTo("relaxed");
        assertThat(req.themes()).containsExactly("ocean", "healing");
        assertThat(req.categories()).isNotEmpty();
        assertThat(req.mode()).isEqualTo(ItineraryGenerateRequest.PlanningMode.AUTO);
        assertThat(req.customPlaceNames()).isEmpty();
        assertThat(req.description()).isEqualTo("원하는 여행: 바다 보면서 느긋하게 쉬기");
        assertThat(r.days()).isEqualTo(6);
        assertThat(VALIDATOR.validate(req)).isEmpty();
        assertThat(r.notes().get(Slot.MOOD)).containsExactly("느긋한 일정 · 바다/해변·힐링 위주로 찾아요 (제안)");
    }

    @Test
    @DisplayName("대중교통은 walk로 바꾸지 않고 any로 보내며 노선·시간표 미반영을 알린다")
    void transitIsApproximated() {
        TripRequestMapper.Result r = map(Events.busanTrip().user(DraftField.TRANSPORT, "transit", "대중교통"));

        assertThat(r.request().transportPref()).isEqualTo("any");
        assertThat(r.request().description()).contains("이동: 대중교통(차 없음)");
        assertThat(r.notes().get(Slot.TRANSPORT)).singleElement().asString().contains("시간표는 반영되지 않아요");
    }

    @Test
    @DisplayName("망설이는 도보 의도는 확정하지 않는다")
    void tentativeWalkIsAny() {
        TripRequestMapper.Result r = map(Events.busanTrip().clearSlot(Slot.TRANSPORT)
                .tentative(DraftField.TRANSPORT, "walk", "흠 걸어서?"));

        assertThat(r.request().transportPref()).isEqualTo("any");
    }

    @Test
    @DisplayName("예약한 숙소가 DB 숙소와 맞으면 전 일정 숙소로 고정한다")
    void bookedLodgingIsFixed() {
        Events e = Events.busanTrip().clearSlot(Slot.ANCHORS)
                .user(DraftField.LODGING_STATE, "booked", "파라다이스 호텔 예약했어")
                .user(DraftField.LODGING, "파라다이스 호텔", "파라다이스 호텔 예약했어");
        Map<String, PlaceMatch> matches = Map.of(PlaceMatcher.key(DraftField.LODGING, "파라다이스 호텔"),
                new PlaceMatch(77L, "파라다이스 호텔 부산", "LODGING"));

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY);

        assertThat(r.request().selectedPlaceIds()).containsExactly(77L);
        assertThat(r.request().mode()).isEqualTo(ItineraryGenerateRequest.PlanningMode.MANUAL);
        assertThat(r.notes().get(Slot.ANCHORS)).contains("파라다이스 호텔 부산 · 모든 날 이 숙소 기준으로 짜요.");
    }

    @Test
    @DisplayName("숙소 희망 지역은 예약이 아니라 참고 문구로만 보낸다")
    void lodgingWishIsReference() {
        Events e = Events.busanTrip().clearSlot(Slot.ANCHORS)
                .user(DraftField.LODGING_STATE, "wish", "해운대에서 자고 싶고")
                .user(DraftField.LODGING, "해운대", "해운대에서 자고 싶고")
                .user(DraftField.MUST_VISIT, "블루라인파크");

        TripRequestMapper.Result r = map(e);

        assertThat(r.request().selectedPlaceIds()).isEmpty();
        assertThat(r.request().description()).contains("숙소: 해운대 쪽 희망").contains("가고 싶은 곳: 블루라인파크");
        assertThat(r.notes().get(Slot.ANCHORS)).contains(GeneratorSupport.LODGING_REFERENCE,
                "블루라인파크 · " + GeneratorSupport.PLACE_REFERENCE);
    }

    @Test
    @DisplayName("피하고 싶은 장소 이름은 요청 문장에 넣지 않는다")
    void avoidPlaceNeverReachesDescription() {
        Events e = Events.busanTrip()
                .user(DraftField.AVOID_PLACES, "감천문화마을")
                .user(DraftField.NOTES, "감천문화마을은 빼줘")
                .user(DraftField.FOOD_AVOID, "해산물");

        TripRequestMapper.Result r = map(e);

        assertThat(r.request().description()).doesNotContain("감천").contains("못 먹거나 피할 음식: 해산물");
        assertThat(r.notes().get(Slot.EXTRAS)).contains(GeneratorSupport.AVOID_PLACES);
    }

    @Test
    @DisplayName("1인 예산은 인원을 곱하고, 인원을 모르거나 숙박 제외면 금액을 보내지 않는다")
    void budgetScopes() {
        Events perPerson = Events.busanTrip().user(DraftField.BUDGET, "300000").user(DraftField.BUDGET_SCOPE, "per_person");
        assertThat(map(perPerson).request().budget()).isEqualByComparingTo(new BigDecimal("600000"));

        Events unknownParty = Events.busanTrip().clearSlot(Slot.COMPANY)
                .user(DraftField.BUDGET, "300000").user(DraftField.BUDGET_SCOPE, "per_person");
        TripRequestMapper.Result r = map(unknownParty);
        assertThat(r.request().budget()).isNull();
        assertThat(r.notes().get(Slot.EXTRAS)).contains(GeneratorSupport.BUDGET_NEEDS_PARTY);

        Events noLodging = Events.busanTrip().user(DraftField.BUDGET, "500000")
                .user(DraftField.BUDGET_SCOPE, "excluding_lodging");
        assertThat(map(noLodging).request().budget()).isNull();
    }

    @Test
    @DisplayName("하루 5만원 미만 예산은 생성 전에 보완을 요청한다")
    void budgetFloorBlocks() {
        TripRequestMapper.Result r = map(Events.busanTrip().user(DraftField.BUDGET, "200000"));

        assertThat(r.ready()).isFalse();
        assertThat(r.blockers()).extracting(TripRequestMapper.Blocker::key).containsExactly("budget_floor");
    }

    @Test
    @DisplayName("날짜가 없거나 생성할 수 없는 날짜면 보완을 요청한다")
    void dateBlockers() {
        assertThat(map(Events.busanTrip().clearSlot(Slot.DATES)).blockers())
                .extracting(TripRequestMapper.Blocker::key).containsExactly("dates_missing");
        assertThat(map(Events.busanTrip().user(DraftField.END_DATE, "2026-11-20")).blockers())
                .extracting(TripRequestMapper.Blocker::key).containsExactly("dates_day_trip");
        assertThat(map(Events.busanTrip().user(DraftField.END_DATE, "2026-12-05")).blockers())
                .extracting(TripRequestMapper.Blocker::key).containsExactly("dates_too_long");
        assertThat(map(Events.busanTrip().user(DraftField.START_DATE, "2026-11-01")).blockers())
                .extracting(TripRequestMapper.Blocker::key).containsExactly("dates_past");
    }

    @Test
    @DisplayName("대략 날짜만 있으면 그 시기의 실제 날짜를 보완 예시로 준다")
    void approxDateBlockerSuggestsRanges() {
        TripRequestMapper.Result r = map(Events.busanTrip().clearSlot(Slot.DATES)
                .user(DraftField.DATE_APPROX, "11|mid", "11월 중순쯤"));

        TripRequestMapper.Blocker b = r.blockers().get(0);
        assertThat(b.description()).startsWith("11월 중순쯤이라고 했어요.");
        assertThat(b.examples()).contains("11월 20일(금)부터 22일(일)까지");
    }

    @Test
    @DisplayName("추가 요청이 500자를 넘으면 자르지 않고 보완을 요청한다")
    void longDescriptionBlocksWithoutTruncation() {
        Events e = Events.busanTrip();
        for (int i = 0; i < 12; i++) e.user(DraftField.NOTES, "부탁 " + i + " " + "가".repeat(50));

        TripRequestMapper.Result r = map(e);

        assertThat(r.ready()).isFalse();
        assertThat(r.description().length()).isGreaterThan(TripRequestMapper.MAX_DESCRIPTION);
        assertThat(r.blockers()).extracting(TripRequestMapper.Blocker::key).containsExactly("description_too_long");
    }

    @Test
    @DisplayName("취향을 말했지만 분류가 없으면 기본 취향을 쓴다고 알린다")
    void unclassifiedTasteIsDisclosed() {
        Events e = Events.busanTrip().clearSlot(Slot.MOOD).user(DraftField.PRIORITIES, "맛집 위주로 다니기");

        TripRequestMapper.Result r = map(e);

        assertThat(r.request().themes()).isEqualTo(GeneratorSupport.DEFAULT_THEMES);
        assertThat(r.notes().get(Slot.MOOD)).containsExactly(GeneratorSupport.UNCLASSIFIED_TASTE);
    }

    @Test
    @DisplayName("취향을 맡기면 기본 취향으로 채우고 제안이라고 표시한다")
    void delegatedTasteUsesDefaults() {
        Events e = Events.busanTrip().clearSlot(Slot.MOOD).outcome(Slot.MOOD, FieldStatus.DELEGATED);

        TripRequestMapper.Result r = map(e);

        assertThat(r.request().themes()).isEqualTo(GeneratorSupport.DEFAULT_THEMES);
        assertThat(r.request().categories()).isNotEmpty();
        assertThat(r.notes().get(Slot.MOOD)).containsExactly(GeneratorSupport.DEFAULT_TASTE);
        assertThat(VALIDATOR.validate(r.request())).isEmpty();
    }

    @Test
    @DisplayName("시작일과 박 수만 있으면 종료일을 계산한다")
    void endDateFromNights() {
        TripRequestMapper.Result r = map(Events.busanTrip().clearSlot(Slot.DATES)
                .user(DraftField.START_DATE, "2026-11-20", "20일부터")
                .user(DraftField.NIGHTS, "2", "2박 3일"));

        assertThat(r.ready()).isTrue();
        assertThat(r.request().endDate()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(r.days()).isEqualTo(3);
    }

    @Test
    @DisplayName("모델이 추론한 숙소·장소는 고정하지 않는다")
    void inferredAnchorsAreIgnored() {
        Events e = Events.busanTrip().clearSlot(Slot.ANCHORS)
                .inferred(DraftField.LODGING_STATE, "booked")
                .inferred(DraftField.LODGING, "파라다이스 호텔")
                .inferred(DraftField.MUST_VISIT, "해운대");
        Map<String, PlaceMatch> matches = Map.of(
                PlaceMatcher.key(DraftField.LODGING, "파라다이스 호텔"), new PlaceMatch(77L, "파라다이스 호텔 부산", "LODGING"),
                PlaceMatcher.key(DraftField.MUST_VISIT, "해운대"), new PlaceMatch(5L, "해운대해수욕장", "SIGHT"));

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY);

        assertThat(r.request().selectedPlaceIds()).isEmpty();
        assertThat(r.request().mode()).isEqualTo(ItineraryGenerateRequest.PlanningMode.AUTO);
    }

    @Test
    @DisplayName("고정 장소가 10곳을 넘으면 나머지는 참고로만 둔다고 알린다")
    void placesOverLimitAreDisclosed() {
        Events e = Events.busanTrip().clearSlot(Slot.ANCHORS);
        Map<String, PlaceMatch> matches = new java.util.HashMap<>();
        for (int i = 1; i <= 11; i++) {
            e.user(DraftField.MUST_VISIT, "장소" + i);
            matches.put(PlaceMatcher.key(DraftField.MUST_VISIT, "장소" + i), new PlaceMatch((long) i, "장소" + i, "SIGHT"));
        }

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY);

        assertThat(r.request().selectedPlaceIds()).hasSize(10);
        assertThat(r.notes().get(Slot.ANCHORS)).contains("장소11 · " + GeneratorSupport.PLACES_FULL);
        assertThat(r.request().description()).contains("가고 싶은 곳: 장소11");
    }

    @Test
    @DisplayName("한 글자 피할 장소로는 다른 조각을 거르지 않는다")
    void shortAvoidNameDoesNotFilter() {
        String description = TripRequestMapper.describe(List.of("원하는 여행: 부산 바다", "숙소: 해운대 쪽 희망"),
                List.of(item("산"), item("해운대")));

        assertThat(description).isEqualTo("원하는 여행: 부산 바다");
    }

    private static Draft.Item item(String text) {
        return new Draft.Item(text, com.shg.trip.shgtrip.domain.tripchat.model.FieldSource.USER, text, null, null, 0);
    }
}
