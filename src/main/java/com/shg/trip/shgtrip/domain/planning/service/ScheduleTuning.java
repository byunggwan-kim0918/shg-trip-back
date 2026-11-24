package com.shg.trip.shgtrip.domain.planning.service;

import java.util.Map;
import java.util.Set;

/**
 * 일정 확정·판정의 튜닝 파라미터 모음.
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
    static final int DINNER_END   = 19 * 60 + 30;

    // ── 하루 시간창 ─────────────────────────────────────────────────────────

    static final int DAY_START_MINUTES = 9 * 60;
    /** 하루 시각 표기의 상한(분). 누적 시간이 자정을 넘겨 02:16처럼 wrap되는 것을 막는다. */
    static final int END_OF_DAY_MINUTES = 23 * 60 + 59;
    /** 하루가 이 시각 이전에 끝나면 오후 활동을 보충한다. */
    static final int EARLY_FINISH_MINUTES = 17 * 60;

    // ── 체류 시간 ───────────────────────────────────────────────────────────

    static final int DEFAULT_VISIT_MINUTES = 90;
    static final int DINING_VISIT_MINUTES = 70;
    /** 출발 허브(공항·역) 도착 시각 상한. 넘기면 앞 활동을 잘라 앞당긴다. */
    static final int DEPARTURE_HUB_LATEST_MINUTES = 21 * 60;
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

    // ── 시간창 용량 · 공백 ──────────────────────────────────────────────────

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
    /** 긴 등산의 체류 상한. 일반 장소(180분)보다 길게 허용하되 하루를 통째로 먹지 않게 자른다. */
    static final int LONG_HIKE_MAX_MINUTES = 300;

    // ── 대안(스왑 후보) 점수 ────────────────────────────────────────────────


    // ── 밀도 · 반복 상한 ────────────────────────────────────────────────────

    /**
     * pace별 하루 관광·체험 개수 [최소, 최대]. 끼니는 따로 세고, 카페·간식은 받은 날짜대로 확정할 때만 관광 자리 하나를 쓴다.
     * 선택·fallback 프롬프트와 채점기가 이 값을 읽는다.
     */
    static final Map<String, int[]> SIGHTS_PER_DAY = Map.of(
            "tight", new int[]{3, 5},
            "normal", new int[]{2, 3},
            "relaxed", new int[]{1, 2}
    );

    /** 점심 시간대가 끝난 뒤 도착하거나 끝나기 전에 떠나면 그날 점심을 요구하지 않는다. 시각은 분, 모르면 null. */
    static boolean lunchInWindow(Integer start, Integer deadline) {
        return (start == null || start <= LUNCH_END) && (deadline == null || deadline >= LUNCH_END);
    }

    static boolean dinnerInWindow(Integer start, Integer deadline) {
        return (start == null || start <= DINNER_END) && (deadline == null || deadline >= DINNER_END);
    }

    /**
     * 도착부터 출발 허브 기한까지 그날 끼니를 빼고 남는 관광 시간(분). 둘 다 모르면 null. 스케줄러의 짧은 날 판정과
     * 선택 모델에 주는 날짜별 시간창이 같은 값을 써야 모델이 핵심 장소를 짧은 날에 두지 않는다.
     */
    public static Integer sightseeingWindowMinutes(Integer start, Integer deadline) {
        if (start == null && deadline == null) return null;
        int from = start != null ? start : DEFAULT_DAY_START_MINUTES;
        int to = deadline != null ? deadline : DEPARTURE_HUB_LATEST_MINUTES;
        int meals = (lunchInWindow(start, deadline) ? DINING_VISIT_MINUTES : 0)
                + (dinnerInWindow(start, deadline) ? DINING_VISIT_MINUTES : 0);
        return to - from - meals;
    }

    public static boolean tooShortForSightseeing(Integer windowMinutes) {
        return windowMinutes != null && windowMinutes < MIN_SIGHTSEEING_WINDOW_MINUTES;
    }

    public static int[] sightsPerDay(String pace) {
        return SIGHTS_PER_DAY.getOrDefault(pace == null ? "" : pace.trim().toLowerCase(), SIGHTS_PER_DAY.get("normal"));
    }


    /** 한 음식 종류가 여행 전체에서 나올 수 있는 끼니 수. 선택 프롬프트는 같은 음식 2끼까지라 5일 이상 여행에서는 이 값이 더 느슨하다. */
    static int mealKindTripCap(int dayCount) {
        return Math.max(2, (int) Math.ceil(dayCount / 2.0));
    }

    /** 요리가 달라도 같은 묶음(해산물·면 등)이 이보다 많으면 한 가지만 먹는 여행이 된다. */
    static int mealGroupTripCap(int dayCount) {
        return mealKindTripCap(dayCount) + 1;
    }

    /** 하루 카페·디저트·베이커리 방문 수 상한. 넘으면 쉬는 날이 아니라 카페를 옮겨 다니는 날이 된다. */
    static final int CAFE_PER_DAY_CAP = 2;
    /** 영업시간이 빈 백화점·쇼핑몰·아울렛으로 보는 영업시간. 국내 백화점의 흔한 개점·폐점 시각이다. */
    static final String RETAIL_COMPLEX_HOURS = java.util.stream.Stream.of(
                    "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
            .map(d -> d + ": 10:30 ~ 20:00").collect(java.util.stream.Collectors.joining(", "));

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
    public static final double MAX_REASONABLE_LEG_KM = 200.0;

    /** 해외 관문 공항을 오가는 구간의 직선 상한(km). destinations.json에서 가장 먼 관문(타이산 → 칭다오 공항 268km)보다 크다. */
    public static final double MAX_GATEWAY_TRANSFER_KM = 300.0;

    /** 이 직선 거리(km)를 넘는 구간은 이동을 추정하지 않고 비운다. */
    public static double maxLegKm(boolean hubTransfer) {
        return hubTransfer ? MAX_GATEWAY_TRANSFER_KM : MAX_REASONABLE_LEG_KM;
    }

    /** 같은 자리로 보는 두 볼거리 사이 거리(km). 걸어서 5~10분이라 다른 날로 나누면 같은 동네를 두 번 간다. */
    static final double SAME_SPOT_KM = 0.5;

    // ── 일자 재배치 (진동 방지 히스테리시스) ────────────────────────────────


    // ── 수리 루프 수렴 ──────────────────────────────────────────────────────


    // ── 서사 흐름 (2-opt 비용함수 가중치) ───────────────────────────────────


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

    /** 약한 분류 장소도 언어판이 이만큼이면 명소로 보고 보충한다. 선택 프롬프트의 "인기도 높음" 경계와 같다. */
    static final int WEAK_FILL_NOTABLE_SITELINKS = 3;

    // ── 유명·한적 성향(FameMix) ─────────────────────────────────────────────

    /** BALANCED에서 그날 대표 명소가 이만큼이면 빈 시간은 덜 알려진 곳 쪽으로 채운다. 초안 규칙의 보통 페이스 관광 2~3곳 중 둘이다. */
    static final int BALANCED_NOTABLE_PER_DAY = 2;
    /** 덜 알려진 쪽으로 빈 시간 근처 후보를 고를 때 유명한 쪽에서 남기는 수. 덜 알려진 곳이 시간에 맞지 않는 날 빈자리를 막는다. */
    static final int FILL_OPPOSITE_RESERVE = 2;
    /**
     * 근처 후보 점수에 더하는 흔들림의 폭(유명도 0~1). 미매칭 장소의 상한(0.35)과 언어판 3개 명소(0.35~)처럼 거의 같은
     * 후보끼리만 순서가 바뀌고, 언어판 0개(0.10)와 명소의 차이는 뒤집지 못한다.
     */
    static final double FILL_VARIETY = 0.08;

    /**
     * 도시 간 이동 수단. 시간 = 직선거리 / 속도 + 역·공항을 오가는 시간, 요금 = 직선 km당 현지 통화.
     * 도쿄·신오사카 신칸센, 베이징·상하이 고속철, 타이베이·쭤잉 고속철, 하노이·다낭 국내선 수준이다.
     */
    public record IntercityMode(String mode, double kmh, int overheadMinutes, double farePerKm, String currency) {}

    /** 도시 간 이동 구간의 이동 수단. 이 구간은 하루 이동 예산·구간 상한에 넣지 않고 편집 뒤에도 그날 첫 스텝에 남는다. */
    public static final Set<String> INTERCITY_LEG_MODES = Set.of("TRAIN", "FLIGHT", "DRIVE");

    /** 요금이 사람 수만큼 드는 도시 간 이동. 자동차는 한 대 비용이다. */
    public static final Set<String> PER_PERSON_FARE_MODES = Set.of("TRAIN", "FLIGHT");

    /** 국내 도시 사이 KTX. 서울-부산(직선 325km) 약 2시간 40분·59,800원에 역을 오가는 시간을 더한 기준이다. */
    static final IntercityMode KR_TRAIN = new IntercityMode("TRAIN", 120, 40, 180, "KRW");
    /** 제주를 오가는 국내선. 김포-제주(직선 450km) 비행 1시간 10분, 공항 수속·이동 2시간, 편도 약 7만 원 기준이다. */
    static final IntercityMode KR_FLIGHT = new IntercityMode("FLIGHT", 380, 120, 150, "KRW");
    /** 차로 다니는 국내 도시 이동. 고속도로 평균 시속 90km(도로 = 직선 × 1.3), 연료·통행료 도로 km당 180원 기준이다. */
    static final IntercityMode KR_DRIVE = new IntercityMode("DRIVE", 70, 20, 230, "KRW");

    public static final Map<String, IntercityMode> INTERCITY_MODES = Map.of(
            "JP", new IntercityMode("TRAIN", 160, 45, 35, "JPY"),
            "CN", new IntercityMode("TRAIN", 220, 60, 0.5, "CNY"),
            "TW", new IntercityMode("TRAIN", 180, 45, 5, "TWD"),
            "VN", new IntercityMode("FLIGHT", 600, 150, 2400, "VND"));

}
