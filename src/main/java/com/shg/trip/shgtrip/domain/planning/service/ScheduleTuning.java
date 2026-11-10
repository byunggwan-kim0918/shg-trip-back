package com.shg.trip.shgtrip.domain.planning.service;

import java.util.Map;

/**
 * {@link RouteOptimizer}의 튜닝 파라미터 모음.
 *
 * <p>값을 바꾸면 {@code RouteOptimizerGoldenMasterTest}가 깨진다. 스냅샷 diff를 확인한 뒤
 * {@code -Dgolden.update=true}로 갱신한다.
 *
 * <p>시각·소요시간은 분, 거리는 직선거리 km다. 표시 이동거리는 도로 환산(×1.3)을 거치므로
 * 표시 기준 상한은 {@code 표시값 ÷ 1.3}으로 넣는다.
 */
public final class ScheduleTuning {

    private ScheduleTuning() {}

    // ── 식사 시간대 ──────────────────────────────────────────────────────────

    static final int LUNCH_START  = 11 * 60 + 30;
    static final int LUNCH_END    = 13 * 60 + 30;
    static final int DINNER_START = 17 * 60 + 30;
    /** 저녁 성격 활동(야경·바)의 최소 시작 시각. 저녁 식사가 없는 날에도 오후에 당겨지지 않게 한다. */
    static final int EVENING_ACTIVITY_EARLIEST_START = DINNER_START;
    static final int DINNER_END   = 19 * 60 + 30;

    // ── 하루 시간창 ─────────────────────────────────────────────────────────

    static final int DAY_START_MINUTES = 9 * 60;
    static final int DEFAULT_EVENING_CAP_MINUTES = 22 * 60;
    /** 하루 시각 표기의 상한(분). 누적 시간이 자정을 넘겨 02:16처럼 wrap되는 것을 막는다. */
    static final int END_OF_DAY_MINUTES = 23 * 60 + 59;
    /** 테마 병합 결과가 상식 범위를 벗어나지 않게 클램프하는 하한/상한. */
    static final int MIN_MORNING_START_MINUTES = 7 * 60;
    static final int MAX_EVENING_CAP_MINUTES = 23 * 60;
    /** 테마 eveningCap과 무관한 절대 시작 상한. 어떤 스텝도 이 이후에 시작하지 않는다. */
    static final int ABSOLUTE_LATEST_START_MINUTES = 22 * 60;
    /** 하루가 이 시각 이전에 끝나면 오후 활동을 보충한다. */
    static final int EARLY_FINISH_MINUTES = 17 * 60;
    /** 개장 전에 도착하는 스텝을 개장 시각까지 늦춰줄 수 있는 한도(분). 넘으면 그 자리에 두지 않는다. */
    static final int OPENING_WAIT_LIMIT_MINUTES = 60;

    // ── 체류 시간 ───────────────────────────────────────────────────────────

    static final int DEFAULT_VISIT_MINUTES = 90;
    static final int DINING_VISIT_MINUTES = 70;
    /** 아침에 숙소에서 출발하는 스텝의 체류 시간(분). */
    static final int MORNING_DEPARTURE_MINUTES = 30;
    /** 숙소 도착 스텝의 체류 시간(분). 관광 체류(90분)로 잡으면 "23:45 체크인" 같은 시간이 나온다. */
    static final int ACCOMMODATION_ARRIVAL_MINUTES = 30;
    /** 출발 허브 체류(탑승 수속). {@link #DEFAULT_VISIT_MINUTES}를 쓰면 귀가가 더 늦어진다. */
    static final int DEPARTURE_HUB_MINUTES = 60;
    /**
     * 차량 여행에서 출발 허브 체류에 더하는 렌터카 반납 시간(분).
     * 공항이 마지막 스텝이라 이 값이 사용자가 보는 일정 종료 시각을 그대로 결정한다.
     */
    static final int RENTAL_RETURN_MINUTES = 30;
    /** 출발 허브(공항·역) 도착 시각 상한. 넘기면 앞 활동을 잘라 앞당긴다. */
    static final int DEPARTURE_HUB_LATEST_MINUTES = 21 * 60;
    /** 사용자가 준 출발 시각(비행기·기차)보다 이만큼 먼저 허브에 닿게 한다. 해외(국제선)는 더 일찍. */
    static final int DEPARTURE_CHECKIN_BUFFER_MINUTES = 60;
    static final int OVERSEAS_DEPARTURE_CHECKIN_BUFFER_MINUTES = 120;
    /** 출발이 이른 날은 마감 이만큼 전에 하루를 시작한다. */
    static final int EARLY_DEPARTURE_LEAD_MINUTES = 60;
    /** 이른 도착·이른 출발로 하루를 당길 수 있는 하한. */
    static final int EARLIEST_DAY_START_MINUTES = 5 * 60;
    /** 끼니 무렵 도착이면 도착 후 이 시간 안의 식사를 그 끼니로 본다. */
    static final int LATE_MEAL_WINDOW_MINUTES = 150;
    /** 도착 허브에서 빠져나오는 데 드는 시간. 일정에 찍히는 허브 체류와 같다. */
    static final int ARRIVAL_HUB_MINUTES = 60;
    /** 도착 시각을 모를 때 가정하는 하루 시작. */
    static final int DEFAULT_DAY_START_MINUTES = 9 * 60;
    /** 하루 쓸 수 있는 시간이 이보다 짧으면 관광을 요구하지 않는다(허브 이동 + 관광 1곳). */
    static final int MIN_SIGHTSEEING_WINDOW_MINUTES = 180;
    /** 하루가 이보다 늦게 시작하면 아침 식사를 두지 않는다. */
    static final int BREAKFAST_LATEST_START = 10 * 60;

    // ── 시간창 용량 · 공백 ──────────────────────────────────────────────────

    /** 활동 1개가 차지하는 대략 시간(체류 90 + 이동 20). 시간창 용량 산정용. */
    static final int ACTIVITY_SLOT_MINUTES = 110;
    /** 버킷 내 활동 1개당 이동 여유(분). 분 예산 산정용. */
    static final int INTRA_BUCKET_TRAVEL_MINUTES = 20;
    /**
     * 식사 슬롯 전 빈 시간이 이 값 이상이면 spare에서 활동을 삽입해 메운다.
     * {@link #ACTIVITY_SLOT_MINUTES}와 같게 두면 100분 안팎 공백이 채워지지 않는다.
     */
    static final int GAP_FILL_THRESHOLD_MINUTES = 90;
    /**
     * 이 시간 이상 비면 미사용 후보까지 넓혀 채우고, pace 상한이 찼어도 1곳은 넣는다(relaxed는 카페만).
     * 식사도 장소 수에 들어가서 상한만 지키면 오후가 통째로 빈다.
     */
    static final int LARGE_GAP_MINUTES = 180;

    /** 매일 오후 카페 스톱을 켜는 테마(슬러그). 커플 여행은 테마와 무관하게 켠다. */
    static final java.util.Set<String> CAFE_STOP_THEMES = java.util.Set.of("food", "romance");
    /**
     * 카페 스톱을 고를 때 그날 중심에서의 최대 거리(km). 커버리지 기준(차량 29km)은 쉬어 가는 곳에 너무 넓다.
     * 도보 여행은 커버리지 기준(7.5km)이 더 작아 그쪽을 따른다.
     */
    static final double CAFE_STOP_MAX_FROM_CENTER_KM = 12.0;

    /**
     * 하루 거리 예산 트림에서 보호하는 대표 명소의 Wikidata 언어판 수 하한.
     * 그날 가장 유명한 1곳만 보호한다. 전부 보호하면 거리 예산이 의미를 잃는다.
     */
    static final int ICONIC_PROTECT_SITELINKS = 10;

    // ── 긴 등산 ────────────────────────────────────────────────────────────

    /** 등산·트레킹 성격이면서 체류가 이 값 이상이면 긴 등산으로 보고 빈 시간 채우기 후보에서 뺀다. */
    static final int LONG_HIKE_MIN_MINUTES = 150;
    /**
     * 오전 시작만 허용하는 산행의 체류 하한. 긴 등산 기준(150)과 같게 두면 180분 오름이 오전 버킷(150분)에
     * 못 들어가 빠진다. 하루를 거의 다 쓰는 산행(한라산 성판악 240)만 오전에 묶는다.
     */
    static final int MORNING_ONLY_HIKE_MIN_MINUTES = 240;
    /**
     * 요청 의도(timing·highlight)로 보호하는 장소의 하루 평균 상한.
     * 보호된 장소는 트림에서 빠지지 않으므로 모델이 전부 표시해도 일정이 굳지 않게 묶는다.
     */
    static final int INTENT_PROTECT_PER_DAY = 2;
    /** 긴 등산의 체류 상한. 일반 장소(180분)보다 길게 허용하되 하루를 통째로 먹지 않게 자른다. */
    static final int LONG_HIKE_MAX_MINUTES = 300;
    /** 긴 등산의 시작 상한. 오후에 오르면 하산 전 일몰·입산 통제에 걸린다. */
    static final int LONG_HIKE_LATEST_START_MINUTES = 11 * 60;
    /**
     * 긴 공백을 메울 때 완화하는 반경 배수. 반경을 그대로 두면 근처에 안 쓴 장소가 없을 때 공백이 남는다.
     * 거리 예산 검사와 함께 쓰므로 하루 주행 상한은 지켜진다.
     */
    static final double LARGE_GAP_RADIUS_MULTIPLIER = 2.0;
    /**
     * 긴 공백을 메울 장소의 우회 한도(km, "현위치 → 후보 → 식당"이 직선보다 길어지는 추가 거리).
     * 반경만 넓히면 공백 대신 운전 시간이 늘어난다. 이 한도로 못 메운 공백은 자유시간으로 둔다.
     */
    static final double LARGE_GAP_MAX_EXTRA_DETOUR_KM = 15.0;

    // ── 대안(스왑 후보) 점수 ────────────────────────────────────────────────

    /**
     * 모델이 큐레이션한 spare 후보 가점. 30km 거리 차이(0.5/km)에는 뒤집히는 크기라 스텝별로 다른 대안이 나온다.
     * 거리 항을 압도하면 모든 스텝의 대안이 같아진다.
     */
    static final double SPARE_CURATION_BONUS = 15.0;
    /** 같은 체인점은 다른 선택지가 아니므로 대안 순위에서 미룬다. */
    static final double SAME_CHAIN_PENALTY = 50.0;

    /**
     * 갭 보충에서 우회거리 1km를 인기도(0~1) 몇 점으로 환산할지. 10km 더 가는 명소(0.9)가 옆의 무명 장소(0.35)를
     * 이기고 30km면 뒤집힌다. 지리 제한은 {@link #LARGE_GAP_MAX_EXTRA_DETOUR_KM}가 맡는다.
     */
    static final double GAP_FILL_DETOUR_WEIGHT = 0.02;

    // ── 밀도 · 반복 상한 ────────────────────────────────────────────────────

    /**
     * pace별 하루 관광·체험 개수 [최소, 최대]. 끼니는 따로 세고, 카페·간식은 관광 자리 하나를 쓴다.
     * 선택·fallback 프롬프트와 채점기가 이 값을 읽는다.
     */
    static final Map<String, int[]> SIGHTS_PER_DAY = Map.of(
            "tight", new int[]{3, 5},
            "normal", new int[]{2, 3},
            "relaxed", new int[]{1, 2}
    );
    /** 점심·저녁. 아침은 숙소에서 출발하는 날만 붙는 선택이라 세지 않는다. */
    static final int MEALS_PER_DAY = 2;

    public static int[] sightsPerDay(String pace) {
        return SIGHTS_PER_DAY.getOrDefault(pace == null ? "" : pace.trim().toLowerCase(), SIGHTS_PER_DAY.get("normal"));
    }

    /** 날짜 배분이 맞추는 하루 장소 수 [최소, 최대](숙소·허브 제외). */
    static int[] paceRange(String pace) {
        int[] sights = sightsPerDay(pace);
        return new int[]{sights[0] + MEALS_PER_DAY, sights[1] + MEALS_PER_DAY};
    }
    /** 세부 유형 반복 상한 계산: ceil(days/2) + 1. */
    static final int TYPE_CAP_BASE = 1;
    /** 경유형(해안도로·드라이브코스 등) 전체 일정 허용 개수. 방문지가 아니라 지나가는 구간이다. */
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
     * 거리 절대 상한 [단일 구간 km, 일일 총주행 km]. {@link #TRANSPORT_DISTANCE_MULTIPLIER}는 상대 기준이라
     * 흩어진 day일수록 허용치가 커지므로 따로 둔다. car 115 ≈ 표시 150km, any 92 ≈ 표시 120km.
     */
    static final Map<String, double[]> TRANSPORT_ABS_LIMITS = Map.of(
            "walk", new double[]{8, 30},
            "car", new double[]{40, 115},
            "any", new double[]{30, 92}
    );

    /**
     * pace가 거리 상한에 곱하는 계수. 이게 없으면 {@code relaxed}가 적게 들르되 똑같이 멀리 간다.
     * {@code normal}을 조이면 갭 충전이 쓸 거리 여력도 같이 줄어 공백이 늘어난다.
     */
    static final Map<String, Double> PACE_DISTANCE_FACTOR = Map.of(
            "tight", 1.0,
            "normal", 1.0,
            "relaxed", 0.6
    );

    /**
     * 이동수단·pace를 함께 본 거리 상한 {@code [구간 상한, 일일 상한]}(km, 직선 기준).
     * 읽는 곳이 여러 군데라 호출마다 따로 계산하면 pace가 한두 곳에서 조용히 빠진다.
     */
    static double[] distanceLimits(String transportPref, String pace) {
        // Map.of는 null 키 조회에서 NPE를 던진다.
        double[] base = TRANSPORT_ABS_LIMITS.getOrDefault(
                transportPref == null ? "any" : transportPref, TRANSPORT_ABS_LIMITS.get("any"));
        double factor = PACE_DISTANCE_FACTOR.getOrDefault(
                pace == null ? "" : pace.trim().toLowerCase(), 1.0);
        if (factor == 1.0) return base;
        // 계수는 일일 총주행에만 곱한다. 한 구간의 길이는 여행지 지형이 정하므로 구간 상한을 줄이면 도로망과 싸운다.
        return new double[]{base[0], base[1] * factor};
    }
    /**
     * 한 destination 내 하루 이동으로는 비현실적인 구간거리 임계값. 초과 시 불량 좌표로 보고
     * 해당 leg의 교통정보를 비운다. 그대로 두면 시간 누적이 새벽으로 wrap된다.
     */
    static final double MAX_REASONABLE_LEG_KM = 200.0;

    /**
     * 이미 하루 거리 상한을 넘은 day에 그래도 허용할 추가 주행. 절대 총량으로 막으면 어떤 후보도 못 들어와
     * 하루가 빈다. 바로 옆 명소는 들어오고 30km 원정은 막히는 크기다.
     */
    static final double OVER_BUDGET_INSERT_SLACK_KM = 8.0;

    /**
     * 거리 예산 초과 트림의 최소 절감량. 조금 아끼려고 명소를 빼면 거리는 여전히 상한 밖이고 일정만 빈다.
     * 갭 보충이 넣은 장소를 트림이 바로 되빼는 churn도 막는다.
     */
    static final double MIN_TRIM_GAIN_KM = 10.0;

    /**
     * 좌표 기반 날짜 분할에서 식당 없는 조각에 다른 조각의 식당을 넘겨줄 최대 거리(km).
     * 상한이 없으면 먼 식당을 옮겨 하루 한가운데 왕복이 박힌다. 상한 안에 없으면 식사를 spare로 채운다.
     */
    static final double MEAL_TRANSFER_MAX_KM = 15.0;

    /**
     * 커버리지 유일 대표를 거리 트림에서 보호할 최대 거리(하루 거리 예산 대비 비율).
     * 고정 km로는 도보와 차량을 같은 자로 잴 수 없다. 0.25면 도보 7.5km, 차량 29km다.
     */
    static final double COVERAGE_YIELD_FRACTION = 0.25;

    /**
     * 숙소를 더 가까운 곳으로 교체할 최소 순이득(km) = (그날 중심까지 단축분) − (다음 날 첫 구간 증가분).
     * 당일만 보면 숙소를 당긴 대가를 다음 날 거리 예산에서 꺼내 쓴다.
     */
    static final double ACCOMMODATION_SWAP_MIN_GAIN_KM = 5.0;

    /**
     * 좌표 기반 날짜 분할에서 한 조각이 하루 상한을 1개 초과할 때의 벌점(km 환산).
     * 금지로 두면 총량이 상한×일수를 넘는 입력에서 분할이 실패하므로 벌점으로 두되, 초과분은 pace quota 수리가
     * spare로 돌려 일정에서 사라지므로 사실상 금지 수준으로 둔다. 불가피한 초과는 큰 선형 벌점이 고르게 퍼뜨린다.
     */
    static final double CHUNK_OVERFLOW_PENALTY_KM = 1000.0;
    /**
     * 도보(walk) 선호 시, 익일 첫 방문지가 전날 숙소에서 이 거리를 넘으면 전날 숙소를 그날 첫 스텝으로
     * prepend한다.
     */
    static final double WALK_CONTINUITY_THRESHOLD_KM = 2.0;
    /** 아침 식사를 유지할 숙소 기준 최대 거리(km). 초과하면 아침 슬롯을 생략한다. */
    static final double BREAKFAST_MAX_KM_FROM_ACCOMMODATION = 10.0;
    /** 아침과 점심 사이 최소 간격(분). 이보다 붙으면 연달아 두 끼를 먹는 셈이라 아침을 뺀다. */
    static final int BREAKFAST_MIN_GAP_BEFORE_LUNCH_MINUTES = 60;
    /** 저녁(dinner 이후) 활동이 숙소-저녁식당 거리보다 이만큼 이상 숙소에서 멀어지면 spare로 뺀다. */
    static final double EVENING_AWAY_TOLERANCE_KM = 5.0;
    /**
     * 연박 통일(같은 숙소 유지)을 위해 감수할 수 있는 추가 거리(km). 그날 방문지 중심에서
     * 이보다 더 멀어지면 짐 이동 편의보다 동선 손해가 커진다.
     */
    static final double CONTINUITY_MAX_EXTRA_KM = 15.0;

    /**
     * 숙소 1곳이 담당하는 숙박 일수. 3박이면 2곳, 5박이면 3곳까지다. 인접 쌍만 보는 연박 통일로는 합계가
     * 통제되지 않고, 짐을 다시 싸는 비용은 거리로 환산되지 않아 따로 묶는다.
     */
    static final int NIGHTS_PER_LODGING = 2;

    // ── 일자 재배치 (진동 방지 히스테리시스) ────────────────────────────────

    /**
     * 두 서브클러스터 중심 거리가 이 값(그리고 내부 spread × 이동수단 배수) 이상이면 소수 클러스터를
     * 인접일로 옮긴다. 도심 밀집 day를 잘못 쪼개지 않도록 하한을 둔다.
     */
    static final double MIN_CLUSTER_SPLIT_KM = 10.0;
    /** day 간 스왑으로 얻어야 하는 최소 개선(km). 진동 방지 히스테리시스다. */
    static final double SWAP_MIN_GAIN_KM = 15.0;
    /**
     * 전역 지리 재배치에서 방문지가 다른 day centroid에 이 값 이상 가까워질 때만 이동한다.
     * 미세 차이로 장소가 날짜 사이를 진동하지 않게 한다.
     */
    static final double REBALANCE_MIN_GAIN_KM = 5.0;

    // ── 수리 루프 수렴 ──────────────────────────────────────────────────────

    static final int MAX_FIXPOINT_ITERATIONS = 5;
    /** 최종 불변식 패스 최대 반복. 삽입이 또 다른 위반을 부를 수 있어 유한 횟수로 끊는다. */
    static final int MAX_INVARIANT_PASSES = 3;

    // ── 서사 흐름 (2-opt 비용함수 가중치) ───────────────────────────────────

    // 도심 스팟 간 거리(1~5km)보다 작게 잡아, 거리차가 0.5km 내외로 비슷할 때만 흐름이 순서를 정한다.
    static final double HIGHLIGHT_FIRST_PENALTY_KM = 0.5;
    static final double REST_AFTER_HIGHLIGHT_BONUS_KM = 0.3;

    // ── 비용 추정 ───────────────────────────────────────────────────────────

    // priceLevel(1~4) 단가를 카테고리별로 둔다. 일괄 단가면 카페·입장료가 부풀려진다.
    // priceLevel이 없으면 카테고리 기본값을 쓴다. 식당·카페·숙소에서 0원은 항상 틀린 값이다.
    static final long DINING_WON_PER_PRICE_LEVEL = 12000L;
    static final long CAFE_WON_PER_PRICE_LEVEL = 5000L;
    static final long PAID_ATTRACTION_DEFAULT_WON = 10000L;
    static final long DINING_DEFAULT_WON = 13000L;
    static final long CAFE_DEFAULT_WON = 6000L;
    // 숙소 1박(1실) 추정가. priceLevel이 있으면 등급×단가, 없으면 숙소 유형별 기본값.
    // 숙소는 여행 예산의 최대 항목이라 0원으로 두면 예산 표시가 무의미해진다.
    static final long LODGING_WON_PER_PRICE_LEVEL = 60000L;
    static final long LODGING_HOSTEL_DEFAULT_WON = 50000L;
    static final long LODGING_RESORT_DEFAULT_WON = 200000L;
    static final long LODGING_HOTEL_DEFAULT_WON = 120000L;  // 그 외 숙소 기본

    // ── 테마별 시간창 ───────────────────────────────────────────────────────

    /**
     * 마법사 테마 id → (시작 시각, 저녁 상한, 밀도 보정, 실내 선호). 여러 테마는 가장 이른 시작 ·
     * 가장 늦은 상한 · 밀도 보정 합으로 병합한다. 시작이 9:30보다 늦으면 점심 전에 활동 1개(110분)가 못 들어간다.
     */
    static final Map<String, ThemeSchedule> THEME_SCHEDULES = Map.ofEntries(
            Map.entry("healing",   new ThemeSchedule(9 * 60 + 30, 21 * 60, -1, false)),
            Map.entry("activity",  new ThemeSchedule(8 * 60 + 30, 21 * 60, 1, false)),
            Map.entry("food",      new ThemeSchedule(9 * 60, 22 * 60, 0, true)),
            Map.entry("culture",   new ThemeSchedule(9 * 60, 21 * 60, 0, true)),
            Map.entry("nature",    new ThemeSchedule(8 * 60 + 30, 20 * 60, 0, false)),
            Map.entry("family",    new ThemeSchedule(9 * 60 + 30, 20 * 60, -1, false)),
            Map.entry("shopping",  new ThemeSchedule(9 * 60 + 30, 22 * 60, 0, true)),
            Map.entry("adventure", new ThemeSchedule(8 * 60, 21 * 60, 1, false)),
            Map.entry("romance",   new ThemeSchedule(9 * 60 + 30, 22 * 60 + 30, 0, false)),
            Map.entry("budget",    new ThemeSchedule(9 * 60, 21 * 60, 0, false)),
            Map.entry("luxury",    new ThemeSchedule(9 * 60 + 30, 22 * 60, -1, true)),
            Map.entry("photo",     new ThemeSchedule(8 * 60, 21 * 60, 0, false)),
            Map.entry("walking",   new ThemeSchedule(9 * 60, 21 * 60, 0, false)),
            Map.entry("ocean",     new ThemeSchedule(8 * 60 + 30, 21 * 60, 0, false)),
            Map.entry("mountain",  new ThemeSchedule(8 * 60, 20 * 60, -1, false)),
            Map.entry("nightview", new ThemeSchedule(9 * 60 + 30, 23 * 60, 0, false)),
            Map.entry("local",     new ThemeSchedule(9 * 60, 22 * 60, 0, false)),
            Map.entry("art",       new ThemeSchedule(9 * 60 + 30, 21 * 60, 0, true)),
            Map.entry("festival",  new ThemeSchedule(9 * 60 + 30, 23 * 60, 0, false)),
            Map.entry("pet",       new ThemeSchedule(9 * 60 + 30, 20 * 60, -1, false))
    );
}
