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
    /** 저녁 성격 활동(야경·바)의 최소 시작 시각. 저녁 식사가 없는 날에도 오후에 당겨지지 않게 한다. */
    static final int EVENING_ACTIVITY_EARLIEST_START = DINNER_START;
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
    /**
     * 개장 전에 도착하는 스텝을 개장 시각까지 늦춰줄 수 있는 한도(분).
     * 이보다 더 기다려야 하면 그 장소를 그 자리에 두지 않는다 —
     * 정오 개장인 곳을 08:00에 배정한 뒤 4시간을 기다리게 할 수는 없다(실측 74 도톤보리 야타이무라).
     */
    static final int OPENING_WAIT_LIMIT_MINUTES = 60;

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
     * 차량 여행에서 출발 허브 체류에 더하는 렌터카 반납 시간(분) — 반납·셔틀·수속 순서.
     * 공항이 마지막 스텝이라 이 값이 사용자가 보는 "일정 종료 시각"을 그대로 결정한다.
     */
    static final int RENTAL_RETURN_MINUTES = 30;
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
    /**
     * 이 시간 이상 비면 spare가 말라도 미사용 후보까지 넓혀 채운다(못 채우면 로그).
     * pace 상한(하루 장소 수)이 이미 찼어도 1곳은 넣는다 — 식사도 장소 수에 들어가서, 식사 2개가
     * 상한을 먹으면 오후 4~5시간이 통째로 빈다(실측 115 day4 12:40~17:30). relaxed는 카페만 넣는다.
     */
    static final int LARGE_GAP_MINUTES = 180;

    /** 매일 오후 카페 스톱을 켜는 테마(슬러그). 커플 여행은 테마와 무관하게 켠다. */
    static final java.util.Set<String> CAFE_STOP_THEMES = java.util.Set.of("food", "romance");
    /**
     * 카페 스톱을 고를 때 그날 중심에서의 최대 거리(km). 커버리지 기준(하루 예산의 25%, 차량 29km)은
     * 카페에 너무 넓다 — 쉬어 가는 곳에 왕복 1시간 반을 쓰게 된다(재구성 115 day3: 20km 떨어진 카페).
     * 도보 여행은 커버리지 기준(7.5km)이 더 작아 그쪽을 따른다.
     */
    static final double CAFE_STOP_MAX_FROM_CENTER_KM = 12.0;

    /**
     * 하루 거리 예산 트림에서 보호하는 대표 명소의 Wikidata 언어판 수 하한. 그날 가장 유명한 1곳만 보호한다 —
     * 성산일출봉(16)을 지키려고 같은 날 카페·식당을 남기고 명소를 빼던 트림(실측 172: 94km/69km)을 막는다.
     * 한 곳으로 제한하는 이유: 전부 보호하면 거리 예산이 의미를 잃는다.
     */
    static final int ICONIC_PROTECT_SITELINKS = 10;

    // ── 긴 등산 ────────────────────────────────────────────────────────────

    /**
     * 등산·트레킹 성격이면서 체류가 이 값 이상이면 "긴 등산"이다 — 빈 시간 채우기 후보에서 뺀다.
     * 실측 등산 태그 126곳 중 40곳.
     */
    static final int LONG_HIKE_MIN_MINUTES = 150;
    /**
     * 오전 시작만 허용하는 산행의 체류 하한. 긴 등산 기준(150)과 같게 두면 보강 후 체류 180분인 성산일출봉·미악산까지
     * 오전 버킷(150분)에 못 들어가 조용히 빠지거나 "11시 이후 시작"으로 트림됐다(실측 175). 180분 오름은 오후에
     * 올라도 되고, 하루를 거의 다 쓰는 산행(한라산국립공원·성판악 240)만 오전에 묶는다.
     */
    static final int MORNING_ONLY_HIKE_MIN_MINUTES = 240;
    /**
     * 요청 의도(timing·highlight)로 보호하는 장소의 하루 평균 상한. 보호된 장소는 거리·시간 트림에서 빠지지 않으므로,
     * 모델이 전부 표시해도 일정 전체가 굳지 않게 묶는다.
     */
    static final int INTENT_PROTECT_PER_DAY = 2;
    /**
     * 긴 등산의 체류 상한. 일반 장소는 180분에서 자르지만, 등산은 그보다 길 수 있다
     * (데이터 값이 있으면 그대로 쓰되 하루를 통째로 먹지 않게 5시간에서 자른다).
     */
    static final int LONG_HIKE_MAX_MINUTES = 300;
    /**
     * 긴 등산의 시작 상한. 산은 오후에 오르지 않는다 — 하산 전 일몰·입산 통제에 걸린다
     * (실측 115: 한라산 백록담 15:09 출발 3시간).
     */
    static final int LONG_HIKE_LATEST_START_MINUTES = 11 * 60;
    /**
     * 긴 공백을 메울 때 완화하는 반경 배수.
     *
     * <p>예전엔 후보 풀만 넓히고 반경은 그대로여서, 근처에 안 쓴 장소가 없으면 4시간 50분이
     * 그대로 비었다(실측: 골든 스냅샷 7개 중 5개가 최대공백 290분). 4시간을 비우는 것보다
     * 30분 더 달리는 편이 낫다. 거리 예산 검사와 함께 쓰므로 하루 주행 상한은 지켜진다.
     */
    static final double LARGE_GAP_RADIUS_MULTIPLIER = 2.0;
    /**
     * 긴 공백을 메울 장소가 경로에서 벗어나도 되는 한도(km, 직선 기준 <b>추가</b> 거리).
     *
     * <p>반경만 완화했을 때 실측: 공백은 290 → 106분으로 줄었지만 총이동이 257 → 369km로 늘었다.
     * 앉아서 3시간을 버리는 대신 2.5시간을 운전한 셈이라 여행자에게 이득이 아니다.
     * 그래서 "현위치 → 후보 → 식당" 경로가 "현위치 → 식당" 직선보다 이만큼 이상 길어지면 넣지 않는다.
     *
     * <p>이 한도 때문에 못 메우는 공백이 남는 것은 의도된 결과다. 근처에 할 게 없다면
     * 자유시간으로 두는 편이 정직하고, 그 공백의 근본 원인은 날짜별 장소 분산이다.
     */
    static final double LARGE_GAP_MAX_EXTRA_DETOUR_KM = 15.0;

    // ── 대안(스왑 후보) 점수 ────────────────────────────────────────────────

    /**
     * 모델이 큐레이션한 spare 후보 가점. 예전 값 100은 거리 항(0.5/km)을 압도해 스텝이 달라도
     * 대안 순위가 같았다 — 실측 78번에서 관광 스텝 11개의 대안이 모두 같은 3곳이었다.
     * 15로 두면 30km 거리 차이에는 뒤집혀 스텝별로 다른 선택지가 나온다.
     */
    static final double SPARE_CURATION_BONUS = 15.0;
    /** 같은 체인점 대안 패널티 — "다른 선택지"가 아니다(실측: 고기굽는남자 서면점 ↔ 고기굽는남자). */
    static final double SAME_CHAIN_PENALTY = 50.0;

    /**
     * 갭 보충에서 우회거리 1km를 인기도 몇 점으로 환산할지. 인기도는 0~1 범위다.
     *
     * <p>0.02면 10km를 더 가는 명소(인기도 0.9)가 바로 옆 무명 장소(0.35)를 이기고,
     * 30km 차이면 뒤집힌다. 우회 상한({@link #LARGE_GAP_MAX_EXTRA_DETOUR_KM})이 이미
     * 지리를 제한하므로 이 가중치는 그 안에서의 우선순위만 정한다.
     */
    static final double GAP_FILL_DETOUR_WEIGHT = 0.02;

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
     * pace가 거리 상한에 곱하는 계수. {@link #TRANSPORT_ABS_LIMITS}는 이동수단만 보므로,
     * 이게 없으면 {@code relaxed}가 "적게 들르되 똑같이 멀리 간다"가 된다 — 장소 수만 줄고
     * 하루 주행은 그대로라 여유로운 일정이 아니다({@link #PACE_RANGE}는 개수만 정한다).
     *
     * <p>{@code normal}은 1.0으로 둔다. 기본값을 조이면 거리 예산이 줄어 갭 충전
     * ({@code RouteOptimizer.fillGapBeforeMeal})이 쓸 여력도 같이 줄고, 실측에서 이미
     * 순공백 239분이 나온 상태다 — 측정 없이 모든 사용자의 기본값을 바꾸지 않는다.
     * 반대로 {@code relaxed}는 사용자가 명시적으로 요청한 밀도라 적용 근거가 있다.
     */
    static final Map<String, Double> PACE_DISTANCE_FACTOR = Map.of(
            "tight", 1.0,
            "normal", 1.0,
            "relaxed", 0.6
    );

    /**
     * 이동수단·pace를 함께 본 거리 상한 {@code [구간 상한, 일일 상한]}(km, 직선 기준).
     * 읽는 곳이 여섯 군데라 호출마다 따로 계산하면 pace가 한두 곳에서 조용히 빠진다.
     */
    static double[] distanceLimits(String transportPref, String pace) {
        // Map.of는 null 키 조회에서 NPE를 던진다 — 읽는 곳이 여섯 군데였을 때는 각자 터질 수
        // 있었고, 한 곳으로 모은 지금 여기서 한 번만 막는다.
        double[] base = TRANSPORT_ABS_LIMITS.getOrDefault(
                transportPref == null ? "any" : transportPref, TRANSPORT_ABS_LIMITS.get("any"));
        double factor = PACE_DISTANCE_FACTOR.getOrDefault(
                pace == null ? "" : pace.trim().toLowerCase(), 1.0);
        if (factor == 1.0) return base;
        // 계수는 일일 총주행에만 곱한다. 구간 상한까지 줄이면 "제주에서 한 번에 24km 이상
        // 못 간다"가 되어 도로망 자체와 싸운다 — 골든 jeju-40-no-startdate에서 day1의
        // 오후·저녁이 통째로 트림되고(스텝 12 → 11) 안내가 2건 → 6건으로 늘었다.
        // pace는 "하루에 얼마나 하느냐"이고, 한 구간의 길이는 여행지의 지형이 정한다.
        return new double[]{base[0], base[1] * factor};
    }
    /**
     * 한 destination 내 하루 이동으로는 비현실적인 구간거리 임계값. 초과 시 불량 좌표로 보고
     * 해당 leg의 교통정보를 비운다(시간 누적 폭주 → 새벽시간 wrap 방지).
     */
    static final double MAX_REASONABLE_LEG_KM = 200.0;

    /**
     * 이미 하루 거리 상한을 넘은 day에 그래도 허용할 추가 주행.
     *
     * <p>상한을 넘은 day에서 절대 총량으로 삽입을 막으면 어떤 후보도 통과하지 못해 하루가
     * 통째로 빈다(실측 84번 1일차: 식사만으로 123km, 공백 4시간 50분, 3km 거리의 성산일출봉 거부).
     * 8km면 "바로 옆 명소"는 들어오고 "30km 원정"은 여전히 막힌다.
     */
    static final double OVER_BUDGET_INSERT_SLACK_KM = 8.0;

    /**
     * 거리 예산 초과 트림의 최소 절감량. 이보다 적게 줄어드는 트림은 하지 않는다.
     *
     * <p>123km인 day에서 5km를 아끼려고 대표 명소를 빼면 거리는 여전히 상한 밖이고 일정만
     * 비어진다. 갭 보충이 넣은 장소를 트림 패스가 바로 되빼는 churn의 원인이기도 하다.
     */
    static final double MIN_TRIM_GAIN_KM = 10.0;

    /**
     * 좌표 기반 날짜 분할에서 "식당 없는 조각"에 다른 조각의 식당을 넘겨줄 최대 거리(km).
     *
     * <p>상한이 없으면 가장 가까운 식당이 40km 밖이어도 옮겨서, 하루 한가운데 왕복이 박힌다
     * (실측 jeju-40-compact: 제주시 조각에 서귀포 공천포식당 → 총이동 253 → 403km).
     * 상한 안에 없으면 옮기지 않고 식사를 spare로 채운다.
     */
    static final double MEAL_TRANSFER_MAX_KM = 15.0;

    /**
     * 커버리지 유일 대표를 거리 트림에서 보호할 최대 거리 — 하루 거리 예산 대비 비율.
     *
     * <p>고정 km로는 도보(하루 30km)와 차량(115km)을 같은 자로 잴 수 없다. 0.25면 도보 7.5km,
     * 차량 29km까지 보호한다. 실측 기준: 도보 일정에 29km 떨어진 시장은 넣지 않아야 하고,
     * 차량 일정에 15km 떨어진 카페는 넣어야 한다.
     */
    static final double COVERAGE_YIELD_FRACTION = 0.25;

    /**
     * 숙소를 더 가까운 곳으로 교체할 최소 <b>순이득</b>(km).
     *
     * <p>순이득 = (그날 중심까지 단축분) − (다음 날 첫 구간 증가분). 당일만 보면 숙소를 당긴
     * 대가를 다음 날 거리 예산에서 꺼내 쓴다(실측 79번: 1일차 숙소를 35km 당기자 2일차가
     * 예산 114km로 시작해 상한을 넘겨 관광지 3곳이 트림됐다).
     * 5km는 기존 임계값과 같다 — 미세 차이로 숙소가 흔들리지 않게 하는 용도.
     */
    static final double ACCOMMODATION_SWAP_MIN_GAIN_KM = 5.0;

    /**
     * 좌표 기반 날짜 분할에서 한 조각이 하루 상한을 1개 초과할 때의 벌점(km 환산).
     *
     * <p>금지가 아니라 벌점인 이유: 금지로 두면 총량이 상한×일수를 넘는 입력에서 분할이 아예
     * 실패한다. 다만 값은 <b>사실상 금지 수준</b>으로 둔다 — 초과분은 뒤의 pace quota 수리가
     * spare로 돌리므로, 초과는 곧 <b>장소가 일정에서 사라진다</b>는 뜻이다. 그건 어떤 지리적
     * 이득보다 나쁘다(실측 C5: 벌점 20km로는 제주 동서 70km에 밀려 relaxed 6곳이 4/2로 갈려
     * 명품진전복해물탕이 일정에서 빠졌다).
     *
     * <p>총량이 수용량을 넘어 모든 분할이 초과하는 경우에도 안전하다 — 초과 합계는 균등
     * 분배에서 최소가 되므로, 큰 선형 벌점은 불가피한 초과를 고르게 퍼뜨리는 쪽을 고른다.
     */
    static final double CHUNK_OVERFLOW_PENALTY_KM = 1000.0;
    /**
     * 도보(walk) 선호 시, 익일 첫 방문지가 전날 숙소에서 이 거리를 넘으면 "숙소에서 아침 출발"로
     * 보정한다(전날 숙소를 그날 첫 스텝으로 prepend). car/any는 어느 정도 떨어져도 허용.
     */
    static final double WALK_CONTINUITY_THRESHOLD_KM = 2.0;
    /** 아침 식사를 유지할 숙소 기준 최대 거리(km) — 초과하면 아침 슬롯을 생략한다(C3). */
    static final double BREAKFAST_MAX_KM_FROM_ACCOMMODATION = 10.0;
    /**
     * 아침과 점심 사이 최소 간격(분). 아침이 이보다 점심에 붙으면 한 끼를 연달아 두 번 먹는 셈이라
     * 아침을 뺀다(실측 115 재구성: 은희네해장국 10:05~11:15 → 형돈 11:30).
     */
    static final int BREAKFAST_MIN_GAP_BEFORE_LUNCH_MINUTES = 60;
    /** 저녁(dinner 이후) 활동이 숙소-저녁식당 거리보다 이만큼 이상 숙소에서 멀어지면 spare로 뺀다. */
    static final double EVENING_AWAY_TOLERANCE_KM = 5.0;
    /**
     * 연박 통일(같은 숙소 유지)을 위해 감수할 수 있는 추가 거리(km). 그날 방문지 중심에서
     * 이보다 더 멀어지면 짐 이동 편의보다 동선 손해가 커진다.
     */
    static final double CONTINUITY_MAX_EXTRA_KM = 15.0;

    /**
     * 숙소 1곳이 담당하는 숙박 일수 — 숙소 종류 수 상한의 기준
     * ({@code RouteOptimizer.enforceLodgingCountCap}). 3박이면 2곳, 5박이면 3곳까지.
     *
     * <p>인접 쌍만 보는 연박 통일로는 합계가 통제되지 않는다(실측 일정 100: 3박에 3곳).
     * 짐을 다시 싸는 비용은 거리로 환산되지 않으므로 거리 판단과 별도로 숫자를 묶는다.
     */
    static final int NIGHTS_PER_LODGING = 2;

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
    static final double REBALANCE_MIN_GAIN_KM = 5.0;

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
    // 시작 시각이 10:00이면 오전 예산이 점심(11:30)까지 90분뿐이라 활동 1개(체류 90 + 이동 20 =
    // 110분)가 들어가지 못하고 하루가 11:30 점심으로 시작한다(실측 78번: romance 테마로 4일 내내).
    // 그래서 10:00이던 테마는 9:30으로 내렸다 — 예산 120분이 되어 활동 1개가 들어간다.
    // nightview 항목에 같은 이유가 먼저 주석으로 적혀 있었다.
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
            // 야경 테마는 저녁을 늦게까지 열되 아침을 너무 늦추지 않는다 — 10시로 미루면 오전
            // 슬롯이 사라져 "저녁이 길어진 만큼 하루가 짧아지는" 상쇄가 일어난다.
            Map.entry("nightview", new ThemeSchedule(9 * 60 + 30, 23 * 60, 0, false)),
            Map.entry("local",     new ThemeSchedule(9 * 60, 22 * 60, 0, false)),
            Map.entry("art",       new ThemeSchedule(9 * 60 + 30, 21 * 60, 0, true)),
            Map.entry("festival",  new ThemeSchedule(9 * 60 + 30, 23 * 60, 0, false)),
            Map.entry("pet",       new ThemeSchedule(9 * 60 + 30, 20 * 60, -1, false))
    );
}
