package com.shg.trip.shgtrip.domain.tripchat.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 현재 일정 생성기가 대화에서 모은 조건을 어디까지 반영하는지와 그 안내 문구.
 * 생성 파이프라인을 바꾸면 이 판정과 TripRequestMapper를 함께 고친다.
 */
public final class GeneratorSupport {

    private GeneratorSupport() {}

    public static final Map<String, String> THEME_LABELS = labels(
            "healing", "힐링", "activity", "액티비티", "food", "맛집", "culture", "문화/역사", "nature", "자연/풍경",
            "family", "가족여행", "shopping", "쇼핑", "adventure", "모험/탐험", "romance", "로맨틱", "budget", "알뜰여행",
            "luxury", "럭셔리", "photo", "사진", "walking", "도보/산책", "ocean", "바다/해변", "mountain", "산/트레킹",
            "nightview", "야경", "local", "로컬 체험", "art", "예술/전시", "festival", "축제", "pet", "반려동물");

    public static final Map<String, String> PACE_LABELS = labels(
            "tight", "알찬 일정", "normal", "보통 일정", "relaxed", "느긋한 일정");

    public static final Map<String, String> TRANSPORT_LABELS = labels(
            "car", "자동차 이용", "walk", "도보 중심", "walk_transit", "도보 중심 · 멀면 택시나 버스",
            "transit", "대중교통 이용", "taxi", "택시 이용", "mixed", "여러 교통편 이용", "any", "상관없음");

    public static final Map<String, String> COMPANION_LABELS = labels(
            "solo", "혼자", "couple", "연인과 둘이", "friends", "친구와", "family", "가족과", "parents", "부모님과");

    /** 위임·미정이면 생성 요청 필수값(@NotEmpty)을 채울 기본 취향 */
    public static final List<String> DEFAULT_THEMES = List.of("food", "culture", "nature");
    public static final List<String> DEFAULT_CATEGORIES = List.of("attraction", "restaurant", "cafe");

    /** 테마만 있을 때 카테고리를 채운다. */
    public static final Map<String, List<String>> THEME_DEFAULT_CATEGORIES = Map.ofEntries(
            Map.entry("food", List.of("restaurant", "cafe", "street_food")),
            Map.entry("healing", List.of("cafe", "nature", "attraction")),
            Map.entry("romance", List.of("restaurant", "cafe", "viewpoint")),
            Map.entry("nature", List.of("nature", "trail", "viewpoint")),
            Map.entry("ocean", List.of("beach", "viewpoint", "restaurant")),
            Map.entry("mountain", List.of("trail", "nature", "viewpoint")),
            Map.entry("culture", List.of("museum", "temple", "attraction")),
            Map.entry("art", List.of("museum", "attraction", "cafe")),
            Map.entry("shopping", List.of("shopping", "market", "cafe")),
            Map.entry("activity", List.of("experience", "attraction", "restaurant")),
            Map.entry("adventure", List.of("experience", "trail", "attraction")),
            Map.entry("family", List.of("theme_park", "attraction", "restaurant")),
            Map.entry("nightview", List.of("viewpoint", "nightlife", "restaurant")),
            Map.entry("photo", List.of("viewpoint", "attraction", "cafe")),
            Map.entry("walking", List.of("trail", "attraction", "cafe")),
            Map.entry("local", List.of("market", "street_food", "restaurant")),
            Map.entry("festival", List.of("attraction", "experience", "restaurant")),
            Map.entry("luxury", List.of("restaurant", "spa", "attraction")),
            Map.entry("budget", List.of("attraction", "street_food", "market")),
            Map.entry("pet", List.of("cafe", "nature", "attraction")));

    /** 생성기 transportPref는 walk·car·any뿐이다. walk는 구간 8km·하루 30km 상한이라 대중교통 여행에 쓰면 갈 곳이 크게 줄어든다. */
    public static String transportPref(String mode, boolean tentative) {
        if (mode == null || tentative) return "any";
        return switch (mode) {
            case "car" -> "car";
            case "walk" -> "walk";
            default -> "any";
        };
    }

    /** 생성기는 이동 구간을 1km 미만 도보, 그 외 시속 40km 차로 추정하고 노선·시간표를 모른다. */
    public static String transportNote(String mode, boolean tentative) {
        if (tentative) return "걷는 쪽으로 생각 중이라 이동 수단을 정하지 않고 짜요.";
        if (mode == null) return null;
        return switch (mode) {
            case "transit", "walk_transit", "taxi", "mixed" ->
                    "버스·지하철 노선과 시간표는 반영되지 않아요. 이동 시간은 차 기준으로 추정해요.";
            default -> null;
        };
    }

    public static final String SUGGESTED_SUFFIX = " (제안)";
    public static final String DEFAULT_TASTE = "말한 취향이 없어 기본 취향으로 찾아요" + SUGGESTED_SUFFIX;
    public static final String UNCLASSIFIED_TASTE = "말한 취향과 맞는 분류를 찾지 못해 기본 취향으로 찾아요" + SUGGESTED_SUFFIX;
    public static final String LODGING_FIXED = "모든 날 이 숙소 기준으로 짜요.";
    public static final String LODGING_REFERENCE = "숙소 위치는 참고만 해요.";
    public static final String LODGING_NOT_FOUND = "이름으로 숙소를 찾지 못해 참고만 해요.";
    public static final String LODGING_NOT_LODGING = "숙소로 등록된 곳이 아니라 참고만 해요.";
    public static final String PLACE_INCLUDED = "일정에 꼭 넣어요.";
    public static final String PLACE_REFERENCE = "이름으로 장소를 찾지 못해 참고만 해요.";
    public static final String PLACE_BY_AREA = "이 이름이 들어간 명소를 찾아 넣어요. 못 찾으면 일정을 만든 뒤 알려줘요.";
    public static final String PLACE_IS_LODGING = "숙소는 정해둔 숙소로 알려주면 고정할 수 있어요.";
    public static final String PLACES_FULL = "꼭 넣을 장소는 10곳까지라 참고만 해요.";
    public static final String BOOKED_INCLUDED = "일정에 넣지만 예약 시간에 맞춰 고정되지는 않아요.";
    public static final String BOOKED_TIME_MISSING = "예약 시간 미정";
    public static final String BUDGET_REFERENCE = "예산은 참고만 하고 금액에 맞춰 조정하지는 않아요.";
    public static final String BUDGET_EXCLUDING_LODGING = "숙박을 뺀 예산은 그대로 전달할 수 없어 참고만 해요.";
    public static final String BUDGET_NEEDS_PARTY = "인원을 알려주면 일행 전체 예산으로 계산해요.";
    public static final String AVOID_PLACES = "피하고 싶은 장소는 아직 반영할 수 없어요.";
    public static final String CONDITIONS_REFERENCE = "음식·활동 같은 조건은 장소를 고를 때 참고만 해요.";
    public static final String APPROX_TIME = "대략적인 시간은 참고만 해요.";

    /** 꼭 가고 싶은 곳에 붙는 시간대 낱말. 생성기가 맞추는 시간대는 아침·저녁 둘뿐이다. */
    private static final Map<String, String> EXPERIENCE_TIMING_WORDS = Map.of(
            "야경", "evening", "노을", "evening", "일몰", "evening", "밤", "evening",
            "일출", "morning", "아침", "morning");

    /** @param timings 떼어 낸 낱말의 시간대 */
    record TimedWord(String word, java.util.Set<String> timings) {}

    /** 낱말 끝의 시간대 낱말만 뗀다("광안리야경" → 광안리). 앞이나 가운데는 이름의 일부일 수 있다("아침고요수목원"). */
    static TimedWord splitTiming(String token) {
        String word = token;
        java.util.Set<String> timings = new java.util.HashSet<>();
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (Map.Entry<String, String> e : EXPERIENCE_TIMING_WORDS.entrySet()) {
                if (word.endsWith(e.getKey())) {
                    word = word.substring(0, word.length() - e.getKey().length());
                    timings.add(e.getValue());
                    stripped = true;
                }
            }
        }
        return new TimedWord(word, timings);
    }

    /** 아침·저녁 낱말이 함께 나오면 어느 쪽인지 몰라 null이다. */
    static String experienceTiming(String text) {
        if (text == null) return null;
        java.util.Set<String> found = new java.util.HashSet<>();
        for (String token : text.strip().split("\\s+")) found.addAll(splitTiming(token).timings());
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /** 국내 enrich 거절 기준(prompts/enrich-input.txt UNREALISTIC_BUDGET) */
    public static final long MIN_BUDGET_PER_DAY = 50_000;

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return Map.copyOf(m);
    }
}
