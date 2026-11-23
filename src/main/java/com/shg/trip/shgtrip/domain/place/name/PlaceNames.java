package com.shg.trip.shgtrip.domain.place.name;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 장소 이름 비교 규칙 한 벌. 근거마다 믿을 수 있는 정도가 달라 하나의 강한 키로 합치지 않는다.
 * 정확 키는 DB 함수 {@code place_name_key}·place-pipeline {@code names.exact_key}와 같은 규칙이고,
 * 세 구현은 계약 파일 {@code place-names/exact-keys.tsv}로 검사한다.
 */
public final class PlaceNames {

    private PlaceNames() {}

    /** 이름이 맞은 근거. 뒤로 갈수록 약하다. */
    public enum Match {
        /** 정확 키가 같다. */
        EXACT,
        /** 출처가 준 다른 이름(괄호 안 한글 이름·한국어 이름·합쳐진 행의 이름)이 같다. */
        ALIAS,
        /** 표기 변형 규칙(일본어 한글 표기·같은 뜻 접미어)으로 같다. */
        VARIANT,
        /** 앞부분·포함 관계로만 맞는다. */
        FUZZY
    }

    /** 글자·숫자가 아닌 것. 이모지 이체자 선택자·폭 없는 공백 같은 결합·서식 문자도 지워 DB [:alnum:]과 같게 한다. */
    private static final Pattern DROPPED = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern BRACKETS = Pattern.compile("[(\\[（【]([^)\\]）】]*)[)\\]）】]");
    private static final Pattern TRAILING_NOTE = Pattern.compile("\\s*[(（\\[][^()（）\\[\\]]*[)）\\]]\\s*$");
    /** 영어 이름 꼬리. 괄호로 감싼 병기("東京ドームシティ (Tokyo Dome City)")도 받는다. */
    private static final Pattern LATIN_TAIL = Pattern.compile("[\\s(（]+[A-Za-z0-9][A-Za-z0-9 .,'’&()（）/-]*$");
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final Pattern WORD_SEPARATORS = Pattern.compile("[\\p{P}\\p{S}\\p{Z}\\p{C}\\s]+");

    /** 전각·대소문자 차이를 없애고 글자·숫자만 남긴 키. null이면 빈 문자열이다. */
    public static String exactKey(String text) {
        if (text == null) return "";
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return DROPPED.matcher(folded).replaceAll("");
    }

    /** 괄호 안 한자·영문·설명을 뺀 이름의 키. 다 빠지면 원래 이름의 키다("불국사 (佛國寺)" → 불국사). */
    public static String bareKey(String name) {
        String bare = name == null ? "" : BRACKETS.matcher(name).replaceAll("");
        String key = exactKey(bare);
        return key.isEmpty() ? exactKey(name) : key;
    }

    /**
     * 괄호 안이 같은 곳의 다른 한글 이름일 때 그 키("월정리해변(월정리해수욕장)" → 월정리해수욕장).
     * 국내 이름의 괄호는 위치·상위 장소("천마총(대릉원)", "Cath Kidston (원마운트)")인 때가 많아 바깥 이름과 앞 두 글자가
     * 같을 때만 받는다.
     */
    public static List<String> bracketAliasKeys(String name) {
        if (name == null) return List.of();
        String outside = exactKey(BRACKETS.matcher(name).replaceAll(""));
        List<String> out = new ArrayList<>(1);
        Matcher m = BRACKETS.matcher(name);
        while (m.find()) {
            String inner = m.group(1);
            if (!HANGUL.matcher(inner).find()) continue;
            String key = exactKey(inner);
            if (key.length() >= ALIAS_PREFIX && outside.length() >= ALIAS_PREFIX
                    && key.regionMatches(0, outside, 0, ALIAS_PREFIX)) out.add(key);
        }
        return out;
    }

    /** 괄호 안을 다른 이름으로 볼 때 바깥 이름과 같아야 하는 앞 글자 수. */
    private static final int ALIAS_PREFIX = 2;

    /** 이름 끝 괄호 설명("용지봉 (634m)")을 뗀 이름. */
    public static String withoutTrailingNote(String name) {
        return name == null ? "" : TRAILING_NOTE.matcher(name.strip()).replaceFirst("");
    }

    /** 한글·한자 이름 뒤에 붙은 영어 병기를 뗀 이름("해동용궁사 Haedong Yonggungsa"). 라틴 문자 이름은 그대로다. */
    public static String withoutLatinTail(String name) {
        if (name == null) return "";
        return name.chars().anyMatch(ch -> ch >= 0x80) ? LATIN_TAIL.matcher(name).replaceFirst("") : name;
    }

    /** 한글·한자 이름 뒤에 영문·숫자 꼬리만 붙었는가("해동용궁사 Haedong Yonggungsa Temple"). */
    public static boolean isBilingualOf(String name, String base) {
        return base.chars().anyMatch(ch -> ch >= 0x80) && name.startsWith(base)
                && LATIN_TAIL.matcher(name.substring(base.length())).matches();
    }

    /** 일본 지명의 한국어 표기 차이. 모델과 여행자는 "츠키지·큐슈"로, 장소 데이터는 "쓰키지·규슈"로 적기도 한다. */
    private static final List<String[]> JAPANESE_SPELLINGS = List.of(
            new String[]{"츠", "쓰"}, new String[]{"큐", "규"}, new String[]{"덴", "텐"}, new String[]{"텐", "덴"});

    /**
     * 같은 곳을 부르는 이름들. 첫 이름은 받은 그대로다. 해변과 해수욕장은 같은 곳이라 "경포해변"의 장소 이름이
     * "경포해수욕장"일 수 있고, 일본 지명은 표기가 갈린다.
     */
    public static List<String> variants(String name) {
        String n = name == null ? "" : name.strip();
        List<String> beach = n.endsWith("해수욕장") ? List.of(n, n.substring(0, n.length() - 4) + "해변")
                : n.endsWith("해변") ? List.of(n, n.substring(0, n.length() - 2) + "해수욕장") : List.of(n);
        Set<String> out = new LinkedHashSet<>(beach);
        for (String b : beach) {
            for (String[] pair : JAPANESE_SPELLINGS) {
                if (b.contains(pair[0])) out.add(b.replace(pair[0], pair[1]));
            }
        }
        return List.copyOf(out);
    }

    /** 이름 끝의 같은 뜻 낱말. 장소 데이터와 여행자가 같은 곳을 다른 낱말로 부른다("행궁동 벽화골목"·"행궁동 벽화마을"). */
    private static final List<List<String>> SAME_SUFFIXES = List.of(
            List.of("벽화골목", "벽화마을", "벽화거리"),
            List.of("전망타워", "전망대"));

    /** 다음 순서로 바꾼 가지 수 상한. 일본어 표기 변형은 낱말 수에 따라 가지가 늘어 조회 수를 묶어 둔다. */
    private static final int MAX_SPELLING_VARIANTS = 12;

    /**
     * {@link #variants}보다 근거가 약한 표기 변형. 접미어 동의어와 일본 지명의 외래어 표기법·관용 표기 차이
     * (낱말 첫소리 거센소리↔예사소리 "타누키코지·다누키코지", ㅈ·ㅊ 뒤 이중모음 "조죠지·조조지")다.
     * 받은 이름과 {@link #variants} 결과는 넣지 않는다.
     *
     * @param japanese 일본 여행지일 때만 일본어 표기 변형을 만든다. 국내 이름에 쓰면 다른 장소 이름과 우연히 맞는다
     */
    public static List<String> spellingVariants(String name, boolean japanese) {
        String n = name == null ? "" : name.strip();
        Set<String> out = new LinkedHashSet<>();
        for (List<String> group : SAME_SUFFIXES) {
            for (String suffix : group) {
                if (!n.endsWith(suffix)) continue;
                String stem = n.substring(0, n.length() - suffix.length());
                for (String other : group) {
                    if (!other.equals(suffix)) out.add(stem + other);
                }
            }
        }
        if (japanese) {
            out.addAll(JapaneseHangul.variants(n, MAX_SPELLING_VARIANTS));
        }
        out.removeAll(variants(n));
        return List.copyOf(out);
    }

    /**
     * text에 word가 들어 있는가. 낱말 중간에서 시작해 다음 낱말로 넘어가는 일치는 빼서 "멘타이코"가 "라멘 타이코"에 걸리지
     * 않게 한다. 한 낱말 안("조점례남문피순대"의 피순대)이나 낱말 첫머리에서 이어지는 일치("모슬포 고등어 회"의 고등어회)는 맞다.
     */
    public static boolean mentions(String text, String word) {
        String w = exactKey(word);
        if (w.isEmpty() || text == null) return false;
        String[] tokens = WORD_SEPARATORS.split(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        StringBuilder joined = new StringBuilder();
        List<Integer> starts = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            starts.add(joined.length());
            joined.append(token);
        }
        String all = joined.toString();
        for (int at = all.indexOf(w); at >= 0; at = all.indexOf(w, at + 1)) {
            int token = tokenAt(starts, at);
            int tokenEnd = token + 1 < starts.size() ? starts.get(token + 1) : all.length();
            if (starts.get(token) == at || at + w.length() <= tokenEnd) return true;
        }
        return false;
    }

    private static int tokenAt(List<Integer> starts, int offset) {
        int token = 0;
        while (token + 1 < starts.size() && starts.get(token + 1) <= offset) token++;
        return token;
    }
}
