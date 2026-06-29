package com.shg.trip.shgtrip.domain.planning.service;

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
 * 생성 직후 코드로 교정 가능한 구조 오류를 수정한다.
 *
 * 교정 범위:
 * 1. 같은 날 숙소명 중복 제거 (첫 번째만 유지)
 * 2. 같은 날 일반 장소 중복 제거 (숙소·교통허브 제외, HardValidator와 동일 기준)
 * 3. 하루 Dining(식당+카페) 최대 3개 초과분 삭제
 * 4. 시간 오류 교정 (endTime 역전, 겹침, 자정 초과 삭제)
 * 5. stepOrder 재번호 (1부터 연속)
 *
 * 의도적으로 하지 않는 것:
 * - 숙소/교통허브 강제 삽입 (LLM이 생성한 흐름 파괴 방지)
 */
@Slf4j
@Component
public class ItineraryAutoFixer {

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final int MAX_MINUTES = 23 * 60 + 59; // 23:59 = 1439분
    private static final int MAX_ACCEPTABLE_GAP_MINUTES = 90; // 이 분 초과 공백은 비정상으로 간주
    private static final int MIN_TRANSITION_BUFFER_MINUTES = 30; // 공백 축소 시 남겨둘 최소 이동 버퍼

    public ItineraryData fix(ItineraryData data) {
        if (data == null || data.steps() == null || data.steps().isEmpty()) {
            return data;
        }

        int originalSize = data.steps().size();
        List<StepData> steps = new ArrayList<>(data.steps());

        // 1. 같은 날 숙소 중복 제거
        removeDuplicateSameDayHotel(steps);

        // 2. 같은 날 일반 장소 중복 제거
        removeDuplicateSameDayPlace(steps);

        // 3. 하루 Dining(식당+카페) 최대 3개 초과분 삭제
        limitDiningPerDay(steps, 3);

        // 4. 시간 오류 교정 (endTime 역전, 겹침, 자정 초과 삭제)
        steps = fixTimeIssues(steps);

        // 5. stepOrder 재번호
        steps = renumberStepOrder(steps);

        log.info("ItineraryAutoFixer: {}개 → {}개 steps", originalSize, steps.size());
        return new ItineraryData(data.title(), data.destination(), data.estimatedCost(), data.tags(), steps);
    }

    private void removeDuplicateSameDayHotel(List<StepData> steps) {
        Map<Integer, Set<String>> hotelsByDay = new HashMap<>();
        var iterator = steps.iterator();
        while (iterator.hasNext()) {
            StepData step = iterator.next();
            if (!isAccommodationStep(step)) continue;
            String hotelName = step.place().name();
            if (hotelName == null || hotelName.isBlank()) continue;
            Set<String> dailyHotels = hotelsByDay.computeIfAbsent(step.dayNumber(), k -> new HashSet<>());
            if (!dailyHotels.add(hotelName)) {
                log.info("AutoFixer: 숙소 중복 제거 day={} name={}", step.dayNumber(), hotelName);
                iterator.remove();
            }
        }
    }

    private void removeDuplicateSameDayPlace(List<StepData> steps) {
        Map<Integer, Set<String>> visitedByDay = new HashMap<>();
        var iterator = steps.iterator();
        while (iterator.hasNext()) {
            StepData step = iterator.next();
            if (step.place() == null || step.place().name() == null || step.place().name().isBlank()) continue;
            if (isAccommodationStep(step) || isTransitHubStep(step)) continue;
            String placeName = step.place().name();
            Set<String> daily = visitedByDay.computeIfAbsent(step.dayNumber(), k -> new HashSet<>());
            if (!daily.add(placeName)) {
                log.info("AutoFixer: 중복 장소 제거 day={} place={}", step.dayNumber(), placeName);
                iterator.remove();
            }
        }
    }

    private void limitDiningPerDay(List<StepData> steps, int maxDining) {
        Map<Integer, Integer> diningCountByDay = new HashMap<>();
        var iterator = steps.iterator();
        while (iterator.hasNext()) {
            StepData step = iterator.next();
            if (!isDiningStep(step)) continue;
            int count = diningCountByDay.merge(step.dayNumber(), 1, Integer::sum);
            if (count > maxDining) {
                log.info("AutoFixer: Dining 초과 삭제 day={} place={}", step.dayNumber(), placeName(step));
                iterator.remove();
            }
        }
    }

    private List<StepData> fixTimeIssues(List<StepData> steps) {
        List<StepData> result = new ArrayList<>();
        int prevDayNumber = -1;
        int prevEndMinutes = 0;

        for (StepData step : steps) {
            // 시간 형식이 없거나 잘못된 경우: 교정 불가, HardValidator에게 맡김
            if (step.startTime() == null || step.endTime() == null
                    || !TIME_PATTERN.matcher(step.startTime()).matches()
                    || !TIME_PATTERN.matcher(step.endTime()).matches()) {
                result.add(step);
                prevDayNumber = step.dayNumber();
                continue;
            }

            if (step.dayNumber() != prevDayNumber) {
                prevEndMinutes = 0;
            }

            int startM = timeToMinutes(step.startTime());
            int endM = timeToMinutes(step.endTime());
            boolean modified = false;

            // A. endTime ≤ startTime → +1시간
            if (endM <= startM) {
                log.info("AutoFixer: endTime 교정 day={} place={} {}→{}",
                        step.dayNumber(), placeName(step), step.endTime(), minutesToTime(startM + 60));
                endM = startM + 60;
                modified = true;
            }

            // B. 이전 step과 겹침 → 뒤로 밀기
            if (prevEndMinutes > 0 && startM < prevEndMinutes) {
                int duration = endM - startM;
                startM = prevEndMinutes;
                endM = startM + Math.max(duration, 60);
                modified = true;
                log.info("AutoFixer: 시간 겹침 교정 day={} place={} → {}-{}",
                        step.dayNumber(), placeName(step), minutesToTime(startM), minutesToTime(endM));
            }
            // B-2. 이전 step과 과도한 공백(90분 초과) → 최소 버퍼(30분)만 남기고 앞당김
            // 활동 소요시간(duration)은 그대로 유지 — 체류시간이 늘어나는 부작용 방지
            else if (prevEndMinutes > 0 && (startM - prevEndMinutes) > MAX_ACCEPTABLE_GAP_MINUTES) {
                int duration = endM - startM;
                int gap = startM - prevEndMinutes;
                startM = prevEndMinutes + MIN_TRANSITION_BUFFER_MINUTES;
                endM = startM + duration;
                modified = true;
                log.info("AutoFixer: 시간 공백 축소 day={} place={} 공백 {}분 → {}-{}",
                        step.dayNumber(), placeName(step), gap, minutesToTime(startM), minutesToTime(endM));
            }

            // C. 자정 초과 → 삭제
            if (endM > MAX_MINUTES) {
                log.info("AutoFixer: 자정 초과 삭제 day={} place={}", step.dayNumber(), placeName(step));
                prevDayNumber = step.dayNumber();
                continue;
            }

            if (modified) {
                step = withTimes(step, minutesToTime(startM), minutesToTime(endM));
            }

            result.add(step);
            prevDayNumber = step.dayNumber();
            prevEndMinutes = endM;
        }
        return result;
    }

    private List<StepData> renumberStepOrder(List<StepData> steps) {
        List<StepData> result = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            StepData step = steps.get(i);
            int expectedOrder = i + 1;
            if (step.stepOrder() != expectedOrder) {
                step = new StepData(expectedOrder, step.dayNumber(), step.startTime(), step.endTime(),
                        step.place(), step.alternatives(), step.transportationMode(),
                        step.transportationDuration(), step.transportationDistance(),
                        step.transportationCost(), step.notes(), step.estimatedCost());
            }
            result.add(step);
        }
        return result;
    }

    private StepData withTimes(StepData step, String newStart, String newEnd) {
        return new StepData(step.stepOrder(), step.dayNumber(), newStart, newEnd,
                step.place(), step.alternatives(), step.transportationMode(),
                step.transportationDuration(), step.transportationDistance(),
                step.transportationCost(), step.notes(), step.estimatedCost());
    }

    private String placeName(StepData step) {
        return step.place() != null && step.place().name() != null ? step.place().name() : "?";
    }

    private int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String minutesToTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private boolean isDiningStep(StepData step) {
        if (step.place() == null || step.place().category() == null) return false;
        return step.place().category().toLowerCase().contains("dining and drinking");
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
