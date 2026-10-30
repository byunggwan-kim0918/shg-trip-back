package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 식당·카페·술집(카테고리 "Dining and Drinking ...") 전용 보강 규칙.
 *
 * <p>관광지 프롬프트를 그대로 쓰면 두 가지가 깨진다.
 * <ul>
 *   <li><b>업종 태그를 모델이 정한다.</b> 동선 층은 태그로 식사·카페를 가른다
 *       ({@link PlaceCategoryConstants#isMealPlace}, {@link PlaceCategoryConstants#isCafeLike}).
 *       규칙을 프롬프트로 적어도 모델은 식당에 카페·커피를 붙이곤 했다(실측 19건 중 2건).
 *       그래서 업종은 카테고리에서 코드로 정하고, 업종과 어긋나는 태그는 코드로 지운다.
 *       카테고리가 총칭("Dining and Drinking > Restaurant")일 때만 모델이 이름을 보고 고른다.</li>
 *   <li><b>무명 식당에 지어낸 설명이 붙는다.</b> 모델은 동네 식당을 모른다. 설명은 Wikidata에
 *       잡힌 곳(sitelinks &gt; 0)만 받고, 나머지는 기존 설명(TourAPI 개요 등)을 그대로 둔다.</li>
 * </ul>
 *
 * <p>체류시간은 묻지 않는다. 동선 층이 식사 스텝에 고정 체류시간을 쓰고, 나머지 경로에서도
 * 업종별 기본값이면 충분하다. 입장료도 식음료는 비용 산정에 쓰이지 않아 넣지 않는다.
 */
final class DiningEnrichment {

    private DiningEnrichment() {
    }

    /** 업종. label은 태그 첫 자리에 들어가는 값이고 동선 층 키워드 목록과 맞춰져 있다. */
    enum Venue {
        MEAL("식당", List.of(), List.of("afternoon", "evening"), 70),
        CAFE("카페", List.of(), List.of("afternoon"), 50),
        BAKERY("베이커리", List.of("빵"), List.of("morning", "afternoon"), 30),
        DESSERT("디저트", List.of(), List.of("afternoon"), 40),
        BAR("술집", List.of(), List.of("evening", "night"), 90),
        SNACK("간식", List.of(), List.of("afternoon"), 30);

        final String label;
        final List<String> extraTags;
        final List<String> defaultSlots;
        final int defaultMinutes;

        Venue(String label, List<String> extraTags, List<String> defaultSlots, int defaultMinutes) {
            this.label = label;
            this.extraTags = extraTags;
            this.defaultSlots = defaultSlots;
            this.defaultMinutes = defaultMinutes;
        }

        /** 잠깐 들르는 곳 — 식당 신호가 붙으면 카페로 안 읽힌다. */
        boolean isLightStop() {
            return this == CAFE || this == BAKERY || this == DESSERT || this == SNACK;
        }

        static Venue fromLabel(String label) {
            if (label == null) return null;
            String trimmed = label.strip();
            for (Venue v : values()) {
                if (v.label.equals(trimmed)) return v;
            }
            return null;
        }
    }

    /**
     * @param placeRole 일정 속 역할(MEAL/CAFE/BAR) — 업종에서 코드로 파생한다. 모델에게 따로 물으면
     *                  업종과 역할이 어긋나는 응답이 생긴다(업종을 카테고리로 정한 이유와 같다).
     * @param cuisine   음식 종류(MEAL만) — 갈래 태그·카테고리 리프에서 파생. 식사가 아니면 null.
     */
    record Result(List<String> tags, String description, List<String> timeSlots, int durationMinutes,
                  String placeRole, String cuisine) {
    }

    static final int MAX_TAGS = 7;
    static final int MAX_TAG_LENGTH = 15;

    private static final String DINING_PREFIX = "dining and drinking";

    /** 카테고리만으로는 업종을 알 수 없는 총칭. */
    private static final Set<String> GENERIC_CATEGORIES = Set.of(
            "dining and drinking", "dining and drinking > restaurant");

    private static final Set<String> TIME_SLOTS = Set.of("morning", "afternoon", "evening", "night", "all_day");

    /** 홍보성·주관적 단어. 부분 일치로 거른다("부산맛집"도 걸린다). */
    private static final List<String> FILLER_WORDS = List.of(
            "맛집", "인기", "유명", "로컬", "현지", "핫플", "추천", "가성비", "분위기", "감성",
            "데이트", "가족", "인스타", "여행", "명소", "필수");

    private static final Set<String> REGION_NAMES = buildRegionNames();

    static boolean isDining(String category) {
        return category != null && category.strip().toLowerCase(Locale.ROOT).startsWith(DINING_PREFIX);
    }

    /**
     * 카테고리로 업종을 정한다. null이면 총칭이거나 모르는 하위 분류라 모델에게 고르게 한다.
     *
     * <p>TourAPI 행도 같은 규칙을 탄다. 적재 단계가 관광공사 분류(cat3·lclsSystm)를 이 형식의
     * 카테고리로 옮겨 두므로, 총칭으로 남은 행은 관광공사도 업종을 적지 않은 경우다.
     */
    static Venue venueOf(String category) {
        if (!isDining(category)) return null;
        String c = category.strip().toLowerCase(Locale.ROOT);
        if (GENERIC_CATEGORIES.contains(c)) return null;
        if (c.contains("> bakery") || c.contains("bagel shop")) return Venue.BAKERY;
        if (c.contains("dessert shop") || c.contains("donut shop") || c.contains("creperie")) return Venue.DESSERT;
        if (c.contains("cafe, coffee, and tea house") || c.contains("juice bar") || c.contains("smoothie shop")) {
            return Venue.CAFE;
        }
        if (c.contains("> bar") || c.contains("brewery") || c.contains("distillery") || c.contains("winery")
                || c.contains("meadery") || c.contains("cidery") || c.contains("vineyard")) {
            return Venue.BAR;
        }
        if (c.contains("snack place") || c.contains("food truck") || c.contains("food stand")
                || c.contains("night market")) {
            return Venue.SNACK;
        }
        if (c.contains("> restaurant >") || c.contains("breakfast spot") || c.contains("cafeteria")
                || c.contains("food court")) {
            return Venue.MEAL;
        }
        // 모르는 하위 분류(분류 체계 개편 등)는 단정하지 않고 모델에게 맡긴다
        return null;
    }

    static boolean isFamous(Integer wikidataSitelinks) {
        return wikidataSitelinks != null && wikidataSitelinks > 0;
    }

    /**
     * @param venue  null이면 모델이 venue를 고른다
     * @param famous true일 때만 description을 요청한다
     */
    static String buildPrompt(String name, String category, String existingDescription, Venue venue, boolean famous) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 음식·음료 업장의 검색용 태그와 방문 시간대를 정리해주세요.\n\n");
        sb.append("장소 정보:\n");
        sb.append("- 이름: ").append(name).append("\n");
        sb.append("- 카테고리: ").append(category).append("\n");
        sb.append("- 업종: ").append(venue != null ? venue.label : "미정").append("\n");
        if (existingDescription != null && !existingDescription.isBlank()) {
            sb.append("- 기존 설명: ").append(existingDescription.strip()).append("\n");
        }

        sb.append("\n규칙:\n");
        int n = 1;
        if (venue == null) {
            sb.append(n++).append(". venue는 식당, 카페, 베이커리, 디저트, 술집, 간식 중 하나다. ")
                    .append("이름이나 기존 설명에 업종이 드러나면 그 업종을 고르고, 드러나지 않으면 식당이다")
                    .append("(예: \"주택 카페\" → 카페, \"OO베이커리\" → 베이커리, \"OO포차\" → 술집, \"OO분식\" → 식당).\n");
        }
        sb.append(n++).append(". tags에는 이름·카테고리·기존 설명에서 확인되는 음식·음료만 쓴다. ")
                .append("메뉴·분위기·평판을 추측하지 않는다. 업종 이름(식당·카페 등)은 쓰지 않는다.\n");
        sb.append(n++).append(". 음식 이름이 보이면 그 이름과 큰 갈래를 함께 쓴다. ")
                .append("카테고리에 요리 종류가 있으면 큰 갈래로 옮겨 쓴다(Korean Restaurant → 한식). ")
                .append("갈래가 하나로 정해지지 않으면 갈래는 쓰지 않는다. 큰 갈래: ")
                .append("한식, 일식, 중식, 양식, 동남아음식, 돼지고기, 소고기, 닭고기, 해산물, 면요리, 탕·국밥, 분식, ")
                .append("커피, 차, 주스, 맥주, 와인, 전통주, 칵테일.\n")
                .append("   예: \"아라 생선구이\" → 생선구이, 해산물 / \"OO삼겹살\" → 삼겹살, 돼지고기 / ")
                .append("\"OO칼국수\" → 칼국수, 면요리 / \"OO쭈꾸미\" → 쭈꾸미, 해산물\n");
        if (venue == null || venue == Venue.MEAL) {
            sb.append(n++).append(". 업종이 식당이면 카페·커피·디저트·빵·빙수·아이스크림이 들어간 태그를 쓰지 않는다.\n");
        }
        if (venue == null || venue == Venue.CAFE) {
            sb.append(n++).append(". 업종이 카페면 이름·카테고리에 보이는 음료를 쓴다")
                    .append("(Café·Coffee Shop → 커피, Tea Room → 차, Bubble Tea Shop → 버블티, Juice Bar → 주스).\n");
        }
        if (venue == null || venue == Venue.BAR) {
            sb.append(n++).append(". 업종이 술집이면 종류(바, 펍, 포차, 이자카야, 호프, 와인바, 칵테일바)가 ")
                    .append("확인될 때 쓴다.\n");
        }
        sb.append(n++).append(". 홍보성·주관적 단어와 지명은 쓰지 않는다: ")
                .append(String.join(", ", FILLER_WORDS)).append(", 서울·부산 같은 지역명.\n");
        sb.append(n++).append(". tags는 0~5개. 확인되는 것이 없으면 빈 배열로 둔다.\n");
        sb.append(n++).append(". recommended_time_slots는 morning, afternoon, evening, night, all_day 중에서 고른다. ")
                .append("기본은 ").append(venue != null ? String.join("·", venue.defaultSlots)
                        : "식당 afternoon·evening, 카페·디저트·간식 afternoon, 베이커리 morning·afternoon, 술집 evening·night")
                .append("이다. 아침 식사가 분명한 곳(해장국·국밥·죽·브런치·조식)은 morning을, ")
                .append("심야 영업이 분명한 곳은 night를 더한다.\n");
        if (famous) {
            sb.append(n++).append(". description에는 이 업장에 대해 널리 알려진 사실(역사·대표 메뉴)만 ")
                    .append("한국어 2문장 이내로 쓴다. 확실하지 않으면 null.\n");
        }

        sb.append("\nJSON 형식으로만 응답해주세요 (다른 텍스트 없이):\n{\n");
        if (venue == null) sb.append("  \"venue\": \"식당\",\n");
        sb.append("  \"tags\": [\"칼국수\", \"면요리\"],\n");
        sb.append("  \"recommended_time_slots\": [\"afternoon\", \"evening\"]");
        if (famous) sb.append(",\n  \"description\": null");
        sb.append("\n}");
        return sb.toString();
    }

    /**
     * 모델 응답을 저장할 값으로 바꾼다. 업종 태그는 항상 첫 자리에 들어가므로 tags가 비는 일은 없다.
     *
     * @param knownVenue {@link #venueOf} 결과. null이면 응답의 venue를 쓰고, 그것도 없거나
     *                   모르는 값이면 식당으로 본다(총칭 카테고리가 "Restaurant"이므로).
     */
    static Result interpret(JsonNode root, Venue knownVenue, boolean famous) {
        Venue venue = knownVenue;
        if (venue == null) {
            venue = Venue.fromLabel(textOrNull(root.get("venue")));
            if (venue == null) venue = Venue.MEAL;
        }
        List<String> tags = finalizeTags(venue, textList(root.get("tags")));
        List<String> slots = sanitizeSlots(textList(root.get("recommended_time_slots")), venue);
        String description = famous ? textOrNull(root.get("description")) : null;
        return new Result(tags, description, slots, venue.defaultMinutes, roleOf(venue),
                venue == Venue.MEAL ? cuisineOf(tags, null) : null);
    }

    /** 업종 → 일정 속 역할. 카페·베이커리·디저트·간식은 모두 "쉬어 가는 자리"(CAFE)다. */
    static String roleOf(Venue venue) {
        if (venue == Venue.MEAL) return "MEAL";
        if (venue == Venue.BAR) return "BAR";
        return "CAFE";
    }

    /**
     * 음식 갈래 태그(프롬프트가 고정 목록에서만 고르게 함)와 카테고리 리프로 음식 종류를 정한다.
     * 여러 갈래가 붙으면 "무엇을 먹는가"(고기·해산물·면·국물)를 "어느 나라 음식인가"(일식·중식·양식)보다
     * 우선한다 — 같은 끼니 반복을 막는 데는 앞쪽이 더 정확하다(스시집과 라멘집은 둘 다 일식이지만
     * 연달아 먹어도 반복이 아니다). 한식만 있으면 한정식·백반류(KOREAN_SET).
     */
    static String cuisineOf(List<String> tags, String category) {
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT);
        java.util.Set<String> t = tags == null ? java.util.Set.of() : new java.util.HashSet<>(tags);
        // 치킨집은 고기로 묶지 않는다 — 일정 쪽은 이름·리프로 "chicken"을 따로 센다(PlaceCategoryConstants.LEAF_ALIAS).
        if (c.contains("fried chicken")) return "OTHER";
        if (t.contains("돼지고기") || t.contains("소고기") || t.contains("닭고기")
                || c.contains("bbq") || c.contains("steakhouse") || c.contains("yakiniku")) return "MEAT";
        if (t.contains("해산물") || c.contains("seafood") || c.contains("sushi")) return "SEAFOOD";
        if (t.contains("면요리") || c.contains("ramen") || c.contains("noodle") || c.contains("udon")
                || c.contains("soba")) return "NOODLE";
        if (t.contains("탕·국밥")) return "SOUP";
        if (t.contains("일식") || c.contains("japanese")) return "JAPANESE";
        if (t.contains("중식") || c.contains("chinese")) return "CHINESE";
        if (t.contains("양식") || c.contains("italian") || c.contains("pizza") || c.contains("burger")
                || c.contains("western") || c.contains("french")) return "WESTERN";
        if (t.contains("분식") || c.contains("bunsik") || c.contains("fast food")) return "SNACK";
        if (t.contains("한식") || c.contains("korean")) return "KOREAN_SET";
        return "OTHER";
    }

    /**
     * 업종 태그를 앞에 두고, 모델 태그에서 업종과 어긋나는 것·홍보어·지역명을 뺀다.
     *
     * <ul>
     *   <li>식당·술집: 비식사 신호(카페·커피·디저트·빵 …)를 뺀다. 남겨 두면 식당이 식사 후보에서
     *       빠지거나 카페 휴식 자리로 들어간다.</li>
     *   <li>카페·베이커리·디저트·간식: 식당 신호(한식·식당·해산물 …)를 뺀다. 남겨 두면 카페로 안 읽힌다.</li>
     * </ul>
     */
    static List<String> finalizeTags(Venue venue, List<String> modelTags) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(venue.label);
        out.addAll(venue.extraTags);
        for (String raw : modelTags) {
            if (out.size() >= MAX_TAGS) break;
            String tag = normalizeTag(raw);
            if (tag == null || isFiller(tag) || isRegionName(tag)) continue;
            if (venue.isLightStop() ? PlaceCategoryConstants.isRestaurantTag(tag)
                    : PlaceCategoryConstants.isNonMealTag(tag)) {
                continue;
            }
            out.add(tag);
        }
        return List.copyOf(out);
    }

    static List<String> sanitizeSlots(List<String> modelSlots, Venue venue) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : modelSlots) {
            if (raw == null) continue;
            String slot = raw.strip().toLowerCase(Locale.ROOT);
            if (TIME_SLOTS.contains(slot)) out.add(slot);
        }
        return out.isEmpty() ? venue.defaultSlots : List.copyOf(out);
    }

    private static String normalizeTag(String raw) {
        if (raw == null) return null;
        String tag = raw.strip();
        while (tag.startsWith("#")) tag = tag.substring(1).strip();
        if (tag.isEmpty() || tag.length() > MAX_TAG_LENGTH) return null;
        return tag;
    }

    static boolean isFiller(String tag) {
        return FILLER_WORDS.stream().anyMatch(tag::contains);
    }

    static boolean isRegionName(String tag) {
        return REGION_NAMES.contains(tag.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
    }

    /** 적재 대상 도시·광역 이름과 행정 접미사 조합. 완전 일치만 본다("제주흑돼지"는 남는다). */
    private static Set<String> buildRegionNames() {
        List<String> bases = List.of(
                "서울", "부산", "인천", "대구", "대전", "광주", "울산", "세종", "제주", "서귀포",
                "수원", "강릉", "경주", "전주", "여수",
                "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남",
                "충청북", "충청남", "전라북", "전라남", "경상북", "경상남", "한국", "대한민국");
        List<String> suffixes = List.of("", "시", "도", "광역시", "특별시", "특별자치시", "특별자치도");
        Set<String> names = new java.util.HashSet<>();
        for (String base : bases) {
            for (String suffix : suffixes) names.add(base + suffix);
        }
        names.addAll(List.of("seoul", "busan", "incheon", "daegu", "daejeon", "gwangju", "jeju",
                "suwon", "gangneung", "gyeongju", "jeonju", "yeosu", "korea"));
        return Set.copyOf(names);
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) out.add(item.asText());
        }
        return out;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String text = node.asText().strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }
}
