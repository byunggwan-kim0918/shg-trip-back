package com.shg.trip.shgtrip.domain.planning.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 일정 확정 방식 설정. v2는 국내 여행만 날짜별 확정({@link SerialPlanner})으로 보내고, 해외는 v2에서도 v1이다.
 * 해외는 v2와 v1을 비교한 측정이 없다.
 */
@Component
public class PlanningPipeline {

    private static final Set<String> VALUES = Set.of("v1", "v2");
    private static final String SERIAL_COUNTRY = "KR";

    private final boolean serialEnabled;

    public PlanningPipeline(@Value("${planning.pipeline:v1}") String pipeline) {
        String value = pipeline == null ? "" : pipeline.trim().toLowerCase(Locale.ROOT);
        if (!VALUES.contains(value)) {
            throw new IllegalArgumentException("planning.pipeline은 v1|v2: " + pipeline);
        }
        this.serialEnabled = "v2".equals(value);
    }

    public boolean serialFor(String country) {
        return serialEnabled && country != null && SERIAL_COUNTRY.equalsIgnoreCase(country.trim());
    }
}
