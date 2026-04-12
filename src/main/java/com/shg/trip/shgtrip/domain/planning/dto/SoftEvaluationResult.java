package com.shg.trip.shgtrip.domain.planning.dto;

import java.util.List;

/**
 * Haiku 4.5 기반 일정 품질 평가 결과.
 * Requirements: 4.2
 */
public record SoftEvaluationResult(int score, List<String> issues) {}
