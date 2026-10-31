package com.shg.trip.shgtrip.domain.itinerary.dto;

import java.util.Set;

/**
 * 마법사 선택지 id — 프론트 {@code lib/constants/wizardOptions.ts}·{@code lib/types/itinerary.ts}와 1:1.
 * 정규식은 Bean Validation 애너테이션이 컴파일 상수만 받기 때문에 원본으로 두고, 집합은 거기서 만든다.
 */
public final class WizardOptionIds {

    public static final String THEME_PATTERN =
            "healing|activity|food|culture|shopping|nature|adventure|romance|family|budget|luxury|photo"
                    + "|walking|ocean|mountain|nightview|local|art|festival|pet";
    public static final String CATEGORY_PATTERN =
            "attraction|restaurant|cafe|accommodation|experience|shopping|nightlife|nature|museum"
                    + "|theme_park|spa|market|beach|temple|street_food|viewpoint|trail";
    public static final String PACE_PATTERN = "tight|normal|relaxed";
    public static final String TRANSPORT_PATTERN = "walk|car|any";

    public static final Set<String> THEMES = Set.of(THEME_PATTERN.split("\\|"));
    public static final Set<String> CATEGORIES = Set.of(CATEGORY_PATTERN.split("\\|"));
    public static final Set<String> PACES = Set.of(PACE_PATTERN.split("\\|"));
    public static final Set<String> TRANSPORTS = Set.of(TRANSPORT_PATTERN.split("\\|"));

    private WizardOptionIds() {
    }
}
