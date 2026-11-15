package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction.*;

/**
 * 못 먹는 음식·피할 활동에 해당하는 장소를 가린다. 보강이 정한 요리 분류·주재료 태그와 카테고리로 보고,
 * 보강이 이름의 요리를 놓친 식당("OO게장"이 한식으로만 남은 곳)은 공용 요리 분류로 잡는다.
 */
@Slf4j
final class PlaceRestrictions {

    static final PlaceRestrictions NONE = new PlaceRestrictions(EnumSet.noneOf(PlaceRestriction.class));

    private static final Set<PlaceRestriction> FOOD = EnumSet.of(SEAFOOD, RAW, PORK, BEEF, CHICKEN, MEAT);

    /** 보강 프롬프트의 주재료 갈래 태그(DiningEnrichment). */
    private static final String SEAFOOD_TAG = "해산물";
    private static final String PORK_TAG = "돼지고기";
    private static final String BEEF_TAG = "소고기";
    private static final String CHICKEN_TAG = "닭고기";

    private static final Set<String> RAW_FISH_TAGS = Set.of("회", "생선회", "물회", "초밥", "스시", "사시미");
    private static final List<String> SUSHI_WORDS = List.of("초밥", "스시", "寿司", "鮨", "sushi");
    private static final Set<String> TRAIL_LEAVES = Set.of("hiking trail", "trail", "mountain", "volcano");
    /** 국내 국립공원은 대부분 산이라 오래 걷는 곳(LONG)이면 산으로 본다. */
    private static final String NATIONAL_PARK_LEAF = "national park";
    /** 산을 오른다는 낱말. 트레킹·둘레길은 걷는 길이라 넣지 않는다. */
    private static final List<String> CLIMB_WORDS = List.of("등산", "등반", "산행");
    /** 날로 먹는 요리 중 공용 요리 분류가 날것으로 세지 않는 것. */
    private static final List<String> RAW_DISH_WORDS = List.of("육회", "육사시미", "게장", "생굴");
    /** 이름에 걸린 낱말이 다른 말의 일부인 경우("체육회관"의 육회, "가게장터"의 게장). 이름에서만 지운다. */
    private static final List<String> NAME_EXCLUSIONS = List.of("회관", "게장터");
    /** 보강이 한식·면요리로만 분류하는 해산물 요리. 공용 요리 분류(조개·장어·게·회)에 없는 것만 둔다. */
    private static final List<String> SEAFOOD_DISH_WORDS = List.of("해물", "해산물", "낙지", "쭈꾸미", "주꾸미", "문어",
            "오징어", "갈치", "고등어", "아구찜", "아귀", "복국", "복어", "전복", "새우", "매운탕", "짬뽕", "어묵", "오뎅",
            "海鮮", "海鲜", "seafood");
    /** 공용 분류가 국물·면·일식으로 세는 돼지·닭 요리. 공용 고기 낱말에 넣으면 세부 유형이 바뀐다. */
    private static final List<String> PORK_DISH_WORDS = List.of("돈까스", "돈카츠", "トンカツ", "豚", "とんこつ",
            "감자탕", "뼈해장국", "순대", "제육");
    private static final List<String> CHICKEN_DISH_WORDS = List.of("삼계탕", "백숙", "鶏", "唐揚げ", "からあげ");

    private static final Set<String> SEAFOOD_LEAVES = Set.of("seafood restaurant", "sushi restaurant",
            "fish restaurant", "fish and chips shop", "poke place");
    private static final Set<String> RAW_LEAVES = Set.of("sushi restaurant", "poke place");
    /** 돼지·소를 가리지 않는 고깃집. 이름에 한쪽 고기만 있으면 그쪽으로 본다. */
    private static final Set<String> EITHER_MEAT_LEAVES = Set.of("korean bbq restaurant", "bbq joint",
            "barbecue restaurant", "yakiniku restaurant");
    private static final Set<String> BEEF_LEAVES = Set.of("steakhouse", "korean beef restaurant");
    private static final Set<String> CHICKEN_LEAVES = Set.of("fried chicken joint", "korean fried chicken joint",
            "yakitori restaurant");
    /** 공용 세부 유형 키(보강 유형을 이름 추정보다 먼저 쓴 값)와 Foursquare 리프. */
    private static final Map<PlaceRestriction, Set<String>> ACTIVITY_TYPES = Map.of(
            HIKING, Set.of("mountain", "volcano", "mountain hut"),
            THEME_PARK, Set.of("theme_park", "theme park", "amusement park", "water park"),
            SHOPPING, Set.of("shopping", "shopping mall", "shopping plaza", "department store", "outlet store",
                    "outlet mall", "duty-free store"),
            MUSEUM, Set.of("museum", "gallery", "art gallery", "art museum", "history museum", "science museum"),
            RELIGIOUS, Set.of("temple", "church", "shrine", "buddhist temple", "spiritual center", "mosque",
                    "monastery", "cathedral", "confucian temple", "kingdom hall", "synagogue", "prayer room"),
            ZOO_AQUARIUM, Set.of("zoo_aquarium", "zoo", "zoo exhibit", "aquarium"));
    /** 공용 세부 유형이 "공원"을 "놀이공원"보다 먼저 보고 동물원 키를 두지 않아 이름·태그로 더 본다. */
    private static final Map<PlaceRestriction, List<String>> ACTIVITY_WORDS = Map.of(
            THEME_PARK, List.of("놀이공원", "테마파크", "워터파크"),
            ZOO_AQUARIUM, List.of("동물원", "아쿠아리움", "수족관"));

    /** 음식 종류를 알려 주지 않는 태그. */
    private static final Set<String> GENERIC_FOOD_TAGS = Set.of("식당", "한식", "일식", "중식", "양식", "레스토랑",
            "음식점", "맛집", "분식");

    /** fallback 모델이 붙이는 종류 → 판정에 쓸 카테고리. "쇼핑"은 시장에도 붙어 이름으로만 본다. */
    private static final Map<String, String> FALLBACK_KINDS = Map.of(
            "맛집", "Dining and Drinking > Restaurant",
            "카페", "Dining and Drinking > Cafe",
            "숙소", "Travel and Transportation > Lodging");

    private final Set<PlaceRestriction> restrictions;

    private PlaceRestrictions(Set<PlaceRestriction> restrictions) {
        this.restrictions = restrictions;
    }

    static PlaceRestrictions of(Collection<PlaceRestriction> restrictions) {
        if (restrictions == null || restrictions.stream().allMatch(Objects::isNull)) return NONE;
        EnumSet<PlaceRestriction> set = EnumSet.noneOf(PlaceRestriction.class);
        restrictions.stream().filter(Objects::nonNull).forEach(set::add);
        return new PlaceRestrictions(set);
    }

    boolean isEmpty() {
        return restrictions.isEmpty();
    }

    /** @return 걸린 조건, 없으면 null */
    PlaceRestriction violatedBy(PlaceCandidate c) {
        return violatedBy(c.name(), c.nameKo(), c.category(), c.tags(), c.cuisine(), c.placeRole(), c.attributes(),
                c.recommendedDurationMinutes(), true);
    }

    /** 검색 결과에는 체류 시간이 없어 보강 난이도가 없는 등산은 후보 단계에서 DB 체류 시간으로 본다. */
    PlaceRestriction violatedBy(VectorSearchResult r) {
        return violatedBy(r.name(), r.nameKo(), r.category(), r.tags(), r.cuisine(), r.placeRole(), r.attributes(),
                null, false);
    }

    /** 요리 분류도 종류를 알려 주는 태그도 없는 식당. 음식 조건이 있으면 아는 식당을 먼저 쓴다. */
    boolean isUnknownFood(VectorSearchResult r) {
        if (restrictions.stream().noneMatch(FOOD::contains)) return false;
        // 카페는 요리 분류를 두지 않아 미상으로 보면 태그 없는 카페가 전부 뒤로 밀린다.
        String role = r.placeRole();
        if (!"MEAL".equals(role) && !(role == null
                && "DINING".equals(PlaceCategoryConstants.majorCategory(r.category())))) return false;
        boolean knowsIngredients = r.attributes() != null && r.attributes().knowsIngredients();
        return r.cuisine() == null && !knowsIngredients && (r.tags() == null || r.tags().stream()
                .allMatch(t -> t == null || GENERIC_FOOD_TAGS.contains(t.strip())));
    }

    List<PlaceCandidate> exclude(List<PlaceCandidate> candidates) {
        if (restrictions.isEmpty()) return candidates;
        List<PlaceCandidate> kept = new ArrayList<>(candidates.size());
        List<PlaceCandidate> hits = new ArrayList<>();
        for (PlaceCandidate c : candidates) {
            PlaceRestriction hit = c.userSelected() ? null : violatedBy(c);
            if (hit == null) {
                kept.add(c);
            } else {
                hits.add(c);
                log.info("피할 조건 제외: {}(id={}) ← {}", c.name(), c.placeId(), hit);
            }
        }
        if (hits.isEmpty()) return kept;
        Set<String> keys = new HashSet<>();
        hits.forEach(h -> addKey(keys, PlaceIdentity.nameKey(h.name(), h.region(), h.category())));
        List<PlaceCandidate> result = new ArrayList<>(kept.size());
        for (PlaceCandidate c : kept) {
            if (!c.userSelected() && sharesName(c.name(), c.region(), c.category(), keys)) {
                log.info("피할 조건 제외: {}(id={}) ← 같은 이름 행", c.name(), c.placeId());
            } else {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * 걸린 검색 결과의 이름 키. 출처별 같은 가게 행은 한쪽에만 요리 분류·등산 난이도가 붙어 있어, 같은 지역·대분류에서
     * 이름이 같은 행도 거리와 상관없이 함께 뺀다. 같은 이름 체인 지점은 같은 음식을 판다.
     */
    Set<String> twinKeys(Collection<VectorSearchResult> results) {
        if (restrictions.isEmpty()) return Set.of();
        Set<String> keys = new HashSet<>();
        for (VectorSearchResult r : results) {
            if (violatedBy(r) != null) addKey(keys, PlaceIdentity.nameKey(r.name(), r.region(), r.category()));
        }
        return keys;
    }

    boolean sharesName(VectorSearchResult r, Set<String> keys) {
        return sharesName(r.name(), r.region(), r.category(), keys);
    }

    private static boolean sharesName(String name, String region, String category, Set<String> keys) {
        if (keys.isEmpty()) return false;
        String key = PlaceIdentity.nameKey(name, region, category);
        return key != null && keys.contains(key);
    }

    private static void addKey(Set<String> keys, String key) {
        if (key != null) keys.add(key);
    }

    /** fallback 경로의 모델 출력은 이름과 모델이 붙인 종류("맛집"·"쇼핑")뿐이다. */
    boolean blocksName(String name, String kind) {
        if (restrictions.isEmpty() || name == null) return false;
        String category = kind == null ? null : FALLBACK_KINDS.get(kind.strip());
        return violatedBy(name, null, category, null, null, null, null, null, true) != null;
    }

    private PlaceRestriction violatedBy(String name, String nameKo, String category, List<String> tags,
                                        String cuisine, String placeRole, PlaceAttributes attributes,
                                        Integer recommendedMinutes, boolean knowsDuration) {
        if (restrictions.isEmpty()) return null;
        String major = PlaceCategoryConstants.majorCategory(category);
        if ("LODGING".equals(major) || "TRANSIT_HUB".equals(major)) return null;
        Facts f = Facts.of(name, nameKo, category, tags, cuisine, attributes);
        boolean eats = eats(major, placeRole, attributes);
        boolean visits = "ATTRACTION".equals(major) || "OTHER".equals(major);
        for (PlaceRestriction r : restrictions) {
            boolean hit = FOOD.contains(r) ? eats && servesFood(r, f)
                    : r == NIGHTLIFE ? isNightlife(major, category, tags, placeRole, attributes, f)
                    : r == HIKING ? visits && isHike(name, category, attributes, recommendedMinutes, knowsDuration, f)
                    : r == TREKKING ? visits && isLongWalk(name, category, tags, attributes, recommendedMinutes,
                            knowsDuration, f)
                    : r == SHOPPING ? visits && (isActivity(r, f) || isShop(placeRole, f))
                    : visits && isActivity(r, f);
            if (hit) return r;
        }
        return null;
    }

    private static boolean eats(String major, String placeRole, PlaceAttributes attributes) {
        if ("DINING".equals(major) || "CAFE".equals(major) || "MEAL".equals(placeRole) || "BAR".equals(placeRole)) {
            return true;
        }
        if (attributes == null || attributes.venue() == null || "CAFE".equals(attributes.venue())) return false;
        // 시장은 먹는 곳(venue)이 붙어도 시장으로 남긴다. 역할이 없으면 venue로 끼니 후보가 되어 식당으로 본다.
        boolean market = "MARKET".equals(placeRole) || (placeRole != null && "market".equals(attributes.subType()));
        return !market;
    }

    private static boolean servesFood(PlaceRestriction r, Facts f) {
        return switch (r) {
            // 타코야키는 공용 분류가 반복 상한용으로 따로 세지만 문어 요리다.
            case SEAFOOD -> "SEAFOOD".equals(f.cuisine()) || f.tags().contains(SEAFOOD_TAG) || f.ingredient("SEAFOOD")
                    || "seafood".equals(PlaceCategoryConstants.mealGroup(f.dish())) || "takoyaki".equals(f.dish())
                    || SEAFOOD_LEAVES.contains(f.leaf())
                    || f.sushi() || f.tags().stream().anyMatch(RAW_FISH_TAGS::contains)
                    || SEAFOOD_DISH_WORDS.stream().anyMatch(f::mentions);
            case RAW -> "raw_fish".equals(f.dish()) || RAW_LEAVES.contains(f.leaf()) || f.sushi()
                    || f.tags().stream().anyMatch(RAW_FISH_TAGS::contains) || RAW_DISH_WORDS.stream().anyMatch(f::mentions);
            case PORK -> f.pork();
            case BEEF -> f.beef();
            case CHICKEN -> f.chicken();
            case MEAT -> "MEAT".equals(f.cuisine()) || (f.guessesMeat() && f.grill()) || f.pork() || f.beef() || f.chicken()
                    || PlaceAttributes.MEAT_INGREDIENTS.stream().anyMatch(f::ingredient);
            default -> false;
        };
    }

    private static boolean isNightlife(String major, String category, List<String> tags, String placeRole,
                                       PlaceAttributes a, Facts f) {
        if ("BAR".equals(placeRole) || (a != null && "BAR".equals(a.venue()))) return true;
        // 공용 술집 판정은 식음료 카테고리를 전제로 해 "Public Art"의 pub에도 걸린다.
        if ((eats(major, placeRole, a) || f.category().contains("nightlife"))
                && PlaceCategoryConstants.isBar(category, tags)) return true;
        // 포차거리 같은 먹거리 시장은 시장으로 남기고, 이름은 식당만 본다.
        return "DINING".equals(major) && PlaceCategoryConstants.isBarName(f.names());
    }

    /** 보강 역할이 쇼핑이어도 빵집·초콜릿 가게 같은 식품 매장은 먹을거리를 사러 들르는 곳이라 남긴다. */
    private static boolean isShop(String placeRole, Facts f) {
        return "SHOPPING".equals(placeRole) && !f.category().contains("food and beverage retail");
    }

    /**
     * 산을 오래 오르는 곳만 등산이다. 보강 등산 난이도 LONG은 긴 해안 트레킹도 담아 산이라는 근거를 함께 보고,
     * 난이도가 없으면 일정의 긴 등산 기준(체류 {@value ScheduleTuning#LONG_HIKE_MIN_MINUTES}분)으로 본다.
     */
    private static boolean isHike(String name, String category, PlaceAttributes a, Integer recommendedMinutes,
                                  boolean knowsDuration, Facts f) {
        boolean mountain = CLIMB_WORDS.stream().anyMatch(f::mentions) || in(ACTIVITY_TYPES.get(HIKING), f.leaf())
                || "hill".equals(PlaceCategoryConstants.attributeSubTypeKey(a));
        if (a != null && a.hikeLevel() != null) {
            return "LONG".equals(a.hikeLevel())
                    && (mountain || "hill".equals(f.type()) || NATIONAL_PARK_LEAF.equals(f.leaf()));
        }
        if (!knowsDuration) return false;
        // 카테고리가 없는 이름(fallback 모델 출력)은 이름 끝 산·봉·오름으로는 낮은 곳과 구분되지 않는다.
        if (f.category().isEmpty()) return CLIMB_WORDS.stream().anyMatch(f::mentions);
        if (!mountain) return false;
        return isLong(name, category, recommendedMinutes);
    }

    /** 일정의 긴 등산({@link CandidateTraits#isLongHike}) 기준에 산길·숲길 카테고리를 더 본다. 산을 오르지 않는 긴 걷기도 담는다. */
    private static boolean isLongWalk(String name, String category, List<String> tags, PlaceAttributes a,
                                      Integer recommendedMinutes, boolean knowsDuration, Facts f) {
        if (a != null && a.hikeLevel() != null) return "LONG".equals(a.hikeLevel());
        if (!knowsDuration) return false;
        // 체류 시간을 모르는 fallback 이름은 걷는 코스라고 적힌 곳만 뺀다. "올레"는 시장 이름에도 붙는다.
        if (f.category().isEmpty()) return PlaceCategoryConstants.isHikingPlace(name, null);
        if (!PlaceCategoryConstants.isHikingPlace(name, tags) && !TRAIL_LEAVES.contains(f.leaf())) return false;
        return isLong(name, category, recommendedMinutes);
    }

    private static boolean isLong(String name, String category, Integer recommendedMinutes) {
        int minutes = recommendedMinutes != null && recommendedMinutes > 0 ? recommendedMinutes
                : PlaceCategoryConstants.heuristicVisitMinutes(name, category);
        return minutes >= ScheduleTuning.LONG_HIKE_MIN_MINUTES;
    }

    private static boolean isActivity(PlaceRestriction r, Facts f) {
        Set<String> types = ACTIVITY_TYPES.getOrDefault(r, Set.of());
        if (in(types, f.type()) || in(types, f.leaf())) return true;
        return !f.typedByAttribute() && ACTIVITY_WORDS.getOrDefault(r, List.of()).stream().anyMatch(f::mentions);
    }

    /** Set.of는 null을 물으면 예외를 던진다. */
    private static boolean in(Set<String> set, String value) {
        return value != null && set.contains(value);
    }

    private record Facts(String names, Set<String> tags, String leaf, String category, String type,
                         boolean typedByAttribute, String dish, String cuisine, PlaceAttributes attributes) {

        static Facts of(String name, String nameKo, String category, List<String> tags, String cuisine,
                        PlaceAttributes attributes) {
            String names = ((name == null ? "" : name) + " " + (nameKo == null ? "" : nameKo)).toLowerCase(Locale.ROOT);
            for (String excluded : NAME_EXCLUSIONS) names = names.replace(excluded, " ");
            Set<String> tagSet = tags == null ? Set.of() : tags.stream().filter(Objects::nonNull)
                    .map(t -> t.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            return new Facts(names, tagSet, PlaceCategoryConstants.leafCategory(category),
                    category == null ? "" : category.toLowerCase(Locale.ROOT),
                    subTypeOf(name, category, tags, attributes),
                    PlaceCategoryConstants.attributeSubTypeKey(attributes) != null,
                    PlaceCategoryConstants.dishKind(name, nameKo, tags),
                    cuisine == null ? null : cuisine.strip().toUpperCase(Locale.ROOT), attributes);
        }

        /** 보강 세부 유형이 있으면 이름 추정보다 먼저 쓴다("寺町京極商店街"는 상점가다). */
        private static String subTypeOf(String name, String category, List<String> tags,
                                           PlaceAttributes attributes) {
            String byAttribute = PlaceCategoryConstants.attributeSubTypeKey(attributes);
            return byAttribute != null ? byAttribute : PlaceCategoryConstants.subType(name, category, tags, null);
        }

        boolean sushi() {
            return SUSHI_WORDS.stream().anyMatch(this::mentions);
        }

        boolean mentions(String word) {
            return names.contains(word) || tags.stream().anyMatch(t -> t.contains(word));
        }

        // 닭갈비처럼 닭 요리에 붙은 구이 낱말은 돼지·소로 추정하지 않는다.
        boolean pork() {
            return tags.contains(PORK_TAG) || ingredient("PORK") || namesPork() || (guessesMeat() && grill() && !namesBeef()
                    && !BEEF_LEAVES.contains(leaf) && !chicken());
        }

        boolean beef() {
            return tags.contains(BEEF_TAG) || ingredient("BEEF") || namesBeef() || (guessesMeat() && !namesPork()
                    && (BEEF_LEAVES.contains(leaf) || (grill() && !chicken())));
        }

        boolean chicken() {
            return tags.contains(CHICKEN_TAG) || ingredient("CHICKEN") || mentionsAny(PlaceCategoryConstants.CHICKEN_WORDS)
                    || mentionsAny(CHICKEN_DISH_WORDS) || (guessesMeat() && CHICKEN_LEAVES.contains(leaf));
        }

        boolean grill() {
            return "MEAT".equals(cuisine) || EITHER_MEAT_LEAVES.contains(leaf)
                    || mentionsAny(PlaceCategoryConstants.GRILL_WORDS);
        }

        boolean ingredient(String kind) {
            return attributes != null && attributes.hasIngredient(kind);
        }

        /**
         * Foursquare는 흑돼지 구이집도 steakhouse로 두어 리프·구이 추정은 갈래 태그·보강 주재료처럼 재료를 아는 근거와
         * 반대쪽 고기 이름이 없을 때만 쓴다.
         */
        private boolean guessesMeat() {
            return !tags.contains(PORK_TAG) && !tags.contains(BEEF_TAG) && !tags.contains(CHICKEN_TAG)
                    && (attributes == null || !attributes.knowsIngredients());
        }

        private boolean namesPork() {
            return mentionsAny(PlaceCategoryConstants.PORK_WORDS) || mentionsAny(PORK_DISH_WORDS);
        }

        private boolean namesBeef() {
            return mentionsAny(PlaceCategoryConstants.BEEF_WORDS);
        }

        private boolean mentionsAny(List<String> words) {
            return words.stream().anyMatch(this::mentions);
        }
    }
}
