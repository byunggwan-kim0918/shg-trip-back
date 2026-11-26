package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class QualityGateTest {

    private static final String BEACH = "Landmarks and Outdoors > Beach";
    private static final String TEMPLE = "Landmarks and Outdoors > Temple";
    private static final String KOREAN = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String CHINESE = "Dining and Drinking > Restaurant > Asian Restaurant > Chinese Restaurant";
    private static final String LICENSE_NOTICE = "운전면허 안내";

    // 운영 ObjectMapper(Spring Boot 기본값)와 같은 설정이다.
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final QualityGate gate = new QualityGate(objectMapper, mock(PlaceFactsReader.class));

    @Test
    @DisplayName("기준을 채운 일정은 판정 입력과 고정 안내를 남긴다")
    void admitsGoodItinerary() throws Exception {
        Itinerary itinerary = itinerary(fullDay(), null);

        gate.admit(itinerary, context(1));

        assertThat(itinerary.getQualityNotices()).containsExactly(LICENSE_NOTICE);
        QualityGate.PlanContext stored = objectMapper.readValue(itinerary.getPlanContext(), QualityGate.PlanContext.class);
        assertThat(stored.rules().dayCount()).isEqualTo(1);
        assertThat(stored.rules().requiredExperiences()).containsExactly(Map.entry(1L, "해운대해수욕장"));
        assertThat(stored.fixedNotices()).containsExactly(LICENSE_NOTICE);
    }

    @Test
    @DisplayName("저장한 판정 입력은 들를 지역·밤마다 묵을 도시·지역별 이동을 그대로 되읽어 편집 재판정이 같은 조건을 본다")
    void planContextKeepsLayout() throws Exception {
        TripConditions.Area sapporo = new TripConditions.Area("삿포로", 43.06, 141.35, 20, 25);
        TripConditions.Area furano = new TripConditions.Area("후라노", 43.34, 142.38, 12, 25);
        TripConditions.Layout layout = new TripConditions.Layout(List.of(sapporo, furano), Map.of(1, sapporo, 3, furano),
                List.of(new TripConditions.AreaMode(furano, "car"), new TripConditions.AreaMode(sapporo, "transit")));
        TripConditions conditions = new TripConditions(List.of(), Map.of(), List.of(), false, "any", null, null,
                "normal", java.util.Set.of(7L), layout);
        QualityGate.PlanContext ctx = new QualityGate.PlanContext(new ItineraryRules.Context("normal", "any",
                LocalDate.of(2027, 7, 15), 5, null, Map.of(), conditions, "JP", List.of()), List.of());

        QualityGate.PlanContext back = objectMapper.readValue(objectMapper.writeValueAsString(ctx),
                QualityGate.PlanContext.class);

        assertThat(back.rules().conditions().layout()).isEqualTo(layout);
        assertThat(back.rules().conditions().chosenPlaceIds()).containsExactly(7L);
        assertThat(back.rules().conditions().layout().modeAt(new double[]{43.35, 142.39}, "any")).isEqualTo("car");
    }

    @Test
    @DisplayName("덜 좋은 일정은 저장하고 고칠 수 있는 일차 안내를 고정 안내 앞에 둔다")
    void savesSoftViolationWithNotice() {
        Itinerary itinerary = itinerary(withoutLunch(), null);

        gate.admit(itinerary, context(1));

        assertThat(itinerary.getQualityNotices()).containsExactly(
                "1일차에 점심 식사가 없어요.", LICENSE_NOTICE);
    }

    @Test
    @DisplayName("쓸 수 없는 일정(날짜 누락)은 판정을 실어 거부한다")
    void rejectsHardViolation() {
        Itinerary itinerary = itinerary(fullDay(), null);

        assertThatThrownBy(() -> gate.admit(itinerary, context(2)))
                .isInstanceOfSatisfying(QualityGate.Rejected.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUALITY_GATE_FAILED);
                    assertThat(e.userMessage()).isEqualTo(
                            "2일차 일정을 채우지 못해 저장하지 않았어요. 다시 만들어 주세요. 30일 생성 횟수는 차감되지 않았어요.");
                    assertThat(e.verdict().violations()).extracting(ItineraryRules.Violation::rule)
                            .contains(ItineraryRules.Rule.DAY_MISSING);
                });
    }

    @Test
    @DisplayName("출발 시각을 준 여행의 마지막 날이 비어 거부되면 출발 시각을 확인하라고 안내한다")
    void rejectionUsesDepartureContext() {
        QualityGate.PlanContext ctx = new QualityGate.PlanContext(new ItineraryRules.Context("normal", "car",
                LocalDate.of(2026, 11, 20), 2, 6 * 60), List.of());

        assertThatThrownBy(() -> gate.admit(itinerary(fullDay(), null), ctx))
                .isInstanceOfSatisfying(QualityGate.Rejected.class, e -> assertThat(e.userMessage()).isEqualTo(
                        "마지막 날 출발 시각이 일러 그날 일정을 넣지 못해 저장하지 않았어요. 출발 시간을 확인한 뒤 다시 만들어 주세요."
                                + " 30일 생성 횟수는 차감되지 않았어요."));
    }

    @Test
    @DisplayName("편집하면 안내를 지금 일정 기준으로 다시 만든다")
    void editRebuildsNotices() throws Exception {
        String ctx = objectMapper.writeValueAsString(context(1));
        Itinerary fixed = itinerary(fullDay(), ctx);
        Itinerary broken = itinerary(withoutLunch(), ctx);

        gate.reflectEdit(fixed);
        gate.reflectEdit(broken);

        assertThat(fixed.getQualityNotices()).containsExactly(LICENSE_NOTICE);
        assertThat(broken.getQualityNotices()).contains("1일차에 점심 식사가 없어요.");
    }

    @Test
    @DisplayName("판정 입력이 없는 기존 일정은 편집 뒤에도 생성 때 안내를 그대로 둔다")
    void legacyItineraryIsNotRejudged() {
        Itinerary itinerary = itinerary(withoutLunch(), null);

        gate.reflectEdit(itinerary);

        assertThat(itinerary.getQualityNotices()).containsExactly("생성 때 안내");
    }

    @Test
    @DisplayName("판정 밖의 거부 문구는 ErrorCode 기본 문구와 같고, 한도에 닿으면 다시 만들라는 말 대신 한도 안내를 쓴다")
    void rejectionMessageMatchesDefault() {
        assertThat(QualityGate.rejectionMessage(null, null)).isEqualTo(ErrorCode.QUALITY_GATE_FAILED.getMessage());
        assertThat(QualityGate.rejectionMessage(new QualityNotices.Rejection("원인.", "할 일."), "한도 안내."))
                .isEqualTo("원인. 한도 안내. 30일 생성 횟수는 차감되지 않았어요.");
    }

    @Test
    @DisplayName("판정이 예외로 실패하면 생성 때 안내를 그대로 두고 저장하며, 편집은 판정 없이 그대로 반영한다")
    void evaluationFailureKeepsGeneratedNotices() throws Exception {
        QualityGate.PlanContext broken = new QualityGate.PlanContext(null, List.of(LICENSE_NOTICE));
        Itinerary itinerary = itinerary(withoutLunch(), null);

        assertThat(gate.admit(itinerary, broken)).isNull();
        assertThat(itinerary.getQualityNotices()).containsExactly("생성 때 안내");
        assertThat(itinerary.getPlanContext()).isEqualTo(objectMapper.writeValueAsString(broken));

        Itinerary edited = itinerary(withoutLunch(), objectMapper.writeValueAsString(broken));
        gate.reflectEdit(edited);
        assertThat(edited.getQualityNotices()).containsExactly("생성 때 안내");
    }

    @Test
    @DisplayName("이미 저장된 판정 입력 JSON은 없어진 후보 풀 한계를 건너뛰고 지금 형식으로 읽는다")
    void storedPlanContextStaysReadable() throws Exception {
        String stored = """
                {"rules":{"pace":"normal","transportPref":"car","startDate":"2026-11-20","dayCount":1,
                "lastDayHubDeadline":null,"requiredExperiences":{"1":"해운대해수욕장"}},"fixedNotices":["운전면허 안내"],
                "poolLimits":[{"rule":"REQUIRED_EXPERIENCE_MISSING","day":1,"evidence":[]}]}""";

        assertThat(objectMapper.readValue(stored, QualityGate.PlanContext.class)).isEqualTo(context(1));
    }

    @Test
    @DisplayName("여행 조건을 담은 판정 입력은 저장 뒤 같은 값으로 읽힌다")
    void conditionsRoundTrip() throws Exception {
        TripConditions conditions = new TripConditions(
                List.of(new TripConditions.Want("광안리 야경", "evening", List.of("광안리해수욕장"), null)),
                Map.of("부산", 2), List.of(PlaceRestriction.SEAFOOD), true, "transit", LocalTime.of(10, 30),
                LocalTime.of(18, 0), "normal");
        QualityGate.PlanContext ctx = new QualityGate.PlanContext(new ItineraryRules.Context("normal", "any",
                LocalDate.of(2026, 11, 20), 2, 17 * 60 + 40, Map.of(), conditions, "KR",
                List.of(new ItineraryRules.City("부산", 35.18, 129.08))), List.of(LICENSE_NOTICE));

        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(ctx), QualityGate.PlanContext.class))
                .isEqualTo(ctx);
    }

    @Test
    @DisplayName("대화에서 그대로 두기로 한 요구는 저장하고 편집 뒤에도 같은 상태로 다시 판정하며, 동의 없이 못 넣은 꼭 갈 곳은 저장하지 않는다")
    void requirementStateSurvivesSaveAndEdit() throws Exception {
        TripConditions.Want kept = new TripConditions.Want("캐널시티 하카타", null, List.of(), null,
                TripConditions.Reach.NOT_FOUND, true, true);
        QualityGate.PlanContext accepted = conditionsContext(kept);
        Itinerary saved = itinerary(legged(fullDay()), null);

        gate.admit(saved, accepted);

        QualityGate.PlanContext stored = objectMapper.readValue(saved.getPlanContext(), QualityGate.PlanContext.class);
        assertThat(stored.rules().conditions().wants()).containsExactly(kept);
        Itinerary edited = itinerary(legged(withoutLunch()), saved.getPlanContext());
        gate.reflectEdit(edited);
        assertThat(edited.getQualityNotices()).contains(LICENSE_NOTICE)
                .noneMatch(n -> n.contains("캐널시티 하카타"));

        TripConditions.Want unasked = new TripConditions.Want("나카스 야타이", null, List.of(), null,
                TripConditions.Reach.NOT_FOUND, true, false);
        assertThatThrownBy(() -> gate.admit(itinerary(legged(fullDay()), null), conditionsContext(unasked)))
                .isInstanceOfSatisfying(QualityGate.Rejected.class, e -> assertThat(e.userMessage()).startsWith(
                        "꼭 가고 싶다고 한 '나카스 야타이'을(를) 장소 데이터에서 찾지 못해 저장하지 않았어요."));
    }

    @Test
    @DisplayName("판정 입력의 제약 이름을 모르면 그 제약만 빼고 편집 뒤에도 다시 판정한다")
    void unknownRestrictionKeepsRejudging() throws Exception {
        String stored = """
                {"rules":{"pace":"normal","transportPref":"car","startDate":"2026-11-20","dayCount":1,
                "lastDayHubDeadline":null,"requiredExperiences":{},"conditions":{"wants":[],"cityDays":{},
                "avoid":["NO_SUCH_RESTRICTION"],"lowMobility":false,"transport":"car","arrival":null,"departure":null,
                "pace":"normal"},"country":"KR","cities":[]},"fixedNotices":["운전면허 안내"]}""";
        Itinerary itinerary = itinerary(withoutLunch(), stored);

        gate.reflectEdit(itinerary);

        assertThat(itinerary.getQualityNotices()).contains("1일차에 점심 식사가 없어요.", LICENSE_NOTICE);
    }

    @Test
    @DisplayName("옛 판정 입력의 필수 경험 장소가 편집으로 빠지면 판정 입력의 이름으로 안내한다")
    void missingRequiredPlaceKeepsItsName() throws Exception {
        QualityGate.PlanContext ctx = new QualityGate.PlanContext(new ItineraryRules.Context("normal", "car",
                LocalDate.of(2026, 11, 20), 1, null, Map.of(1L, "해운대해수욕장", 99L, "광안리해수욕장")), List.of());
        Itinerary itinerary = itinerary(fullDay(), objectMapper.writeValueAsString(ctx));

        gate.reflectEdit(itinerary);

        assertThat(itinerary.getQualityNotices())
                .containsExactly("꼭 가고 싶다고 한 '광안리해수욕장'을(를) 일정에 넣지 못했어요.");
    }

    @Test
    @DisplayName("교체 미리보기의 안내는 실제로 바꾼 뒤 편집 재판정과 같다")
    void swapPreviewMatchesEdit() throws Exception {
        String ctx = objectMapper.writeValueAsString(context(1));
        List<ItineraryStep> steps = fullDay();
        steps.set(3, step(4, "18:00", "19:00", place(4, "초당순두부", KOREAN, "MEAL")));
        Itinerary itinerary = itinerary(steps, ctx);
        ItineraryStep dinner = steps.get(3);
        Place noodle = place(9, "짬뽕지존", CHINESE, "MEAL");

        QualityGate.SwapPreview preview = gate.previewSwap(itinerary, dinner, noodle, null, null, null);
        dinner.addAlternative(AlternativeOption.builder().id(5L).place(noodle).optionOrder(1).build());
        dinner.selectAlternative(dinner.getAlternatives().get(0));
        gate.reflectEdit(itinerary);

        assertThat(preview.notices()).isEqualTo(itinerary.getQualityNotices());
    }

    @Test
    @DisplayName("교체 판정은 바뀐 스텝으로 오는 구간과 다음 스텝으로 가는 구간을 넘긴 값으로 본다")
    void swapVerdictUsesGivenLegs() {
        List<ItineraryStep> steps = fullDay();
        Itinerary itinerary = itinerary(steps, null);
        ItineraryRules.Context ctx = context(1).rules();
        Place other = place(9, "범어사", TEMPLE, "SIGHT");
        GeoUtils.TransportLeg near = new GeoUtils.TransportLeg("CAR", 10, BigDecimal.valueOf(5), BigDecimal.valueOf(1500));
        GeoUtils.TransportLeg far = new GeoUtils.TransportLeg("CAR", 90, BigDecimal.valueOf(80), BigDecimal.valueOf(24000));

        assertThat(legOverCap(SavedItineraryFacts.evaluateSwap(itinerary, steps.get(2), other, far, steps.get(3), near,
                ctx, Map.of()))).isTrue();
        assertThat(legOverCap(SavedItineraryFacts.evaluateSwap(itinerary, steps.get(2), other, near, steps.get(3), far,
                ctx, Map.of()))).isTrue();
        assertThat(legOverCap(SavedItineraryFacts.evaluateSwap(itinerary, steps.get(2), other, near, steps.get(3), near,
                ctx, Map.of()))).isFalse();
    }

    private static boolean legOverCap(ItineraryRules.Verdict verdict) {
        return verdict.violations().stream().anyMatch(v -> v.rule() == ItineraryRules.Rule.LEG_OVER_CAP);
    }

    @Test
    @DisplayName("판정 입력이 없는 일정은 교체 미리보기 판정을 하지 않는다")
    void swapPreviewNeedsPlanContext() {
        Itinerary itinerary = itinerary(fullDay(), null);

        assertThat(gate.previewSwap(itinerary, itinerary.getSteps().get(0), place(9, "다른 해변", BEACH, "SIGHT"),
                null, null, null)).isNull();
    }

    private static QualityGate.PlanContext context(int days) {
        return new QualityGate.PlanContext(new ItineraryRules.Context("normal", "car", LocalDate.of(2026, 11, 20),
                days, null, Map.of(1L, "해운대해수욕장")), List.of(LICENSE_NOTICE));
    }

    private static QualityGate.PlanContext conditionsContext(TripConditions.Want want) {
        TripConditions conditions = new TripConditions(List.of(want), Map.of(), List.of(), false, "car", null, null,
                "normal");
        return new QualityGate.PlanContext(new ItineraryRules.Context("normal", "car", LocalDate.of(2026, 11, 20), 1,
                null, Map.of(), conditions, "KR", List.of()), List.of(LICENSE_NOTICE));
    }

    /** 여행 조건이 있는 판정은 첫 스텝 뒤 모든 구간에 이동 시간을 요구한다. */
    private static List<ItineraryStep> legged(List<ItineraryStep> steps) {
        List<ItineraryStep> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ItineraryStep s = steps.get(i);
            out.add(ItineraryStep.builder().id(s.getId()).dayNumber(1).stepOrder(s.getStepOrder())
                    .startTime(s.getStartTime()).endTime(s.getEndTime()).place(s.getPlace())
                    .transportationMode(i == 0 ? null : "CAR").transportationDuration(i == 0 ? null : 15).build());
        }
        return out;
    }

    private static List<ItineraryStep> fullDay() {
        return new ArrayList<>(List.of(
                step(1, "09:00", "10:30", place(1, "해운대해수욕장", BEACH, "SIGHT")),
                step(2, "12:00", "13:00", place(2, "토속순두부", KOREAN, "MEAL")),
                step(3, "14:00", "16:00", place(3, "해동용궁사", TEMPLE, "SIGHT")),
                step(4, "18:00", "19:00", place(4, "짬뽕지존", CHINESE, "MEAL"))));
    }

    private static List<ItineraryStep> withoutLunch() {
        List<ItineraryStep> steps = fullDay();
        steps.remove(1);
        return steps;
    }

    private static Itinerary itinerary(List<ItineraryStep> steps, String planContext) {
        return Itinerary.builder().id(10L).userId(1L).title("부산 당일").destination("부산")
                .startDate(LocalDate.of(2026, 11, 20)).endDate(LocalDate.of(2026, 11, 20))
                .planContext(planContext).qualityNotices(List.of("생성 때 안내"))
                .steps(steps).build();
    }

    private static ItineraryStep step(int order, String start, String end, Place place) {
        return ItineraryStep.builder().id((long) order).dayNumber(1).stepOrder(order)
                .startTime(start).endTime(end).place(place).build();
    }

    private static Place place(long id, String name, String category, String role) {
        return Place.builder().id(id).name(name).address("부산 해운대구 테스트로 " + id).category(category)
                .region("Busan").country("KR").placeRole(role)
                .latitude(BigDecimal.valueOf(35.16)).longitude(BigDecimal.valueOf(129.16)).build();
    }
}
