package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 일정의 장소가 사용자 요구(장소·경험·음식)를 채우는지. 짓는 쪽과 검사가 같은 기준을 써야 넣은 요구가 빠졌다고 판정되지 않는다. */
public final class WantMatcher {

    private static final Set<String> TIME_WORDS = Set.of("일출", "일몰", "야경", "야간", "산책", "구경", "투어", "밤바다");
    static final Pattern LOCALITY = Pattern.compile(".+[동읍면리]");
    static final Pattern BRACKETS = Pattern.compile("[(\\[（【].*?[)\\]）】]");
    /** 같은 식당으로 보는 이름 뒤 덧붙은 글자 수("진로집본점"). */
    private static final int BRANCH_SUFFIX_CHARS = 3;
    /** 음식 종류가 아니라 조리법·끼니·업종을 가리키는 말. 이 말만 맞는 식당은 요청한 음식을 낸다고 볼 수 없다. */
    private static final Set<String> GENERIC_DISH = Set.of("구이", "찌개", "요리", "식사", "정식", "맛집",
            "料理", "定食", "食堂", "食事", "居酒屋", "レストラン", "餐厅", "饭店", "小吃");
    /** 음식 종류 전체를 가리키는 말. 다른 음식의 다른 표기로 오면 그 종류 식당이 모두 요청 음식을 내는 것이 된다. */
    private static final Set<String> DISH_CATEGORY = Set.of("해산물", "海鮮");

    private WantMatcher() {}

    static boolean matches(TripConditions.Want want, PlaceCandidate place) {
        CandidatePolicy.Role role = CandidatePolicy.roleOf(place);
        if (want.food() != null) {
            boolean eatery = role == CandidatePolicy.Role.MEAL || role == CandidatePolicy.Role.CAFE
                    || role == CandidatePolicy.Role.BAR;
            return eatery && serves(want.food(), want.foodAliases(), want.satisfy(), place);
        }
        if (matches(want.text(), place)) return true;
        for (String s : want.satisfy() == null ? List.<String>of() : want.satisfy()) {
            if (matches(s, place)) return true;
        }
        // 현지어 이름은 모델이 추측한 표기라 일부만 맞으면 이름을 딴 근처 가게("清水寺前 湯豆腐")도 요구를 채운다.
        String bare = PlaceNames.bareKey(Objects.toString(place.name(), ""));
        return want.localNames().stream().map(PlaceNames::bareKey).anyMatch(l -> l.length() >= 2 && l.equals(bare));
    }

    /** 요청한 곳 안의 장소(엑스포과학공원의 한빛탑)에 갔으면 그곳에 간 것이다. */
    static boolean partOf(TripConditions.Want want, PlaceFactsReader.PlaceFact fact) {
        if (want.food() != null || fact == null || fact.partOf() == null || fact.partOf().isBlank()) return false;
        String parent = key(fact.partOf());
        return parent.length() >= 2 && (key(want.text()).contains(parent) || parent.contains(key(want.text())));
    }

    /**
     * 장소 이름이나 식당 메뉴에 요청한 말이 들어 있는지. 일출·야경 같은 시간 말은 떼고 본다. 해외 장소는 요청과 이름 규칙이
     * 한국어라 한국어 이름으로 본다.
     */
    static boolean matches(String wantText, PlaceCandidate place) {
        // "성산일출봉 일출"의 뒤쪽 '일출'만 시각 말이다. 이름 속 '일출'(일출봉)은 남긴다.
        String cleaned = Arrays.stream(wantText.trim().split("\\s+"))
                .filter(w -> !TIME_WORDS.contains(w)).collect(Collectors.joining(" "));
        for (String alias : PlaceNames.variants(cleaned)) {
            if (matchesName(alias, place)) return true;
        }
        return false;
    }

    private static boolean matchesName(String cleaned, PlaceCandidate place) {
        String want = key(cleaned);
        if (want.isEmpty()) return false;
        String name = key(BRACKETS.matcher(Objects.toString(place.displayName(), "")).replaceAll(""));
        if (name.contains(want) || (want.length() >= 3 && want.contains(name) && name.length() >= 3)) return true;
        if (DraftPlaceResolver.dropsInfix(name, want)) return true;
        // "고소동 벽화마을"과 "고소동 천사벽화마을"처럼 낱말이 모두 이름에 들어 있으면 같은 곳이다.
        List<String> words = Arrays.stream(cleaned.trim().split("\\s+")).map(WantMatcher::key)
                .filter(w -> w.length() >= 2).toList();
        if (words.size() >= 2 && words.stream().allMatch(name::contains)) return true;
        // 앞에 붙은 동네 이름은 장소 이름에 없을 수 있다("고소동 벽화마을"과 "여수천사벽화마을").
        List<String> named = words.stream().filter(w -> !LOCALITY.matcher(w).matches()).toList();
        if (words.size() >= 2 && named.size() < words.size() && !named.isEmpty()
                && String.join("", named).length() >= 3 && named.stream().allMatch(name::contains)) return true;
        return CandidatePolicy.roleOf(place) == CandidatePolicy.Role.MEAL && serves(cleaned, place);
    }

    /**
     * 메뉴로 확인되거나, 메뉴 정보가 없는 식당이 그 음식으로 이름난 곳(모델이 적은 식당)과 이름이 같으면 요청 음식을 낸다고
     * 본다. 메뉴가 있는데 그 음식이 없으면 이름이 같아도 아니다.
     *
     * @param aliases 같은 음식의 다른 표기. 해외 식당은 이름이 현지어이고 태그는 한국에서 부르는 이름이라 요청한 말과 다르다
     */
    static boolean serves(String food, List<String> aliases, List<String> named, PlaceCandidate c) {
        if (serves(food, c)) return true;
        if (aliases != null && aliases.stream().map(WantMatcher::key)
                .anyMatch(a -> !DISH_CATEGORY.contains(a) && servesDish(a, c))) return true;
        if (named == null || c.attributes() != null && c.attributes().official() != null
                && !c.attributes().official().isEmpty()) return false;
        String bare = PlaceNames.bareKey(c.name());
        return named.stream().map(PlaceNames::bareKey).anyMatch(n -> n.length() >= 2
                && (bare.equals(n) || bare.startsWith(n) && bare.length() - n.length() <= BRANCH_SUFFIX_CHARS));
    }

    /**
     * 식당 이름·메뉴·음식 종류에 그 음식이 있는지. 일정에 붙은 이름표나 모델이 적은 식당 이름은 보지 않는다. 피순대를
     * 찾아 넣은 칸에 통닭집이 들어가도 이름표는 피순대다.
     */
    static boolean serves(String food, PlaceCandidate c) {
        String[] words = Objects.toString(food, "").trim().split("\\s+");
        // "모슬포 고등어회"처럼 앞에 붙은 지명은 식당 이름에 없어도 된다. 음식 이름은 마지막 낱말이다.
        return servesDish(key(food), c) || words.length > 1 && servesDish(key(words[words.length - 1]), c);
    }

    private static boolean servesDish(String dish, PlaceCandidate c) {
        if (dish.length() < 2) return false;
        if (GENERIC_DISH.contains(dish)) return false;
        List<String> texts = new java.util.ArrayList<>(List.of(Objects.toString(c.name(), ""),
                Objects.toString(c.nameKo(), ""), Objects.toString(c.cuisine(), "")));
        if (c.tags() != null) texts.addAll(c.tags());
        if (c.attributes() != null && c.attributes().official() != null) texts.addAll(c.attributes().official().values());
        return texts.stream().anyMatch(t -> PlaceNames.mentions(t, dish));
    }

    private static String key(String s) {
        return PlaceNames.exactKey(s);
    }
}
