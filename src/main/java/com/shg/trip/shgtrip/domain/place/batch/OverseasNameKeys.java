package com.shg.trip.shgtrip.domain.place.batch;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 해외 장소 이름과 Wikidata 이름(여러 언어 라벨·별칭)을 비교하는 키.
 *
 * <p>한국 규칙({@link WikidataSitelinksJob#nameKey})의 "항목 이름 앞부분 묶음"은 쓰지 않는다. 한자·가나 이름은
 * 띄어쓰기가 없어 묶음이 생기지 않고, 라틴 문자 이름의 앞 단어는 대개 일반명사("Chợ", "Bãi biển")다.
 */
final class OverseasNameKeys {

    /** "豫园"·"故宫"처럼 두 글자 명소가 많다. */
    static final int MIN_CJK_KEY_LENGTH = 2;
    static final int MIN_LATIN_KEY_LENGTH = 3;

    /**
     * 병기 이름의 뒤 묶음이 항목 이름에 붙여도 되는 분류어("龍安寺 Ryoan-ji Temple"의 temple).
     * 시설어(hall·deck·gate·center)는 딸린 시설이라 넣지 않는다.
     */
    static final Set<String> TYPE_WORDS = Set.of(
            "temple", "shrine", "jinja", "jingu", "taisha", "pagoda", "church", "cathedral", "mosque",
            "castle", "palace", "museum", "park", "garden", "gardens", "market", "nightmarket",
            "beach", "island", "lake", "mountain", "falls", "waterfall", "bridge", "street", "oldstreet", "zoo",
            "aquarium");

    /** 괄호는 안의 병기·주석째 지운다. 낫표·겹낫표·꺾쇠는 이름 자체를 감싸는 경우가 많아 기호만 뗀다. */
    private static final String OPENERS = "\\[(【〔";
    private static final String CLOSERS = "\\])】〕";
    private static final Pattern INNERMOST_BRACKET =
            Pattern.compile("[" + OPENERS + "][^" + OPENERS + CLOSERS + "]*[" + CLOSERS + "]");
    private static final Pattern UNCLOSED = Pattern.compile("[" + OPENERS + "].*$");
    private static final Pattern SEPARATORS =
            Pattern.compile("[\\s·・,./_:|&'’\"“”\\-–—~〜「」『』《》〈〉" + CLOSERS + "]+");
    /** 지점명 단어. 추출기(_stem_expr)가 같은 상호를 셀 때 떼는 꼴과 같다. */
    private static final Pattern BRANCH_SUFFIX = Pattern.compile("(店|門市|门市)$");

    private OverseasNameKeys() {}

    /**
     * 장소 쪽 키.
     *
     * @param rest   병기 이름의 첫 묶음 키일 때 나머지 묶음, 아니면 null
     * @param branch 끝 지점명 단어("…店")를 뗀 키인가
     */
    record PlaceKey(String key, String rest, boolean branch) {

        static PlaceKey whole(String key) {
            return new PlaceKey(key, null, false);
        }

        boolean exact() {
            return rest == null && !branch;
        }

        /**
         * 지점명을 뗀 키는 식음료 항목(체인)에만 붙는다("黒門市場 鮮魚店"은 시장이 아니다). 첫 묶음 키는 뒤 묶음이
         * 항목 이름 그대로이거나 항목 이름 + {@link #TYPE_WORDS}여야 한다("東大寺 Todaiji Nandaimon"은 남대문이다).
         */
        boolean accepts(Set<String> itemKeys, boolean foodItem) {
            if (branch && !foodItem) return false;
            if (rest == null) return true;
            for (String k : itemKeys) {
                if (rest.equals(k) || (rest.startsWith(k) && TYPE_WORDS.contains(rest.substring(k.length())))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 이름 전체 키, 문자 종류가 바뀌는 병기 이름("外滩 The Bund")의 첫 묶음 키, 단어가 둘 이상일 때 끝 지점명 단어를
     * 뗀 키("一蘭 天神西通り店" → "一蘭")를 낸다. 같은 키가 여러 종류로 나오면 이름 전체 키가 남는다.
     */
    static List<PlaceKey> placeKeys(String name) {
        List<String> tokens = tokens(name);
        Map<String, PlaceKey> keys = new LinkedHashMap<>();
        add(keys, PlaceKey.whole(String.join("", tokens)));

        StringBuilder first = new StringBuilder();
        char firstScript = 'N';
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            char script = scriptOf(token);
            // 숫자만 있는 단어("Taipei 101"의 101)는 앞 묶음에 붙인다.
            if (script != 'N' && firstScript != 'N' && script != firstScript) {
                add(keys, new PlaceKey(first.toString(), String.join("", tokens.subList(i, tokens.size())), false));
                break;
            }
            if (firstScript == 'N') firstScript = script;
            first.append(token);
        }

        if (tokens.size() >= 2) {
            String last = tokens.get(tokens.size() - 1);
            if (BRANCH_SUFFIX.matcher(last).find()) {
                add(keys, new PlaceKey(String.join("", tokens.subList(0, tokens.size() - 1)), null, true));
            }
        }
        return List.copyOf(keys.values());
    }

    private static void add(Map<String, PlaceKey> keys, PlaceKey key) {
        if (longEnough(key.key())) keys.putIfAbsent(key.key(), key);
    }

    /** Wikidata 이름들(라벨·별칭) → 이름 전체 키. */
    static Set<String> itemKeys(Collection<String> names) {
        Set<String> keys = new LinkedHashSet<>();
        for (String n : names) {
            String key = String.join("", tokens(n));
            if (longEnough(key)) keys.add(key);
        }
        return keys;
    }

    /**
     * 장소가 이 항목에 붙는 이름인가. 인기도 매칭({@link WikidataSitelinksJob#assignOverseas})과 같은 조건이라
     * 명소 적재가 "이미 있다"고 본 명소는 그 행이 인기도를 받는다.
     */
    static boolean matches(List<PlaceKey> placeKeys, Set<String> itemKeys, boolean foodItem) {
        for (PlaceKey p : placeKeys) {
            if (itemKeys.contains(p.key()) && p.accepts(itemKeys, foodItem)) return true;
        }
        return false;
    }

    /**
     * 그 나라에서 쓰는 이름. 간체·번체 라벨이 둘 다 있으면 중국은 간체, 대만은 번체를 쓴다.
     * 현지어 라벨이 없으면 영어, 그것도 없으면 언어 코드 순 첫 라벨(실행마다 같은 이름이 나오게), 라벨이 없으면 null이다.
     */
    static String localLabel(Map<String, String> labels, String country) {
        List<String> order = switch (country == null ? "" : country) {
            case "JP" -> List.of("ja");
            case "CN" -> List.of("zh-hans", "zh-cn", "zh", "zh-hant");
            case "TW" -> List.of("zh-hant", "zh-tw", "zh", "zh-hk", "zh-hans");
            case "VN" -> List.of("vi");
            default -> List.of();
        };
        for (String lang : order) {
            if (labels.containsKey(lang)) return labels.get(lang);
        }
        if (labels.containsKey("en")) return labels.get("en");
        return labels.keySet().stream().sorted().findFirst().map(labels::get).orElse(null);
    }

    /** 괄호는 안쪽부터 지운다. "空港 (Kansai Airport (KIX))"처럼 겹친 괄호가 있다. */
    static List<String> tokens(String name) {
        if (name == null || name.isBlank()) return List.of();
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String previous;
        do {
            previous = s;
            s = INNERMOST_BRACKET.matcher(s).replaceAll(" ");
        } while (!s.equals(previous));
        s = foldLatin(UNCLOSED.matcher(s).replaceAll(" "));
        List<String> tokens = new ArrayList<>();
        for (String part : SEPARATORS.split(s)) {
            if (!part.isEmpty()) tokens.add(part);
        }
        return tokens;
    }

    /**
     * 라틴 문자만 기호를 뗀다("Bến Thành" → "ben thanh"). 가나에 똑같이 하면 탁점이 빠져 "が"가 "か"가 된다.
     * FSQ는 베트남 이름을 기호 없이 적은 행이 섞여 있다.
     */
    private static String foldLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            if (c == 'đ') {
                sb.append('d');
            } else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) {
                String base = Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFD);
                base.codePoints().filter(b -> Character.getType(b) != Character.NON_SPACING_MARK).forEach(sb::appendCodePoint);
            } else {
                sb.appendCodePoint(c);
            }
        });
        return sb.toString();
    }

    private static boolean longEnough(String key) {
        int min = hasCjk(key) || hasHangul(key) ? MIN_CJK_KEY_LENGTH : MIN_LATIN_KEY_LENGTH;
        return key.codePointCount(0, key.length()) >= min;
    }

    /** C = 한자·가나, K = 한글, L = 그 밖의 문자, N = 문자 없음(숫자·기호). */
    private static char scriptOf(String token) {
        if (hasCjk(token)) return 'C';
        if (hasHangul(token)) return 'K';
        return token.codePoints().anyMatch(Character::isLetter) ? 'L' : 'N';
    }

    static boolean hasCjk(CharSequence s) {
        return s.codePoints().anyMatch(OverseasNameKeys::isCjk);
    }

    private static boolean isCjk(int c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || c == 'ー';
    }

    private static boolean hasHangul(CharSequence s) {
        return s.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL);
    }
}
