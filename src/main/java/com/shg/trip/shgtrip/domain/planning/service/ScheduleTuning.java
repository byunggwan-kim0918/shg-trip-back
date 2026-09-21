package com.shg.trip.shgtrip.domain.planning.service;

import java.util.Map;

/**
 * {@link RouteOptimizer}의 튜닝 파라미터 모음.
 *
 * <p>로직과 <b>값</b>을 분리하기 위한 클래스다. 이 값들은 실측 일정을 보고 여러 차례 조정된
 * 것들이라 앞으로도 계속 바뀐다. 4천 줄짜리 최적화 로직 안에 흩어져 있으면 "하나 바꾸고
 * 재생성해 보는" 실험이 매번 그 파일을 여는 일이 된다.
 *
 * <p>값을 바꾸면 {@code RouteOptimizerGoldenMasterTest}가 깨진다 — 그게 정상이다.
 * 스냅샷 diff로 일정이 어떻게 달라졌는지 확인한 뒤 {@code -Dgolden.update=true}로 갱신한다.
 *
 * <p><b>단위 규약</b>: 시각·소요시간은 모두 <i>분</i>, 거리는 모두 <i>직선거리 km</i>다.
 * 사용자에게 보이는 이동거리는 도로 환산({@code GeoUtils.ROAD_DISTANCE_FACTOR}, ×1.3)을 거치므로
 * 표시 기준으로 상한을 잡으려면 {@code 표시값 ÷ 1.3}을 넣어야 한다.
 */
final class ScheduleTuning {

    private ScheduleTuning() {}

    // ── 식사 시간대 ──────────────────────────────────────────────────────────
    // 식사 시간대 pinning 기준 (HH:mm 파싱 후 분 단위 비교)

    static final int LUNCH_START  = 11 * 60 + 30; // 11:30
    static final int LUNCH_END    = 13 * 60 + 30; // 13:30
    static final int DINNER_START = 17 * 60 + 30; // 17:30
    static final int DINNER_END   = 19 * 60 + 30; // 19:30

    // ── 하루 시간창 ─────────────────────────────────────────────────────────

    static final int DAY_START_MINUTES = 9 * 60; // 09:00
    static final int DEFAULT_EVENING_CAP_MINUTES = 22 * 60; // 22:00
    /** 하루 시각 표기의 상한(분). 누적 시간이 자정을 넘겨 02:16처럼 wrap되는 것을 막는다. */
    static final int END_OF_DAY_MINUTES = 23 * 60 + 59; // 23:59
    /** 하루 일과 시작 시각 하한/상한 — 테마 병합 결과가 상식 범위를 벗어나지 않게 클램프. */
    static final int MIN_MORNING_START_MINUTES = 7 * 60;
    static final int MAX_EVENING_CAP_MINUTES = 23 * 60;
    /** 테마 eveningCap과 무관한 절대 시작 상한 — 어떤 스텝도 이 이후에 시작하지 않는다. */
    static final int ABSOLUTE_LATEST_START_MINUTES = 22 * 60;
    /** 하루가 이 시각 이전에 끝나면 오후 활동을 보충한다. */
    static final int EARLY_FINISH_MINUTES = 17 * 60;

    // ── 체류 시간 ───────────────────────────────────────────────────────────

    static final int DEFAULT_VISIT_MINUTES = 90;
    static final int DINING_VISIT_MINUTES = 70;
    /** 아침에 숙소에서 출발하는 스텝의 체류 시간(분). 실제 관광 체류(90분)와 달리 짧게 잡는다. */
    static final int MORNING_DEPARTURE_MINUTES = 30;
    /**
     * 하루를 마치고 숙소에 도착하는 스텝의 체류 시간(분).
     * 관광 체류(90분)로 잡으면 "23:45 체크인" 같은 어색한 시간이 나온다.
     */
    static final int ACCOMMODATION_ARRIVAL_MINUTES = 30;
    /** 출발 허브 체류(탑승 수속) — {@link #DEFAULT_VISIT_MINUTES}를 쓰면 귀가가 더 늦어진다. */
    static final int DEPARTURE_HUB_MINUTES = 60;
    /**
     * 출발 허브(공항·역) 도착 시각 상한. 넘기면 앞 활동을 잘라 앞당긴다.
     * 없으면 "23:25 공항 도착" 같은 일정이 만들어진다(실측 itinerary 67 day3).
     */
    static final int DEPARTURE_HUB_LATEST_MINUTES = 21 * 60;

    // ── 시간창 용량 · 공백 ──────────────────────────────────────────────────

    /** 활동 1개가 차지하는 대략 시간(체류 90 + 이동 추정 ~20). 시간창 용량 산정용. */
    static final int ACTIVITY_SLOT_MINUTES = 110;
    /** 버킷 내 활동 1개당 이동 여유(분) — 분 예산 산정용. */
    static final int INTRA_BUCKET_TRAVEL_MINUTES = 20;
    /**
     * 식사 슬롯 전 빈 시간이 이 값 이상이면 spare에서 활동을 삽입해 메운다.
     * {@link #ACTIVITY_SLOT_MINUTES}(버킷 용량 산정용)와 분리 — 실측에서 98/107분 공백이 미발동됐다.
     */
    static final int GAP_FILL_THRESHOLD_MINUTES = 90;
    /** 이 시간 이상 비면 spare가 말라도 미사용 후보까지 넓혀 채운다(못 채우면 로그). */
    static final int LARGE_GAP_MINUTES = 180;

    // ── 밀도 · 반복 상한 ────────────────────────────────────────────────────

    /** pace별 하루 활동 개수 [최소, 최대]. */
    static final Map<String, int[]> PACE_RANGE = Map.of(
            "tight", new int[]{5, 7},
            "normal", new int[]{4, 5},
            "relaxed", new int[]{2, 3}
    );
    /** 세부 유형 반복 상한 계산: ceil(days/2) + 1 (C1). */
    static final int TYPE_CAP_BASE = 1;
    /** 경유형(해안도로·드라이브코스 등) 전체 일정 허용 개수 — 방문지가 아니라 지나가는 구간이므로 1개. */
    static final int VIA_ROUTE_CAP = 1;
    /** 같은 대분류가 이 개수 이상 연속되면 중간을 다른 대분류로 교체한다. */
    static final int MAX_CONSECUTIVE_SAME_MAJOR = 3;
    /** 필수 식사를 넣기 위해 pace 상한을 넘어도 되는 여유(개). */
    static final int MEAL_QUOTA_SLACK = 1;

    // ── 거리 예산 ───────────────────────────────────────────────────────────

    /**
     * day 내 장소들이 day 중심에서 이 배수 이상 떨어지면 이상치로 보고 인접 day로 재배치한다.
     * walk: 도보/버스로 다닐 만한 거리로 좁게 묶음. car: 차로 이동하므로 넉넉하게 허용.
     */
    static final Map<String, Double> TRANSPORT_DISTANCE_MULTIPLIER = Map.of(
            "walk", 1.5,
            "car", 3.0,
            "any", 2.0
    );
    /**
     * 거리 절대 상한 [단일 구간 km, 일일 총주행 km].
     *
     * <p>{@link #TRANSPORT_DISTANCE_MULTIPLIER}는 day 중심 상대 기준이라 이미 흩어진 day일수록
     * 허용치가 커지는 자기무력화 문제가 있어(실측: 하루 190km, 저녁 67km 카페 원정), 상대 기준과
     * 별개로 절대 상한을 둔다.
     *
     * <p>car 일일 115 ≈ 표시 150km, any 92 ≈ 표시 120km, walk 30은 도보/대중교통이라 환산 없이 유지.
     * (기존 car {50,150}은 표시 기준으로 195km까지 허용해 "하루 185km" 일정이 상한을 통과했다)
     */
    static final Map<String, double[]> TRANSPORT_ABS_LIMITS = Map.of(
            "walk", new double[]{8, 30},
            "car", new double[]{40, 115},
            "any", new double[]{30, 92}
    );
    /**
     * 한 destination 내 하루 이동으로는 비현실적인 구간거리 임계값. 초과 시 불량 좌표로 보고
     * 해당 leg의 교통정보를 비운다(시간 누적 폭주 → 새벽시간 wrap 방지).
     */
    static final double MAX_REASONABLE_LEG_KM = 200.0;
    /**
     * 도보(walk) 선호 시, 익일 첫 방문지가 전날 숙소에서 이 거리를 넘으면 "숙소에서 아침 출발"로
     * 보정한다(전날 숙소를 그날 첫 스텝으로 prepend). car/any는 어느 정도 떨어져도 허용.
     */
    static final double WALK_CONTINUITY_THRESHOLD_KM = 2.0;
    /** 아침 식사를 유지할 숙소 기준 최대 거리(km) — 초과하면 아침 슬롯을 생략한다(C3). */
    static final double BREAKFAST_MAX_KM_FROM_ACCOMMODATION = 10.0;
    /** 저녁(dinner 이후) 활동이 숙소-저녁식당 거리보다 이만큼 이상 숙소에서 멀어지면 spare로 뺀다. */
    static final double EVENING_AWAY_TOLERANCE_KM = 5.0;
    /**
     * 연박 통일(같은 숙소 유지)을 위해 감수할 수 있는 추가 거리(km). 그날 방문지 중심에서
     * 이보다 더 멀어지면 짐 이동 편의보다 동선 손해가 커진다.
     */
    static final double CONTINUITY_MAX_EXTRA_KM = 15.0;

    // ── 일자 재배치 (진동 방지 히스테리시스) ────────────────────────────────

    /**
     * day가 지리적으로 두 덩어리로 갈릴 때, 두 서브클러스터 중심 거리가 이 값(그리고 내부 spread ×
     * 이동수단 배수) 이상이면 "2-클러스터 day"로 보고 소수 클러스터를 인접일로 옮긴다. 도심 밀집
     * day를 잘못 쪼개지 않도록 하한을 둔다.
     */
    static final double MIN_CLUSTER_SPLIT_KM = 10.0;
    /** day 간 스왑으로 얻어야 하는 최소 개선(km) — 진동 방지 히스테리시스(C5). */
    static final double SWAP_MIN_GAIN_KM = 15.0;
    /**
     * 전역 지리 재배치(rebalanceDaysByGeography): 방문지가 다른 day centroid에 이 값 이상
     * 가까워질 때만 이동한다. 미세 차이로 장소가 날짜 사이를 진동하는 것을 막는 히스테리시스.
     */
    static final double REBALANCE_MIN_GAIN_KM = 15.0;

    // ── 수리 루프 수렴 ──────────────────────────────────────────────────────

    static final int MAX_FIXPOINT_ITERATIONS = 5;
    /** 최종 불변식 패스 최대 반복 — 삽입이 또 다른 위반을 부를 수 있어 유한 횟수로 수렴시킨다. */
    static final int MAX_INVARIANT_PASSES = 3;

    // ── 서사 흐름 (2-opt 비용함수 가중치) ───────────────────────────────────

    // 거리 단위(km)와 같은 스케일의 "패널티"로 표현해 2-opt 비용함수에 더한다.
    // 도심 스팟간 거리(보통 1~5km)보다 작게 잡아, 거리가 명확히 우세하면 거리가 이기고
    // 거리차가 0.5km 내외로 비슷한(타이) 경우에만 흐름이 결정하도록 한다.
    static final double HIGHLIGHT_FIRST_PENALTY_KM = 0.5;
    static final double REST_AFTER_HIGHLIGHT_BONUS_KM = 0.3;

    // ── 비용 추정 ───────────────────────────────────────────────────────────

    // priceLevel(1~4) 단가는 카테고리별로 다르다. 기존 "priceLevel × 15,000원 일괄"은 카페
    // 30,000원, 우물 입장료 30,000원 같은 왜곡을 만들었다(실측). priceLevel이 없을 때(흔함 —
    // 특히 소규모 식당/카페)는 카테고리 기본값을 쓴다. "0원"은 식당·카페·숙소에선 사실상 항상
    // 틀린 값이라(공짜 숙박·식당은 없음) 추정치를 넣는 게 예산 감에 더 정직하다.
    static final long DINING_WON_PER_PRICE_LEVEL = 12000L;
    static final long CAFE_WON_PER_PRICE_LEVEL = 5000L;
    static final long PAID_ATTRACTION_DEFAULT_WON = 10000L;
    static final long DINING_DEFAULT_WON = 13000L;
    static final long CAFE_DEFAULT_WON = 6000L;
    // 숙소 1박(1실) 추정가. priceLevel(1~4)이 있으면 등급×단가, 없으면 숙소 유형별 기본값.
    // 숙소는 여행 예산의 최대 항목이라 0원으로 두면 예산 표시가 무의미해진다(실측: 3박 0원).
    static final long LODGING_WON_PER_PRICE_LEVEL = 60000L; // pl1=6만 ~ pl4=24만
    static final long LODGING_HOSTEL_DEFAULT_WON = 50000L;
    static final long LODGING_RESORT_DEFAULT_WON = 200000L;
    static final long LODGING_HOTEL_DEFAULT_WON = 120000L;  // 그 외 숙소 기본

    // ── 테마별 시간창 ───────────────────────────────────────────────────────

    /**
     * 마법사 테마 20종 → 하루 시간창 설정(C6).
     *
     * <p>기존엔 테마 문자열에 "야경"/"일출"이 들어있는지 contains로 두 가지만 봤다 — 나머지 18개
     * 테마는 일정 구성에 아무 영향이 없었다. 여기서는 테마 id를 키로 (시작 시각, 저녁 상한,
     * 밀도 보정, 실내 선호)를 명시한다. 여러 테마를 고르면 가장 이른 시작 · 가장 늦은 상한 ·
     * 밀도 보정 합으로 병합한다(사용자가 고른 것 전부를 반영).
     */
    static final Map<String, ThemeSchedule> THEME_SCHEDULES = Map.ofEntries(
            Map.entry("healing",   new ThemeSchedule(10 * 60, 21 * 60, -1, false)),
            Map.entry("activity",  new ThemeSchedule(8 * 60 + 30, 21 * 60, 1, false)),
            Map.entry("food",      new ThemeSchedule(9 * 60, 22 * 60, 0, true)),
            Map.entry("culture",   new ThemeSchedule(9 * 60, 21 * 60, 0, true)),
            Map.entry("nature",    new ThemeSchedule(8 * 60 + 30, 20 * 60, 0, false)),
            Map.entry("family",    new ThemeSchedule(9 * 60 + 30, 20 * 60, -1, false)),
            Map.entry("shopping",  new ThemeSchedule(10 * 60, 22 * 60, 0, true)),
            Map.entry("adventure", new ThemeSchedule(8 * 60, 21 * 60, 1, false)),
            Map.entry("romance",   new ThemeSchedule(10 * 60, 22 * 60 + 30, 0, false)),
            Map.entry("budget",    new ThemeSchedule(9 * 60, 21 * 60, 0, false)),
            Map.entry("luxury",    new ThemeSchedule(10 * 60, 22 * 60, -1, true)),
            Map.entry("photo",     new ThemeSchedule(8 * 60, 21 * 60, 0, false)),
            Map.entry("walking",   new ThemeSchedule(9 * 60, 21 * 60, 0, false)),
            Map.entry("ocean",     new ThemeSchedule(8 * 60 + 30, 21 * 60, 0, false)),
            Map.entry("mountain",  new ThemeSchedule(8 * 60, 20 * 60, -1, false)),
            // 야경 테마는 저녁을 늦게까지 열되 아침을 너무 늦추지 않는다 — 10시로 미루면 오전
            // 슬롯이 사라져 "저녁이 길어진 만큼 하루가 짧아지는" 상쇄가 일어난다.
            Map.entry("nightview", new ThemeSchedule(9 * 60 + 30, 23 * 60, 0, false)),
            Map.entry("local",     new ThemeSchedule(9 * 60, 22 * 60, 0, false)),
            Map.entry("art",       new ThemeSchedule(10 * 60, 21 * 60, 0, true)),
            Map.entry("festival",  new ThemeSchedule(10 * 60, 23 * 60, 0, false)),
            Map.entry("pet",       new ThemeSchedule(9 * 60 + 30, 20 * 60, -1, false))
    );
}
