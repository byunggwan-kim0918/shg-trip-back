package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;

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
            "nightview", "야경", "local", "로컬 체험", "art", "예술/전시", "festival", "축제", "pet", "반려동물",
            "quiet", "한적한 곳");

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

    /** 지역 하나의 이동 방법. 여행 전체 값과 달리 택시와 대중교통을 나눈다. 섞어 다니면 any다. */
    public static String areaTransport(String mode) {
        if (mode == null) return "any";
        return switch (mode) {
            case "car", "walk", "transit", "taxi" -> mode;
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
    public static final String KEPT_UNMET = "넣지 못한다는 걸 알고 그대로 뒀어요. 일정에 없으면 따로 알려줘요.";
    public static final String PLACE_BY_AREA = "이 이름이 들어간 명소를 찾아 넣어요. 못 찾으면 일정을 만든 뒤 알려줘요.";
    public static final String PLACE_IS_LODGING = "숙소는 정해둔 숙소로 알려주면 고정할 수 있어요.";
    public static final String PLACES_FULL = "꼭 넣을 장소는 10곳까지라 참고만 해요.";
    public static final String BOOKED_INCLUDED = "일정에 넣지만 예약 시간에 맞춰 고정되지는 않아요.";
    public static final String BOOKED_TIME_MISSING = "예약 시간 미정";
    public static final String BUDGET_REFERENCE = "예산은 참고만 하고 금액에 맞춰 조정하지는 않아요.";
    public static final String BUDGET_EXCLUDING_LODGING = "숙박을 뺀 예산은 그대로 전달할 수 없어 참고만 해요.";
    public static final String BUDGET_NEEDS_PARTY = "인원을 알려주면 일행 전체 예산으로 계산해요.";
    public static final String AVOID_PLACE_EXCLUDED = "일정과 대안에서 빼요.";
    public static final String AVOID_PLACE_NOT_FOUND = "장소를 찾지 못해 확실히 빼지는 못해요.";
    public static final String AVOID_PLACE_KEPT = "일정에 꼭 넣기로 한 곳이라 빼지 않아요.";
    public static final String AVOID_PLACE_HUB = "공항·역은 뺄 수 없어요.";
    public static final String AVOID_PLACES_FULL = "피할 곳은 10곳까지만 뺄 수 있어요.";
    public static final String CONDITIONS_REFERENCE = "좋아하는 음식이나 기타 요청은 장소를 고를 때 참고만 해요.";
    public static final String APPROX_TIME = "대략적인 시간은 참고만 해요.";
    public static final String VISIT_AREAS_INCLUDED = "모두 일정에 넣어요. 장소 정보가 없는 곳은 일정을 만든 뒤 알려줘요.";
    public static final String STAYS_FOLLOWED = "이 순서대로 숙소를 잡아요.";
    public static final String STAYS_MISMATCH = "숙박 계획의 박 수가 여행 날짜와 달라 앞에서부터 맞춰 잡아요.";
    public static final String AREA_TRANSPORT_FOLLOWED = "그 지역에 머무는 날은 이 방법으로 다니는 기준으로 짜요.";
    public static final String MUST_VISIT_TRIMMED = "꼭 갈 곳이 많아 앞의 10곳만 꼭 넣고 나머지는 참고로 넘겨요.";
    public static final String PRIORITIES_TRIMMED = "하고 싶은 게 많아 최근에 말한 것 위주로 반영해요.";
    public static final String FOOD_EXCLUDED = "이 음식을 주로 파는 식당은 빼요. 다른 메뉴에 들어 있을 수는 있어요.";
    public static final String BAR_EXCLUDED = "술집은 일정과 대안에서 빼요.";
    public static final String HIKING_EXCLUDED = "산에 오르는 등산 코스는 빼요. 둘레길 같은 걷는 길이나 낮은 산은 남을 수 있어요.";
    public static final String TREKKING_EXCLUDED = "오래 걷는 트레킹 코스는 빼요. 짧은 산책로는 남을 수 있어요.";
    public static final String ACTIVITY_EXCLUDED = "일정과 대안에서 빼요.";
    public static final String RESTRICTION_REFERENCE = "따로 걸러 내지 못해 참고만 해요.";

    private static final Map<String, String> MAJOR_LABELS = Map.of("DINING", "식당", "CAFE", "카페", "LODGING", "숙소");

    /** 대화 프롬프트(trip-chat-turn.txt)가 못 먹는 음식·피할 활동을 이 이름으로 정리한다. 안내에는 종류마다 첫 이름을 쓴다. */
    private static final Map<String, PlaceRestriction> RESTRICTION_LABELS = labeled(
            Map.entry("해산물", PlaceRestriction.SEAFOOD), Map.entry("날것", PlaceRestriction.RAW),
            Map.entry("돼지고기", PlaceRestriction.PORK), Map.entry("소고기", PlaceRestriction.BEEF),
            Map.entry("닭고기", PlaceRestriction.CHICKEN), Map.entry("고기", PlaceRestriction.MEAT),
            Map.entry("술집", PlaceRestriction.NIGHTLIFE), Map.entry("술", PlaceRestriction.NIGHTLIFE),
            Map.entry("등산", PlaceRestriction.HIKING), Map.entry("트레킹", PlaceRestriction.TREKKING),
            Map.entry("놀이공원", PlaceRestriction.THEME_PARK), Map.entry("쇼핑", PlaceRestriction.SHOPPING),
            Map.entry("박물관", PlaceRestriction.MUSEUM), Map.entry("종교 시설", PlaceRestriction.RELIGIOUS),
            Map.entry("동물원", PlaceRestriction.ZOO_AQUARIUM));
    private static final Map<String, PlaceRestriction> RESTRICTION_NAMES = RESTRICTION_LABELS.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(e -> TextNormalizer.key(e.getKey()), Map.Entry::getValue));

    /** 뒤에 말을 덧붙여도("해산물 알레르기") 알아볼 이름. 고기·술은 "고기국수"·"술빵"에 걸려 넣지 않는다. */
    private static final List<String> PREFIX_NAMES = List.of("해산물", "날것", "돼지고기", "소고기", "닭고기", "술집",
            "등산", "트레킹", "놀이공원", "쇼핑", "박물관", "종교시설", "동물원");
    private static final java.util.regex.Pattern ITEM_SEPARATOR =
            java.util.regex.Pattern.compile("[,·/]|(?:이랑|랑|하고|와|과|및|그리고|이나|또는)\\s+");
    /**
     * 이름 바로 뒤에 붙어도 피한다는 뜻인 말("트레킹 코스", "해산물 알레르기", "등산은 싫어"). "해산물은 괜찮은데"처럼
     * 다른 말이 붙으면 피한다는 뜻인지 알 수 없어 맞추지 않는다.
     */
    private static final java.util.regex.Pattern AVOIDING_TAIL = java.util.regex.Pattern.compile(
            "(?:코스|몰|센터|류|요리|음식|종류|같은곳|같은데|같은거)?(?:[은는이가도을를])?"
                    + "(?:(?:알레르기|알러지|못|안|싫|별로|빼|제외|말고).*)?");

    /** 한 항목에 이름을 여러 개 적어도("돼지고기, 소고기") 모두 맞춘다. 정해진 종류가 없으면 빈 목록. */
    static List<PlaceRestriction> restrictionsOf(String itemText) {
        PlaceRestriction exact = RESTRICTION_NAMES.get(TextNormalizer.key(itemText));
        if (exact != null) return List.of(exact);
        if (itemText == null) return List.of();
        return ITEM_SEPARATOR.splitAsStream(itemText.replaceAll("\\(.*?\\)", " ")).map(TextNormalizer::key)
                .map(part -> RESTRICTION_NAMES.containsKey(part) ? part : PREFIX_NAMES.stream()
                        .filter(name -> part.startsWith(name)
                                && AVOIDING_TAIL.matcher(part.substring(name.length())).matches())
                        .findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).map(RESTRICTION_NAMES::get).distinct().toList();
    }

    /** 한 항목에 종류가 여럿이면 안내를 종류마다 따로 쓴다. */
    static String restrictionLabel(PlaceRestriction r) {
        return RESTRICTION_LABELS.entrySet().stream().filter(e -> e.getValue() == r).findFirst()
                .map(Map.Entry::getKey).orElse(r.name());
    }

    static String restrictionNote(PlaceRestriction r) {
        return switch (r) {
            case SEAFOOD, RAW, PORK, BEEF, CHICKEN, MEAT -> FOOD_EXCLUDED;
            case NIGHTLIFE -> BAR_EXCLUDED;
            case HIKING -> HIKING_EXCLUDED;
            case TREKKING -> TREKKING_EXCLUDED;
            default -> ACTIVITY_EXCLUDED;
        };
    }

    /** 산 이름("용지봉")이 같은 이름 식당에 맞춰져도 사용자가 알아볼 수 있게 식당·카페·숙소는 종류를 붙인다. */
    static String placeLabel(PlaceMatch match) {
        String kind = MAJOR_LABELS.get(match.major());
        return kind == null ? match.name() : match.name() + "(" + kind + ")";
    }

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

    @SafeVarargs
    private static Map<String, PlaceRestriction> labeled(Map.Entry<String, PlaceRestriction>... entries) {
        Map<String, PlaceRestriction> m = new LinkedHashMap<>();
        for (Map.Entry<String, PlaceRestriction> e : entries) m.put(e.getKey(), e.getValue());
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return Map.copyOf(m);
    }
}
