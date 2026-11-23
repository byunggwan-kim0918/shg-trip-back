package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TripRequestMapperTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Validator VALIDATOR = Validation.byDefaultProvider().configure()
            .clockProvider(() -> Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneId.of("Asia/Seoul")))
            .buildValidatorFactory().getValidator();

    private static TripRequestMapper.Result map(Events e) {
        return TripRequestMapper.map(e.draft(), Map.of(), TODAY, Set.of());
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

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY, Set.of());

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
                "블루라인파크 · " + GeneratorSupport.PLACE_BY_AREA);
    }

    @Test
    @DisplayName("피할 장소는 이름 목록으로 따로 보내고 요청 문장은 사용자 말 그대로 둔다")
    void avoidPlaceNamesTravelSeparately() {
        Events e = Events.busanTrip()
                .user(DraftField.AVOID_PLACES, "감천문화마을")
                .user(DraftField.NOTES, "감천문화마을은 빼줘")
                .user(DraftField.FOOD_AVOID, "해산물");

        TripRequestMapper.Result r = map(e);

        assertThat(r.request().description()).contains("못 먹거나 피할 음식: 해산물", "기타: 감천문화마을은 빼줘");
        assertThat(r.request().avoidPlaceNames()).containsExactly("감천문화마을");
        assertThat(r.request().avoidPlaceIds()).isNull();
        assertThat(r.notes().get(Slot.EXTRAS)).contains("감천문화마을 · " + GeneratorSupport.AVOID_PLACE_NOT_FOUND);
    }

    @Test
    @DisplayName("못 먹는 음식·피할 활동은 정해진 종류면 추론한 항목까지 조건으로 보내고, 그 밖은 참고만 한다고 안내한다")
    void restrictionsFromChatItems() {
        Events e = Events.busanTrip()
                .user(DraftField.FOOD_AVOID, "해산물").user(DraftField.FOOD_AVOID, "술")
                .user(DraftField.FOOD_AVOID, "매운 음식").inferred(DraftField.FOOD_AVOID, "돼지고기")
                .user(DraftField.AVOID_ACTIVITIES, "등산").user(DraftField.AVOID_ACTIVITIES, "종교 시설")
                .user(DraftField.AVOID_ACTIVITIES, "술집");

        TripRequestMapper.Result r = map(e);

        assertThat(r.request().restrictions()).containsExactly(PlaceRestriction.SEAFOOD, PlaceRestriction.NIGHTLIFE,
                PlaceRestriction.PORK, PlaceRestriction.HIKING, PlaceRestriction.RELIGIOUS);
        assertThat(r.notes().get(Slot.EXTRAS)).contains(
                "해산물 · " + GeneratorSupport.FOOD_EXCLUDED, "술 · " + GeneratorSupport.BAR_EXCLUDED,
                "매운 음식 · " + GeneratorSupport.RESTRICTION_REFERENCE,
                "돼지고기 · " + GeneratorSupport.FOOD_EXCLUDED,
                "등산 · " + GeneratorSupport.HIKING_EXCLUDED, "종교 시설 · " + GeneratorSupport.ACTIVITY_EXCLUDED,
                "술집 · " + GeneratorSupport.BAR_EXCLUDED);
        assertThat(r.request().description()).contains("못 먹거나 피할 음식: 해산물, 술, 매운 음식, 돼지고기",
                "피하고 싶은 활동: 등산, 종교 시설, 술집");
        assertThat(VALIDATOR.validate(r.request())).isEmpty();
    }

    @Test
    @DisplayName("모델이 종류 이름 뒤에 말을 덧붙이거나 여러 개를 적어도 알아보고, 먹을 수 있다는 말과 다른 낱말 속 이름은 맞추지 않는다")
    void restrictionNamesWithExtraWords() {
        assertThat(GeneratorSupport.restrictionsOf("해산물 알레르기")).containsExactly(PlaceRestriction.SEAFOOD);
        assertThat(GeneratorSupport.restrictionsOf("종교 시설")).containsExactly(PlaceRestriction.RELIGIOUS);
        assertThat(GeneratorSupport.restrictionsOf("트레킹")).containsExactly(PlaceRestriction.TREKKING);
        assertThat(GeneratorSupport.restrictionNote(PlaceRestriction.TREKKING)).isEqualTo(GeneratorSupport.TREKKING_EXCLUDED);
        assertThat(GeneratorSupport.restrictionsOf("술")).containsExactly(PlaceRestriction.NIGHTLIFE);
        assertThat(GeneratorSupport.restrictionsOf("돼지고기, 소고기"))
                .containsExactly(PlaceRestriction.PORK, PlaceRestriction.BEEF);
        assertThat(GeneratorSupport.restrictionsOf("해산물은 괜찮고 매운 거")).isEmpty();
        assertThat(GeneratorSupport.restrictionsOf("해산물은 괜찮은데 매운 건 못 먹어요")).isEmpty();
        assertThat(GeneratorSupport.restrictionsOf("해산물 안 좋아함")).containsExactly(PlaceRestriction.SEAFOOD);
        assertThat(GeneratorSupport.restrictionsOf("등산은 싫어")).containsExactly(PlaceRestriction.HIKING);
        assertThat(GeneratorSupport.restrictionsOf("놀이공원 말고")).containsExactly(PlaceRestriction.THEME_PARK);
        assertThat(GeneratorSupport.restrictionsOf("쇼핑센터")).containsExactly(PlaceRestriction.SHOPPING);
        assertThat(GeneratorSupport.restrictionsOf("트레킹 코스")).containsExactly(PlaceRestriction.TREKKING);
        assertThat(GeneratorSupport.restrictionsOf("돼지고기 요리")).containsExactly(PlaceRestriction.PORK);
        assertThat(GeneratorSupport.restrictionsOf("놀이공원 같은 곳")).containsExactly(PlaceRestriction.THEME_PARK);
        assertThat(GeneratorSupport.restrictionsOf("날것(회)")).containsExactly(PlaceRestriction.RAW);
        assertThat(GeneratorSupport.restrictionsOf("술집 같은 곳")).containsExactly(PlaceRestriction.NIGHTLIFE);
        assertThat(GeneratorSupport.restrictionsOf("등산이나 트레킹"))
                .containsExactly(PlaceRestriction.HIKING, PlaceRestriction.TREKKING);
        assertThat(GeneratorSupport.restrictionsOf("박물관 근처 카페")).isEmpty();
        assertThat(GeneratorSupport.restrictionsOf("고기국수")).isEmpty();
        assertThat(GeneratorSupport.restrictionsOf("미술관 관람")).isEmpty();
        assertThat(GeneratorSupport.restrictionsOf("물고기")).isEmpty();
        assertThat(GeneratorSupport.restrictionsOf("매운 음식")).isEmpty();
    }

    @Test
    @DisplayName("모든 조건 종류는 대화에서 고를 이름이 있다")
    void everyRestrictionHasChatName() {
        for (PlaceRestriction r : PlaceRestriction.values()) {
            String label = GeneratorSupport.restrictionLabel(r);
            assertThat(label).as(r.name()).isNotEqualTo(r.name());
            assertThat(GeneratorSupport.restrictionsOf(label)).as(label).containsExactly(r);
        }
    }

    @Test
    @DisplayName("대화 프롬프트가 적게 하는 이름은 모두 조건 종류로 알아보고, 모든 종류가 프롬프트에 나온다")
    void promptNamesMatchRestrictions() throws IOException {
        String prompt = new ClassPathResource("prompts/trip-chat-turn.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        Matcher rows = Pattern
                .compile("\\| (?:FOOD_AVOID|AVOID_ACTIVITIES) \\(목록\\) \\| (.+?) 중 맞는 이름").matcher(prompt);
        Set<PlaceRestriction> named = EnumSet.noneOf(PlaceRestriction.class);
        int rowCount = 0;
        while (rows.find()) {
            rowCount++;
            for (String name : rows.group(1).split("·")) {
                List<PlaceRestriction> found = GeneratorSupport.restrictionsOf(name);
                assertThat(found).as(name).hasSize(1);
                named.addAll(found);
            }
        }
        assertThat(rowCount).isEqualTo(2);
        assertThat(named).containsExactlyInAnyOrder(PlaceRestriction.values());
    }

    @Test
    @DisplayName("한 항목에 종류가 여럿이면 종류마다 이름을 붙여 따로 안내한다")
    void multipleRestrictionsInOneItem() {
        Events e = Events.busanTrip().user(DraftField.AVOID_ACTIVITIES, "술집, 등산");

        TripRequestMapper.Result r = map(e);

        assertThat(r.request().restrictions()).containsExactly(PlaceRestriction.NIGHTLIFE, PlaceRestriction.HIKING);
        assertThat(r.notes().get(Slot.EXTRAS)).contains("술집 · " + GeneratorSupport.BAR_EXCLUDED,
                "등산 · " + GeneratorSupport.HIKING_EXCLUDED);
    }

    @Test
    @DisplayName("좋아하는 음식 안내는 참고만 한다고 하고, 못 먹는 음식은 뺀다고 따로 안내한다")
    void likesAndAvoidsGetSeparateNotes() {
        Events e = Events.busanTrip().user(DraftField.FOOD_LIKES, "국밥").user(DraftField.FOOD_AVOID, "해산물");

        TripRequestMapper.Result r = map(e);

        assertThat(r.notes().get(Slot.EXTRAS)).contains(GeneratorSupport.CONDITIONS_REFERENCE,
                "해산물 · " + GeneratorSupport.FOOD_EXCLUDED);
        assertThat(GeneratorSupport.CONDITIONS_REFERENCE).doesNotContain("활동");
    }

    @Test
    @DisplayName("이름이 맞은 피할 장소는 id와 맞춘 장소 이름으로 보내고, 꼭 넣을 장소와 겹치면 빼지 않는다")
    void matchedAvoidPlacesAreSentById() {
        Events e = Events.busanTrip().clearSlot(Slot.ANCHORS)
                .user(DraftField.MUST_VISIT, "해운대해수욕장")
                .user(DraftField.MUST_VISIT, "개미집")
                .user(DraftField.AVOID_PLACES, "감천문화마을")
                .user(DraftField.AVOID_PLACES, "해운대")
                .user(DraftField.AVOID_PLACES, "부산역")
                .user(DraftField.AVOID_PLACES, "할매국밥");
        Map<String, PlaceMatch> matches = Map.of(
                PlaceMatcher.key(DraftField.MUST_VISIT, "해운대해수욕장"), new PlaceMatch(5L, "해운대해수욕장", "ATTRACTION"),
                PlaceMatcher.key(DraftField.MUST_VISIT, "개미집"), new PlaceMatch(6L, "개미집", "DINING"),
                PlaceMatcher.key(DraftField.AVOID_PLACES, "감천문화마을"), new PlaceMatch(9L, "감천문화마을", "ATTRACTION"),
                PlaceMatcher.key(DraftField.AVOID_PLACES, "해운대"), new PlaceMatch(5L, "해운대해수욕장", "ATTRACTION"),
                PlaceMatcher.key(DraftField.AVOID_PLACES, "부산역"), new PlaceMatch(3L, "부산역", "TRANSIT_HUB"),
                PlaceMatcher.key(DraftField.AVOID_PLACES, "할매국밥"), new PlaceMatch(8L, "할매국밥", "DINING"));

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY, Set.of());

        assertThat(r.request().avoidPlaceIds()).containsExactly(9L, 8L);
        assertThat(r.request().selectedPlaceIds()).containsExactly(5L, 6L);
        assertThat(r.request().avoidPlaceNames()).containsExactly("감천문화마을", "할매국밥");
        assertThat(r.notes().get(Slot.ANCHORS)).contains("해운대해수욕장 · " + GeneratorSupport.PLACE_INCLUDED,
                "개미집(식당) · " + GeneratorSupport.PLACE_INCLUDED);
        assertThat(r.notes().get(Slot.EXTRAS)).contains("감천문화마을 · " + GeneratorSupport.AVOID_PLACE_EXCLUDED,
                "해운대해수욕장 · " + GeneratorSupport.AVOID_PLACE_KEPT, "부산역 · " + GeneratorSupport.AVOID_PLACE_HUB,
                "할매국밥(식당) · " + GeneratorSupport.AVOID_PLACE_EXCLUDED);
        assertThat(VALIDATOR.validate(r.request())).isEmpty();
    }

    @Test
    @DisplayName("피할 장소는 10곳까지 보내고, 이미 넣은 장소를 다시 말한 것은 상한 안내 없이 넘긴다")
    void avoidPlacesAreCappedAtTen() {
        Events e = Events.busanTrip();
        java.util.Map<String, PlaceMatch> matches = new java.util.HashMap<>();
        for (int i = 1; i <= 10; i++) {
            e.user(DraftField.AVOID_PLACES, "장소" + i);
            matches.put(PlaceMatcher.key(DraftField.AVOID_PLACES, "장소" + i), new PlaceMatch((long) i, "장소" + i, "ATTRACTION"));
        }
        e.user(DraftField.AVOID_PLACES, "장소1 다시").user(DraftField.AVOID_PLACES, "열한번째");
        matches.put(PlaceMatcher.key(DraftField.AVOID_PLACES, "장소1 다시"), new PlaceMatch(1L, "장소1", "ATTRACTION"));
        matches.put(PlaceMatcher.key(DraftField.AVOID_PLACES, "열한번째"), new PlaceMatch(11L, "열한번째", "ATTRACTION"));

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY, Set.of());

        assertThat(r.request().avoidPlaceIds()).hasSize(10).doesNotContain(11L);
        assertThat(r.notes().get(Slot.EXTRAS)).contains("열한번째 · " + GeneratorSupport.AVOID_PLACES_FULL)
                .doesNotContain("장소1 · " + GeneratorSupport.AVOID_PLACES_FULL);
        assertThat(VALIDATOR.validate(r.request())).isEmpty();
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

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY, Set.of());

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

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY, Set.of());

        assertThat(r.request().selectedPlaceIds()).hasSize(10);
        assertThat(r.notes().get(Slot.ANCHORS)).contains("장소11 · " + GeneratorSupport.PLACES_FULL);
        assertThat(r.request().description()).contains("가고 싶은 곳: 장소11");
    }

    @Test
    @DisplayName("이름으로 찾지 못한 꼭 갈 곳은 지역 낱말과 시간대를 필수 경험으로 넘긴다")
    void unmatchedMustVisitBecomesRequiredExperience() {
        Events e = Events.busanTrip().clearSlot(Slot.ANCHORS)
                .user(DraftField.MUST_VISIT, "광안리 야경")
                .user(DraftField.MUST_VISIT, "해운대 일출")
                .user(DraftField.MUST_VISIT, "부산 야경")
                .user(DraftField.MUST_VISIT, "야시장")
                .user(DraftField.MUST_VISIT, "블루라인파크");
        Map<String, PlaceMatch> matches = Map.of(PlaceMatcher.key(DraftField.MUST_VISIT, "블루라인파크"),
                new PlaceMatch(9L, "해운대블루라인파크", "SIGHT"));

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY, Set.of());

        assertThat(r.request().requiredExperiences()).containsExactly(
                new ItineraryGenerateRequest.RequiredExperience("광안리 야경", "광안리", "evening"),
                new ItineraryGenerateRequest.RequiredExperience("해운대 일출", "해운대", "morning"));
        assertThat(r.request().selectedPlaceIds()).containsExactly(9L);
        assertThat(r.request().description()).contains("가고 싶은 곳: 광안리 야경, 해운대 일출, 부산 야경, 야시장");
        assertThat(r.notes().get(Slot.ANCHORS)).contains("광안리 야경 · " + GeneratorSupport.PLACE_BY_AREA,
                "부산 야경 · " + GeneratorSupport.PLACE_REFERENCE, "야시장 · " + GeneratorSupport.PLACE_REFERENCE);
        assertThat(VALIDATOR.validate(r.request())).isEmpty();
        assertThat(map(Events.busanTrip()).request().requiredExperiences()).isNull();
    }

    @Test
    @DisplayName("지역 낱말은 시간대 낱말·조사·여행지 이름·종류만 있는 낱말을 뺀 나머지다")
    void areaKeywordSkipsTimingDestinationAndGenericWords() {
        assertThat(TripRequestMapper.areaKeyword("광안리야경", "부산")).isEqualTo("광안리");
        assertThat(TripRequestMapper.areaKeyword("광안리에서 야경", "부산")).isEqualTo("광안리");
        assertThat(TripRequestMapper.areaKeyword("부산 감천문화마을", "부산광역시")).isEqualTo("감천문화마을");
        assertThat(TripRequestMapper.areaKeyword("해운대 블루라인파크 야경 꼭", "부산")).isEqualTo("해운대 블루라인파크");
        assertThat(TripRequestMapper.areaKeyword("부산 야경", "부산, 경주")).isNull();
        assertThat(TripRequestMapper.areaKeyword("제주도 노을", "제주")).isNull();
        assertThat(TripRequestMapper.areaKeyword("밤바다", "여수")).isNull();
    }

    @Test
    @DisplayName("시간대 낱말은 낱말 끝에 올 때만 본다. 이름 앞의 낱말(아침고요수목원)은 시간대가 아니다")
    void timingOnlyFromWordEnd() {
        assertThat(GeneratorSupport.experienceTiming("광안리야경")).isEqualTo("evening");
        assertThat(GeneratorSupport.experienceTiming("해운대 일출")).isEqualTo("morning");
        assertThat(GeneratorSupport.experienceTiming("일출 야경")).isNull();
        assertThat(GeneratorSupport.experienceTiming("아침고요수목원")).isNull();
        assertThat(TripRequestMapper.areaKeyword("아침고요수목원", "가평")).isEqualTo("아침고요수목원");
    }

    @Test
    @DisplayName("하고 싶은 것이 많으면 최근 바람부터 상한까지 말한 순서대로 싣고, 줄였다고 알린다")
    void prioritiesTextIsCapped() {
        Events e = Events.busanTrip();
        for (int i = 0; i < 20; i++) e.user(DraftField.PRIORITIES, "바다 앞 카페에서 오래 쉬기 " + i);

        TripRequestMapper.Result r = map(e);
        String wishes = r.request().description().split(" / ")[0];

        assertThat(wishes).doesNotContain("바다 보면서 느긋하게 쉬기").endsWith("오래 쉬기 19");
        assertThat(wishes.indexOf("쉬기 18")).isLessThan(wishes.indexOf("쉬기 19"));
        assertThat(wishes.length() - "원하는 여행: ".length()).isLessThanOrEqualTo(TripRequestMapper.MAX_PRIORITIES_TEXT);
        assertThat(r.notes().get(Slot.MOOD)).contains(GeneratorSupport.PRIORITIES_TRIMMED);
    }

    @Test
    @DisplayName("꼭 넣기로 해 빼지 않는 피할 장소는 이름 목록에 넣지 않는다")
    void keptAvoidPlaceIsNotListed() {
        Events e = Events.busanTrip().clearSlot(Slot.ANCHORS)
                .user(DraftField.MUST_VISIT, "해운대해수욕장")
                .user(DraftField.AVOID_PLACES, "해운대");
        Map<String, PlaceMatch> matches = Map.of(
                PlaceMatcher.key(DraftField.MUST_VISIT, "해운대해수욕장"), new PlaceMatch(5L, "해운대해수욕장", "ATTRACTION"),
                PlaceMatcher.key(DraftField.AVOID_PLACES, "해운대"), new PlaceMatch(5L, "해운대해수욕장", "ATTRACTION"));

        TripRequestMapper.Result r = TripRequestMapper.map(e.draft(), matches, TODAY, Set.of());

        assertThat(r.request().avoidPlaceNames()).isNull();
        assertThat(r.request().selectedPlaceIds()).containsExactly(5L);
    }
}
