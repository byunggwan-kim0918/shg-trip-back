package com.shg.trip.shgtrip.domain.planning.service.validation;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.SoftEvaluationResult;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.ValidationResult;
import com.shg.trip.shgtrip.domain.planning.service.ai.AIService;
import com.shg.trip.shgtrip.global.config.PlanningProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 일정 검증 서비스.
 * - validateHard: 필수 필드 검증 (순수 Java 로직)
 * - validateSoft: AI 품질 검증 (Haiku 4.5)
 * - validateWithRetry: 보강 → 재검증 → 재생성 파이프라인
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryValidationService {

    private static final int MAX_ENHANCE_ATTEMPTS = 3;

    private final AIService aiService;
    private final PlanningProperties planningProperties;

    /**
     * Hard 검증: 필수 필드, 날짜 범위, 장소 존재 여부, 대안 개수.
     * 순수 Java 로직 — AI 호출 없음.
     */
    public ValidationResult validateHard(ItineraryData data, EnrichedInput input) {
        List<String> errors = new ArrayList<>();

        // 필수 필드 검증
        if (data.title() == null || data.title().isBlank()) {
            errors.add("일정 제목이 없습니다.");
        }
        if (data.destination() == null || data.destination().isBlank()) {
            errors.add("여행지가 없습니다.");
        }
        if (data.steps() == null || data.steps().isEmpty()) {
            errors.add("일정 단계가 없습니다.");
        }

        if (data.steps() != null) {
            // 날짜 범위 검증
            long expectedDays = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
            int maxDayNumber = data.steps().stream()
                    .mapToInt(StepData::dayNumber)
                    .max().orElse(0);
            if (maxDayNumber > expectedDays) {
                errors.add(String.format("일정 일수(%d)가 여행 기간(%d일)을 초과합니다.", maxDayNumber, expectedDays));
            }

            for (int i = 0; i < data.steps().size(); i++) {
                StepData step = data.steps().get(i);
                String prefix = String.format("Step %d: ", i + 1);

                // 장소 존재 여부
                if (step.place() == null || step.place().name() == null || step.place().name().isBlank()) {
                    errors.add(prefix + "장소 정보가 없습니다.");
                }

                // 시간 형식 검증
                if (step.startTime() == null || !step.startTime().matches("\\d{2}:\\d{2}")) {
                    errors.add(prefix + "시작 시간 형식이 올바르지 않습니다 (HH:mm).");
                }
                if (step.endTime() == null || !step.endTime().matches("\\d{2}:\\d{2}")) {
                    errors.add(prefix + "종료 시간 형식이 올바르지 않습니다 (HH:mm).");
                }

                // 대안 개수 검증 (3~5개) — 교통 허브(공항/기차역 등) step은 대안이 비현실적이므로 제외
                boolean isTransitHub = isTransitHub(step);
                if (!isTransitHub) {
                    if (step.alternatives() == null || step.alternatives().size() < 3) {
                        errors.add(prefix + "대안 장소가 3개 미만입니다.");
                    }
                    if (step.alternatives() != null && step.alternatives().size() > 5) {
                        errors.add(prefix + "대안 장소가 5개를 초과합니다.");
                    }
                }
            }

            // 메인 장소 중복 검증 (숙소·교통 허브 제외)
            Map<String, Long> placeNameCount = data.steps().stream()
                    .filter(s -> s.place() != null && s.place().name() != null)
                    .filter(s -> !isTransitHubOrAccommodation(s))
                    .collect(java.util.stream.Collectors.groupingBy(
                            s -> s.place().name(), java.util.stream.Collectors.counting()));
            placeNameCount.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .forEach(e -> errors.add(String.format("장소 '%s'이(가) 일정에 %d회 중복 배치되었습니다.", e.getKey(), e.getValue())));
        }

        return errors.isEmpty() ? ValidationResult.hardPass() : ValidationResult.hardFail(errors);
    }

    /**
     * Soft 검증: Haiku 4.5 AI 품질 평가 + Java 규칙 검사.
     * AI 평가 (validate-soft.txt): 문맥 일관성(30점), 동선 효율성(40점), 정보 완전성(30점)
     * Java 추가 감점: 예산 초과(-15), 일정 밀도(-10), 교통 정보 누락(-5/건)
     */
    public ValidationResult validateSoft(ItineraryData data, EnrichedInput input) {
        try {
            // 1. AI 품질 평가 (Haiku 4.5)
            SoftEvaluationResult aiEval = aiService.evaluateSoftQuality(data, input);
            int score = aiEval.score();
            List<String> warnings = new ArrayList<>(aiEval.issues());

            // 2. Java 규칙 추가 감점
            // 예산 초과
            if (data.estimatedCost() != null && input.budget() != null
                    && data.estimatedCost().compareTo(input.budget()) > 0) {
                warnings.add(String.format("예상 비용(%s원)이 예산(%s원)을 초과합니다.",
                        data.estimatedCost().toPlainString(), input.budget().toPlainString()));
                score = Math.max(score - 15, 0);
                log.debug("Soft 감점: 예산 초과 -15 → score={}", score);
            }

            // 일정 밀도 (하루 평균 단계 수) — pace에 따라 기준 다름
            if (data.steps() != null && !data.steps().isEmpty()) {
                long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
                double avgSteps = (double) data.steps().size() / days;
                String pace = input.pace() != null ? input.pace() : "normal";
                switch (pace) {
                    case "tight" -> {
                        if (avgSteps < 4) {
                            warnings.add("알차게 페이스인데 하루 평균 일정이 4개 미만입니다.");
                            score = Math.max(score - 10, 0);
                            log.debug("Soft 감점: tight 페이스 밀도 부족 -10 → score={}", score);
                        }
                    }
                    case "relaxed" -> {
                        // relaxed는 2~3개가 정상 — 과밀만 체크
                        if (avgSteps > 5) {
                            warnings.add("여유롭게 페이스인데 하루 평균 일정이 5개를 초과합니다.");
                            score = Math.max(score - 10, 0);
                            log.debug("Soft 감점: relaxed 페이스 과밀 -10 → score={}", score);
                        }
                    }
                    default -> { // normal
                        if (avgSteps < 2) {
                            warnings.add("하루 평균 일정이 2개 미만으로 빈약합니다.");
                            score = Math.max(score - 10, 0);
                            log.debug("Soft 감점: normal 페이스 밀도 부족 -10 → score={}", score);
                        }
                        if (avgSteps > 8) {
                            warnings.add("하루 평균 일정이 8개를 초과하여 과밀합니다.");
                            score = Math.max(score - 10, 0);
                            log.debug("Soft 감점: normal 페이스 과밀 -10 → score={}", score);
                        }
                    }
                }
            }

            // 교통 정보 누락 — 각 날의 첫 번째 단계는 제외
            if (data.steps() != null) {
                // 각 dayNumber별 최소 stepOrder를 구해서 해당 step은 교통 정보 불필요
                Map<Integer, Integer> firstStepPerDay = new HashMap<>();
                for (StepData s : data.steps()) {
                    firstStepPerDay.merge(s.dayNumber(), s.stepOrder(), Math::min);
                }
                long missingTransport = data.steps().stream()
                        .filter(s -> {
                            Integer firstStep = firstStepPerDay.get(s.dayNumber());
                            return firstStep == null || s.stepOrder() != firstStep;
                        })
                        .filter(s -> s.transportationMode() == null || s.transportationMode().isBlank())
                        .count();
                if (missingTransport > 0) {
                    warnings.add(String.format("교통 정보가 누락된 단계가 %d개 있습니다.", missingTransport));
                    score = Math.max(score - (int) (missingTransport * 5), 0);
                    log.debug("Soft 감점: 교통 정보 누락 {}건 × -5 → score={}", missingTransport, score);
                }

                // 이동 시간 > 시간 갭 검증 (물리적으로 불가능한 이동)
                List<StepData> sorted = data.steps().stream()
                        .sorted(java.util.Comparator.comparingInt(StepData::stepOrder))
                        .toList();
                long impossibleMoves = 0;
                for (int i = 0; i < sorted.size() - 1; i++) {
                    StepData cur = sorted.get(i);
                    StepData next = sorted.get(i + 1);
                    if (cur.dayNumber() != next.dayNumber()) continue;
                    if (next.transportationDuration() == null) continue;
                    if (cur.endTime() == null || next.startTime() == null) continue;
                    try {
                        int curEnd = timeToMinutes(cur.endTime());
                        int nextStart = timeToMinutes(next.startTime());
                        int gap = nextStart - curEnd;
                        if (gap < next.transportationDuration()) {
                            impossibleMoves++;
                        }
                    } catch (Exception ignored) {}
                }
                if (impossibleMoves > 0) {
                    warnings.add(String.format("이동 시간이 시간 갭보다 긴 단계가 %d개 있습니다 (물리적으로 불가능한 이동).", impossibleMoves));
                    score = Math.max(score - (int) (impossibleMoves * 3), 0);
                    log.debug("Soft 감점: 불가능한 이동 {}건 × -3 → score={}", impossibleMoves, score);
                }
            }

            String feedback = warnings.isEmpty() ? null : String.join(" / ", warnings);

            log.debug("Soft 검증 완료: AI 점수={}, Java 감점 후 최종 점수={}, 감점 항목 수={}", aiEval.score(), score, warnings.size() - aiEval.issues().size());

            if (score >= planningProperties.validation().softThreshold()) {
                return ValidationResult.softPass(score, warnings, feedback);
            } else {
                return ValidationResult.softFail(score, List.of(), warnings, feedback);
            }
        } catch (Exception e) {
            log.error("Soft validation failed: {}", e.getMessage(), e);
            // AI 장애 시 threshold 미만 점수로 enhance 루프 진입 보장
            // softThreshold - 1로 동적 계산하여 threshold 설정 변경에도 안전하게 대응
            int failScore = Math.max(0, planningProperties.validation().softThreshold() - 1);
            return ValidationResult.softFail(failScore, List.of(), List.of("Soft 검증 중 오류 발생"),
                    "AI 품질 평가 실패: " + e.getMessage());
        }
    }

    /**
     * 보강 → 재검증 → 재생성 파이프라인.
     * 최대 3회 보강 시도. AI 호출 자체가 수초 소요되므로 인위적 backoff 없음.
     * 3회 실패 시 처음부터 재생성.
     */
    public ItineraryData validateWithRetry(ItineraryData data, EnrichedInput input, List<Place> selectedPlaces) {
        // 1. Hard 검증
        ValidationResult hardResult = validateHard(data, input);
        if (!hardResult.valid()) {
            log.warn("Hard validation failed: {}", hardResult.errors());
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "일정 필수 검증 실패: " + String.join(", ", hardResult.errors()));
        }

        // 2. Soft 검증 + 보강 루프
        ItineraryData current = data;
        ValidationResult lastSoftResult = null;
        for (int attempt = 0; attempt < MAX_ENHANCE_ATTEMPTS; attempt++) {
            ValidationResult softResult = validateSoft(current, input);
            lastSoftResult = softResult;

            if (softResult.valid()) {
                log.info("Soft validation passed (score={}, attempt={})", softResult.score(), attempt);
                return current;
            }

            log.info("Soft validation failed (score={}, attempt={}/{}), enhancing...",
                    softResult.score(), attempt + 1, MAX_ENHANCE_ATTEMPTS);

            // 보강
            current = aiService.enhanceItinerary(current, softResult, input);

            // 보강 후 Hard 재검증
            ValidationResult reHard = validateHard(current, input);
            if (!reHard.valid()) {
                log.warn("Enhanced itinerary failed hard validation: {}", reHard.errors());
                // 보강이 오히려 구조를 깨뜨린 경우 → 재생성으로 전환
                break;
            }

            // 마지막 enhance 후에도 soft 재검증 수행
            if (attempt == MAX_ENHANCE_ATTEMPTS - 1) {
                ValidationResult finalSoft = validateSoft(current, input);
                lastSoftResult = finalSoft;
                if (finalSoft.valid()) {
                    log.info("Soft validation passed after final enhance (score={})", finalSoft.score());
                    return current;
                }
            }
        }

        // 3. 3회 실패 → 재생성 (마지막 실패 사유를 전달하여 같은 문제 반복 방지)
        String failureReason = lastSoftResult != null && lastSoftResult.feedback() != null
                ? lastSoftResult.feedback()
                : "이전 생성 결과가 3회 보강 후에도 품질 기준을 충족하지 못했습니다.";
        log.warn("Enhancement failed after {} attempts, regenerating from scratch. Reason: {}",
                MAX_ENHANCE_ATTEMPTS, failureReason);
        ItineraryData regenerated = aiService.regenerateItinerary(input, failureReason, selectedPlaces);

        // 재생성 결과 Hard 검증
        ValidationResult finalHard = validateHard(regenerated, input);
        if (!finalHard.valid()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "재생성된 일정도 필수 검증에 실패했습니다: " + String.join(", ", finalHard.errors()));
        }

        // 재생성 결과 Soft 검증 (best-effort: 실패해도 반환, 경고만 기록)
        ValidationResult regeneratedSoft = validateSoft(regenerated, input);
        if (!regeneratedSoft.valid()) {
            log.warn("Regenerated itinerary did not pass soft validation (score={}), returning as-is.",
                    regeneratedSoft.score());
        } else {
            log.info("Regenerated itinerary passed soft validation (score={}).", regeneratedSoft.score());
        }

        return regenerated;
    }

    /** "HH:mm" 형식 시간 문자열을 분으로 변환 */

    /** 교통 허브 장소명 패턴 — "역사박물관" 등 오탐 방지를 위해 정밀 매칭 */
    private static final java.util.regex.Pattern TRANSIT_NAME_PATTERN = java.util.regex.Pattern.compile(
            "공항|기차역|버스터미널|터미널|항구|페리|"
                    + "airport|(?:train|bus|subway|metro)\\s*station|terminal|port|ferry|"
                    + "\\S+역(?!사)",
            // "\\S+역(?!사)" → "서울역", "도쿄역" 매칭, "역사박물관" 미매칭
            // 영문 station은 교통 수식어(train/bus/subway/metro)와 결합된 경우만 매칭
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    /** 교통 허브 step인지 — 장소명 패턴 매칭 또는 FLIGHT 교통수단 */
    private boolean isTransitHub(StepData step) {
        if (step.transportationMode() != null && step.transportationMode().equals("FLIGHT")) {
            return true;
        }
        String placeName = step.place() != null && step.place().name() != null
                ? step.place().name().toLowerCase() : "";
        return TRANSIT_NAME_PATTERN.matcher(placeName).find();
    }

    /** 숙소 또는 교통 허브 step인지 */
    private boolean isTransitHubOrAccommodation(StepData step) {
        String cat = step.place() != null ? step.place().category() : null;
        if (cat != null && (cat.equals("숙소") || cat.equalsIgnoreCase("accommodation"))) {
            return true;
        }
        return isTransitHub(step);
    }

    private int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

}
