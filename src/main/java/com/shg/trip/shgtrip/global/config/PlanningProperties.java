package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 일정 생성 관련 설정 프로퍼티.
 * planning.validation.soft-threshold: Soft 검증 통과 기준 점수 (기본값 70)
 */
@ConfigurationProperties(prefix = "planning")
public record PlanningProperties(Validation validation) {

    public record Validation(int softThreshold) {
        public Validation {
            if (softThreshold < 0 || softThreshold > 100) {
                throw new IllegalArgumentException(
                        "planning.validation.soft-threshold must be between 0 and 100, got: " + softThreshold);
            }
        }
    }
}
