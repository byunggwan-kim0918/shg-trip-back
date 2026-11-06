package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.HardValidationResult;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 벡터 경로 전용 hard validation.
 * soft validation(Haiku 품질 평가) 없이 구조적 정합성만 검증한다.
 */
@Slf4j
@Component
public class HardValidator {

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    // 한 destination 안에서 비현실적인 구간거리(km). 넘으면 불량 좌표로 만든 일정이라
    // 새벽 시각·수백 분 이동 같은 결과가 저장되지 않게 실패시킨다.
    private static final java.math.BigDecimal MAX_REASONABLE_LEG_KM = java.math.BigDecimal.valueOf(200);


    /**
     * @return 검증 결과 (통과 시 valid=true, 실패 시 valid=false + failureReason)
     */
    public HardValidationResult validate(ItineraryData data) {
        if (data == null) {
            return HardValidationResult.fail("ItineraryData is null");
        }
        if (data.steps() == null || data.steps().isEmpty()) {
            return HardValidationResult.fail("steps가 비어 있습니다.");
        }

        List<String> errors = new ArrayList<>();

        List<StepData> steps = data.steps();
        int previousDayNumber = 0;
        String previousEndTime = null;

        Map<Integer, Set<String>> visitedPlacesByDay = new HashMap<>();

        for (int i = 0; i < steps.size(); i++) {
            StepData step = steps.get(i);
            String prefix = "Step " + (i + 1) + ": ";

            validateRequiredFields(step, prefix, errors);

            validateTimeFormat(step, prefix, errors);

            int expectedOrder = i + 1;
            if (step.stepOrder() != expectedOrder) {
                errors.add(prefix + String.format("stepOrder가 %d이지만 %d이어야 합니다.", step.stepOrder(), expectedOrder));
            }

            if (step.dayNumber() < previousDayNumber) {
                errors.add(prefix + String.format("dayNumber(%d)가 이전 step의 dayNumber(%d)보다 작습니다.",
                        step.dayNumber(), previousDayNumber));
            }

            if (step.dayNumber() != previousDayNumber) {
                previousEndTime = null;
            }

            boolean startValid = step.startTime() != null && TIME_PATTERN.matcher(step.startTime()).matches();
            if (startValid && previousEndTime != null) {
                int prevEndMinutes = timeToMinutes(previousEndTime);
                int curStartMinutes = timeToMinutes(step.startTime());
                if (curStartMinutes < prevEndMinutes) {
                    errors.add(prefix + String.format("startTime(%s)이 이전 step의 endTime(%s)보다 이릅니다.",
                            step.startTime(), previousEndTime));
                }
            }

            // 숙소·교통허브는 당일 출발·복귀 패턴이라 같은 날 중복을 허용한다.
            if (step.place() != null && step.place().name() != null && !step.place().name().isBlank()
                    && !isAccommodationStep(step) && !isTransitHubStep(step)) {
                String placeName = step.place().name();
                visitedPlacesByDay.computeIfAbsent(step.dayNumber(), k -> new HashSet<>());
                if (!visitedPlacesByDay.get(step.dayNumber()).add(placeName)) {
                    errors.add(prefix + String.format("같은 날(%d일차) '%s'가 중복 방문됩니다.",
                            step.dayNumber(), placeName));
                }
            }

            if (step.transportationDistance() != null
                    && step.transportationDistance().compareTo(MAX_REASONABLE_LEG_KM) > 0) {
                errors.add(prefix + String.format("이동거리(%skm)가 비현실적입니다 (한 여행지 내 %skm 초과).",
                        step.transportationDistance().toPlainString(), MAX_REASONABLE_LEG_KM.toPlainString()));
            }

            // 모니터링 전용이라 errors에 추가하지 않는다.
            logMealLabelMismatchIfAny(step);

            previousDayNumber = step.dayNumber();
            boolean endValid = step.endTime() != null && TIME_PATTERN.matcher(step.endTime()).matches();
            if (endValid) previousEndTime = step.endTime();
        }

        if (errors.isEmpty()) {
            return HardValidationResult.pass();
        }

        String failureReason = String.join("; ", errors);
        log.debug("Hard validation failed: {}", failureReason);
        return HardValidationResult.fail(failureReason);
    }

    /**
     * notes는 자유 텍스트라 재시도 비용 대비 효과가 낮아 라벨 불일치는 로그만 남긴다.
     */
    private void logMealLabelMismatchIfAny(StepData step) {
        if (step.place() == null || step.notes() == null || step.startTime() == null) return;
        if (!"DINING".equals(PlaceCategoryConstants.majorCategory(step.place().category()))) return;
        if (!TIME_PATTERN.matcher(step.startTime()).matches()) return;

        int startMin = timeToMinutes(step.startTime());
        String notes = step.notes();
        boolean mismatchEvening = startMin < 15 * 60 && notes.contains("저녁");
        boolean mismatchSnack = startMin >= 17 * 60 && notes.contains("간식");
        if (mismatchEvening || mismatchSnack) {
            log.warn("식사 라벨 불일치 감지: day={} time={} notes='{}'",
                    step.dayNumber(), step.startTime(), notes);
        }
    }

    private void validateRequiredFields(StepData step, String prefix, List<String> errors) {
        if (step.place() == null) {
            errors.add(prefix + "place가 null입니다.");
        } else if (step.place().name() == null || step.place().name().isBlank()) {
            errors.add(prefix + "place.name이 비어 있습니다.");
        }

        if (step.startTime() == null) {
            errors.add(prefix + "startTime이 null입니다.");
        }
        if (step.endTime() == null) {
            errors.add(prefix + "endTime이 null입니다.");
        }
    }

    private void validateTimeFormat(StepData step, String prefix, List<String> errors) {
        boolean startValid = step.startTime() != null && TIME_PATTERN.matcher(step.startTime()).matches();
        boolean endValid = step.endTime() != null && TIME_PATTERN.matcher(step.endTime()).matches();

        if (step.startTime() != null && !startValid) {
            errors.add(prefix + "startTime 형식이 올바르지 않습니다 (HH:mm 필요, 값: " + step.startTime() + ").");
        }
        if (step.endTime() != null && !endValid) {
            errors.add(prefix + "endTime 형식이 올바르지 않습니다 (HH:mm 필요, 값: " + step.endTime() + ").");
        }

        // 형식이 틀린 값은 위에서 이미 오류로 남겼다.
        if (startValid && endValid) {
            int startMinutes = timeToMinutes(step.startTime());
            int endMinutes = timeToMinutes(step.endTime());
            if (endMinutes <= startMinutes) {
                errors.add(prefix + String.format("endTime(%s)이 startTime(%s)보다 같거나 이릅니다.",
                        step.endTime(), step.startTime()));
            }
        }
    }

    private int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private boolean isAccommodationStep(StepData step) {
        if (step.place() == null) return false;
        return PlaceCategoryConstants.isAccommodation(step.place().category());
    }

    private boolean isTransitHubStep(StepData step) {
        if (step.place() == null) return false;
        return PlaceCategoryConstants.isTransitHub(step.place().name(), step.place().category());
    }
}
