package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HardValidatorTest {

    private HardValidator validator;

    @BeforeEach
    void setUp() {
        validator = new HardValidator();
    }

    private StepData step(int stepOrder, int dayNumber, String startTime, String endTime, String placeName) {
        PlaceData place = placeName != null ? new PlaceData(placeName, null, "Tour", "Region", "Country") : null;
        return new StepData(
                stepOrder, dayNumber, startTime, endTime,
                place, List.of(),
                "WALK", 10, null, null, null, null
        );
    }

    private ItineraryData itinerary(List<StepData> steps) {
        return new ItineraryData("Test Trip", "Tokyo", BigDecimal.valueOf(100000), List.of("culture"), steps);
    }

    @Test
    @DisplayName("유효한 일정은 valid=true를 반환한다")
    void validate_validItinerary_passes() {
        List<StepData> steps = List.of(
                step(1, 1, "09:00", "11:00", "센소지"),
                step(2, 1, "12:00", "13:30", "라멘가게"),
                step(3, 2, "09:30", "11:30", "메이지신궁")
        );

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("null ItineraryData는 실패한다")
    void validate_nullData_fails() {
        HardValidationResult result = validator.validate(null);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("null");
    }

    @Test
    @DisplayName("빈 steps는 실패한다")
    void validate_emptySteps_fails() {
        HardValidationResult result = validator.validate(itinerary(List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("비어 있습니다");
    }

    @Test
    @DisplayName("null steps는 실패한다")
    void validate_nullSteps_fails() {
        ItineraryData data = new ItineraryData("Trip", "Tokyo", null, List.of(), null);

        HardValidationResult result = validator.validate(data);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("비어 있습니다");
    }

    @Test
    @DisplayName("place가 null이면 실패한다")
    void validate_nullPlace_fails() {
        List<StepData> steps = List.of(step(1, 1, "09:00", "11:00", null));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("place가 null");
    }

    @Test
    @DisplayName("place.name이 빈 문자열이면 실패한다")
    void validate_blankPlaceName_fails() {
        List<StepData> steps = List.of(step(1, 1, "09:00", "11:00", "   "));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("place.name이 비어 있습니다");
    }

    @Test
    @DisplayName("startTime이 null이면 실패한다")
    void validate_nullStartTime_fails() {
        List<StepData> steps = List.of(step(1, 1, null, "11:00", "센소지"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("startTime이 null");
    }

    @Test
    @DisplayName("endTime이 null이면 실패한다")
    void validate_nullEndTime_fails() {
        List<StepData> steps = List.of(step(1, 1, "09:00", null, "센소지"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("endTime이 null");
    }

    @Test
    @DisplayName("잘못된 시간 형식은 실패한다 (24:00)")
    void validate_invalidTimeFormat_24hour_fails() {
        List<StepData> steps = List.of(step(1, 1, "24:00", "25:00", "센소지"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("형식이 올바르지 않습니다");
    }

    @Test
    @DisplayName("잘못된 시간 형식은 실패한다 (9:00)")
    void validate_invalidTimeFormat_singleDigitHour_fails() {
        List<StepData> steps = List.of(step(1, 1, "9:00", "11:00", "센소지"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("형식이 올바르지 않습니다");
    }

    @Test
    @DisplayName("endTime이 startTime보다 이르면 실패한다")
    void validate_endTimeBeforeStartTime_fails() {
        List<StepData> steps = List.of(step(1, 1, "14:00", "11:00", "센소지"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("endTime(11:00)이 startTime(14:00)보다 같거나 이릅니다");
    }

    @Test
    @DisplayName("endTime이 startTime과 같으면 실패한다")
    void validate_endTimeEqualsStartTime_fails() {
        List<StepData> steps = List.of(step(1, 1, "10:00", "10:00", "센소지"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("같거나 이릅니다");
    }

    @Test
    @DisplayName("stepOrder가 1에서 시작하지 않으면 실패한다")
    void validate_stepOrderNotStartingAt1_fails() {
        List<StepData> steps = List.of(step(2, 1, "09:00", "11:00", "센소지"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("stepOrder가 2이지만 1이어야 합니다");
    }

    @Test
    @DisplayName("stepOrder에 갭이 있으면 실패한다")
    void validate_stepOrderGap_fails() {
        List<StepData> steps = List.of(
                step(1, 1, "09:00", "11:00", "센소지"),
                step(3, 1, "12:00", "14:00", "라멘가게")
        );

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("stepOrder가 3이지만 2이어야 합니다");
    }

    @Test
    @DisplayName("dayNumber가 감소하면 실패한다")
    void validate_dayNumberDecreasing_fails() {
        List<StepData> steps = List.of(
                step(1, 2, "09:00", "11:00", "센소지"),
                step(2, 1, "12:00", "14:00", "라멘가게")
        );

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("dayNumber(1)가 이전 step의 dayNumber(2)보다 작습니다");
    }

    @Test
    @DisplayName("dayNumber가 같은 값이 연속되면 통과한다 (비감소)")
    void validate_dayNumberSameValue_passes() {
        List<StepData> steps = List.of(
                step(1, 1, "09:00", "11:00", "센소지"),
                step(2, 1, "12:00", "14:00", "라멘가게"),
                step(3, 1, "15:00", "17:00", "시부야")
        );

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("여러 에러가 동시에 발생하면 모두 포함된다")
    void validate_multipleErrors_allReported() {
        StepData badStep = new StepData(
                5, 1, "invalid", null,
                null, List.of(),
                "WALK", null, null, null, null, null
        );
        List<StepData> steps = List.of(badStep);

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("place가 null");
        assertThat(result.failureReason()).contains("endTime이 null");
        assertThat(result.failureReason()).contains("startTime 형식이 올바르지 않습니다");
        assertThat(result.failureReason()).contains("stepOrder가 5이지만 1이어야 합니다");
    }

    @Test
    @DisplayName("경계값: 23:59 유효 시간")
    void validate_boundaryTime_2359_passes() {
        List<StepData> steps = List.of(step(1, 1, "23:00", "23:59", "야경명소"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("경계값: 00:00 유효 시간")
    void validate_boundaryTime_0000_passes() {
        List<StepData> steps = List.of(step(1, 1, "00:00", "01:00", "새벽명소"));

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("다일 여행 정상 케이스: dayNumber 1→1→2→2→3 통과")
    void validate_multiDayTrip_passes() {
        List<StepData> steps = List.of(
                step(1, 1, "09:00", "11:00", "장소A"),
                step(2, 1, "12:00", "14:00", "장소B"),
                step(3, 2, "09:00", "11:00", "장소C"),
                step(4, 2, "12:00", "14:00", "장소D"),
                step(5, 3, "10:00", "12:00", "장소E")
        );

        HardValidationResult result = validator.validate(itinerary(steps));

        assertThat(result.valid()).isTrue();
    }
}
