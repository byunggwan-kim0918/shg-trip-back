package com.shg.trip.shgtrip.domain.itinerary.dto;

import java.util.Set;

/**
 * 생성 요청이 받는 테마·카테고리·페이스·이동 id. 한국어 라벨은 대화 입력의 {@code GeneratorSupport}가 가진다.
 * Bean Validation 애너테이션이 컴파일 상수만 받아 정규식을 원본으로 두고 집합은 거기서 만든다.
 */
public final class WizardOptionIds {

    public static final String THEME_PATTERN =
            "healing|activity|food|culture|shopping|nature|adventure|romance|family|budget|luxury|photo"
                    + "|walking|ocean|mountain|nightview|local|art|festival|pet|quiet";
    public static final String CATEGORY_PATTERN =
            "attraction|restaurant|cafe|accommodation|experience|shopping|nightlife|nature|museum"
                    + "|theme_park|spa|market|beach|temple|street_food|viewpoint|trail";
    public static final String PACE_PATTERN = "tight|normal|relaxed";
    public static final String TRANSPORT_PATTERN = "walk|car|any";
    /** 지역별 이동 방법. 지역 하나에서는 택시와 대중교통을 나눠 구간 수단을 정한다. */
    public static final String AREA_TRANSPORT_PATTERN = "walk|car|transit|taxi|any";

    public static final Set<String> THEMES = Set.of(THEME_PATTERN.split("\\|"));
    public static final Set<String> CATEGORIES = Set.of(CATEGORY_PATTERN.split("\\|"));
    public static final Set<String> PACES = Set.of(PACE_PATTERN.split("\\|"));
    public static final Set<String> TRANSPORTS = Set.of(TRANSPORT_PATTERN.split("\\|"));

    /** 장소 주제가 아니라 덜 알려진 곳을 원하는 성향이다. 이것만 있으면 검색할 주제가 없어 기본 테마를 더한다. */
    public static final String QUIET_THEME = "quiet";

    private WizardOptionIds() {
    }
}
