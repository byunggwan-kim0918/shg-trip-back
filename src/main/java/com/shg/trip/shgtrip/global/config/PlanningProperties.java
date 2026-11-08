package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
