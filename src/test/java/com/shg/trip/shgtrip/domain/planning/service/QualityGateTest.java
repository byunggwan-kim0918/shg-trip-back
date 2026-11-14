package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary.QualityStatus;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityGateTest {

    private static final String BEACH = "Landmarks and Outdoors > Beach";
    private static final String TEMPLE = "Landmarks and Outdoors > Temple";
    private static final String KOREAN = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";
    private static final String CHINESE = "Dining and Drinking > Restaurant > Asian Restaurant > Chinese Restaurant";
    private static final String LICENSE_NOTICE = "운전면허 안내";

    // 운영 ObjectMapper(Spring Boot 기본값)와 같은 설정이다.
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final QualityGate gate = new QualityGate(objectMapper);

    @Test
    @DisplayName("기준을 채운 일정은 OK로 두고 판정 입력과 고정 안내를 남긴다")
    void admitsGoodItineraryAsOk() throws Exception {
        Itinerary itinerary = itinerary(fullDay(), QualityStatus.OK, null);

        gate.admit(itinerary, context(1));

        assertThat(itinerary.getQualityStatus()).isEqualTo(QualityStatus.OK);
        assertThat(itinerary.getQualityNotices()).containsExactly(LICENSE_NOTICE);
        QualityGate.PlanContext stored = objectMapper.readValue(itinerary.getPlanContext(), QualityGate.PlanContext.class);
        assertThat(stored.rules().dayCount()).isEqualTo(1);
        assertThat(stored.rules().requiredExperiences()).containsExactly(Map.entry(1L, "해운대해수욕장"));
        assertThat(stored.fixedNotices()).containsExactly(LICENSE_NOTICE);
    }

    @Test
    @DisplayName("핵심 품질에 못 미치면 조정 필요로 저장하고 일차 안내를 앞에 둔다")
    void marksCoreViolationNeedsAdjustment() {
        Itinerary itinerary = itinerary(withoutLunch(), QualityStatus.OK, null);

        gate.admit(itinerary, context(1));

        assertThat(itinerary.getQualityStatus()).isEqualTo(QualityStatus.NEEDS_ADJUSTMENT);
        assertThat(itinerary.getQualityNotices()).containsExactly(
                "1일차에 점심 식사가 없어요.", LICENSE_NOTICE);
    }

    @Test
    @DisplayName("쓸 수 없는 일정(날짜 누락)은 판정을 실어 거부한다")
    void rejectsHardViolation() {
        Itinerary itinerary = itinerary(fullDay(), QualityStatus.OK, null);

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

        assertThatThrownBy(() -> gate.admit(itinerary(fullDay(), QualityStatus.OK, null), ctx))
                .isInstanceOfSatisfying(QualityGate.Rejected.class, e -> assertThat(e.userMessage()).isEqualTo(
                        "마지막 날 출발 시각이 일러 그날 일정을 넣지 못해 저장하지 않았어요. 출발 시간을 확인한 뒤 다시 만들어 주세요."
                                + " 30일 생성 횟수는 차감되지 않았어요."));
    }

    @Test
    @DisplayName("편집으로 기준을 채우면 조정 필요가 풀리고 안내도 지금 일정 기준으로 바뀐다")
    void editClearsAdjustment() throws Exception {
        String ctx = objectMapper.writeValueAsString(context(1));
        Itinerary itinerary = itinerary(fullDay(), QualityStatus.NEEDS_ADJUSTMENT, ctx);

        gate.reflectEdit(itinerary);

        assertThat(itinerary.getQualityStatus()).isEqualTo(QualityStatus.OK);
        assertThat(itinerary.getQualityNotices()).containsExactly(LICENSE_NOTICE);
    }

    @Test
    @DisplayName("편집으로 기준이 깨져도 조정 필요를 새로 걸지 않고 안내만 갱신한다")
    void editNeverSetsAdjustment() throws Exception {
        String ctx = objectMapper.writeValueAsString(context(1));
        Itinerary itinerary = itinerary(withoutLunch(), QualityStatus.OK, ctx);

        gate.reflectEdit(itinerary);

        assertThat(itinerary.getQualityStatus()).isEqualTo(QualityStatus.OK);
        assertThat(itinerary.getQualityNotices())
                .contains("1일차에 점심 식사가 없어요.");
    }

    @Test
    @DisplayName("판정 입력이 없는 기존 일정은 편집 뒤에도 생성 때 안내를 그대로 둔다")
    void legacyItineraryIsNotRejudged() {
        Itinerary itinerary = itinerary(withoutLunch(), QualityStatus.OK, null);

        gate.reflectEdit(itinerary);

        assertThat(itinerary.getQualityNotices()).containsExactly("생성 때 안내");
        assertThat(itinerary.getQualityStatus()).isEqualTo(QualityStatus.OK);
    }

    @Test
    @DisplayName("판정 밖의 거부 문구는 ErrorCode 기본 문구와 같고, 한도에 닿으면 다시 만들라는 말 대신 한도 안내를 쓴다")
    void rejectionMessageMatchesDefault() {
        assertThat(QualityGate.rejectionMessage(null, null)).isEqualTo(ErrorCode.QUALITY_GATE_FAILED.getMessage());
        assertThat(QualityGate.rejectionMessage(new QualityNotices.Rejection("원인.", "할 일."), "한도 안내."))
                .isEqualTo("원인. 한도 안내. 30일 생성 횟수는 차감되지 않았어요.");
    }

    @Test
    @DisplayName("판정이 예외로 실패하면 생성 때 안내를 그대로 두고 OK로 저장하며, 편집은 판정 없이 그대로 반영한다")
    void evaluationFailureKeepsGeneratedNotices() throws Exception {
        QualityGate.PlanContext broken = new QualityGate.PlanContext(null, List.of(LICENSE_NOTICE));
        Itinerary itinerary = itinerary(withoutLunch(), QualityStatus.OK, null);

        assertThat(gate.admit(itinerary, broken)).isNull();
        assertThat(itinerary.getQualityStatus()).isEqualTo(QualityStatus.OK);
        assertThat(itinerary.getQualityNotices()).containsExactly("생성 때 안내");
        assertThat(itinerary.getPlanContext()).isEqualTo(objectMapper.writeValueAsString(broken));

        Itinerary edited = itinerary(withoutLunch(), QualityStatus.NEEDS_ADJUSTMENT, objectMapper.writeValueAsString(broken));
        gate.reflectEdit(edited);
        assertThat(edited.getQualityStatus()).isEqualTo(QualityStatus.NEEDS_ADJUSTMENT);
        assertThat(edited.getQualityNotices()).containsExactly("생성 때 안내");
    }

    @Test
    @DisplayName("이미 저장된 판정 입력 JSON을 지금 형식으로 그대로 읽는다")
    void storedPlanContextStaysReadable() throws Exception {
        String stored = """
                {"rules":{"pace":"normal","transportPref":"car","startDate":"2026-11-20","dayCount":1,
                "lastDayHubDeadline":null,"requiredExperiences":{"1":"해운대해수욕장"}},"fixedNotices":["운전면허 안내"]}""";

        assertThat(objectMapper.readValue(stored, QualityGate.PlanContext.class)).isEqualTo(context(1));
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(context(1))))
                .isEqualTo(objectMapper.readTree(stored));
    }

    @Test
    @DisplayName("일정에서 빠진 필수 경험 장소는 저장·편집 뒤에도 판정 입력의 이름으로 안내한다")
    void missingRequiredPlaceKeepsItsName() {
        QualityGate.PlanContext ctx = new QualityGate.PlanContext(new ItineraryRules.Context("normal", "car",
                LocalDate.of(2026, 11, 20), 1, null, Map.of(1L, "해운대해수욕장", 99L, "광안리해수욕장")), List.of());
        Itinerary itinerary = itinerary(fullDay(), QualityStatus.OK, null);
        String expected = "꼭 가고 싶다고 한 '광안리해수욕장'을(를) 일정에 넣지 못했어요.";

        gate.admit(itinerary, ctx);
        assertThat(itinerary.getQualityStatus()).isEqualTo(QualityStatus.NEEDS_ADJUSTMENT);
        assertThat(itinerary.getQualityNotices()).containsExactly(expected);

        gate.reflectEdit(itinerary);
        assertThat(itinerary.getQualityNotices()).containsExactly(expected);
    }

    private static QualityGate.PlanContext context(int days) {
        return new QualityGate.PlanContext(new ItineraryRules.Context("normal", "car", LocalDate.of(2026, 11, 20),
                days, null, Map.of(1L, "해운대해수욕장")), List.of(LICENSE_NOTICE));
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

    private static Itinerary itinerary(List<ItineraryStep> steps, QualityStatus status, String planContext) {
        return Itinerary.builder().id(10L).userId(1L).title("부산 당일").destination("부산")
                .startDate(LocalDate.of(2026, 11, 20)).endDate(LocalDate.of(2026, 11, 20))
                .qualityStatus(status).planContext(planContext).qualityNotices(List.of("생성 때 안내"))
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
