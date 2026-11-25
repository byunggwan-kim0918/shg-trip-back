package com.shg.trip.shgtrip.domain.place.name;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 초안의 현지어 이름으로 찾은 장소가 초안의 한국어 이름과 같은 곳인지 확인한다. 현지어 이름은 모델이 추측한 표기라, 그 이름과
 * 똑같은 다른 장소(센닌자카 전망대를 拓真館으로)가 데이터에 있으면 이름만으로 잇게 된다.
 *
 * <p>한국어 이름에서 도시 이름과 일반어(전망대·신사, 현지 발음을 옮긴 몬·가와)를 떼고 남은 고유 음절이 장소의 근거로 설명돼야
 * 같은 곳으로 본다. 근거는 장소의 한글 읽기(규칙 읽기·한국어 이름), 한자의 한국 한자음·일본어 읽기, 번역어 표, 영어 낱말이다.
 * 이름의 중심 일반어가 장소 유형과 어긋나면(전망대인데 미술관) 같은 곳이 아니다. 표는 {@code names/} 리소스다.
 */
public final class LocalNameCheck {

    /** 고유 음절 가운데 근거로 설명돼야 하는 몫. 한두 음절이 다른 읽기(헤이나이·히라우치)는 다른 곳으로 본다. */
    static final double COVERED_SHARE = 0.75;
    /** 영어 낱말과 한국어 표기의 자음이 이만큼 같으면 그 낱말을 옮긴 것이다(햅 파이브 = HEP FIVE). */
    private static final double ENGLISH_CONSONANT_RATIO = 0.8;

    private static final Pattern SPLIT = Pattern.compile("[\\s·・,()/&]+");
    private static final Pattern PARTICLE = Pattern.compile("(의|은|는)$");
    private static final Pattern IGNORED_IN_NAME = Pattern.compile("[ぁ-ゖァ-ヺー\\s・()（）A-Za-z0-9\\-&]");
    private static final Pattern NAME_PUNCT = Pattern.compile("[\\s・()（）\\-]");
    private static final Pattern LONG_VOWEL = Pattern.compile("aa|ii|uu|ee|oo|ou|ei");
    private static final Pattern SIBILANT = Pattern.compile("sh|ch|ts|j|z");
    /** しゃ·しゅ·しょ는 샤·슈·쇼로, じゃ·ちゃ는 자·차로 적는다(국립국어원 일본어 표기). 쇼와 소는 다른 이름이다. */
    private static final Pattern SH_GLIDE = Pattern.compile("sh([aou])");

    private record Generic(String group, boolean koreanWord, List<String> localChars, List<String> categoryKeywords,
                           List<String> englishWords) {}

    private record Tables(Map<String, Generic> korean, Map<String, Generic> transliterated, Map<String, List<String>> words,
                          Map<Character, List<String>> hanjaKo, Map<Character, List<String>> hanjaJa,
                          Map<Character, String> kana, List<String> localGenericChars) {}

    private static final Tables T = load();

    private LocalNameCheck() {}

    /**
     * @param korean      초안의 한국어 이름
     * @param cityNames   한국어 도시·여행지 이름. 이름 앞뒤의 도시 이름은 장소를 가리는 근거가 아니다
     * @param placeName   찾은 장소의 현지 이름
     * @param category    장소 카테고리 경로. null이면 유형 대조를 건너뛴다
     * @param readings    믿을 만한 한글 읽기(규칙 읽기·Wikidata 한국어 이름)
     * @param weak        보강이 지은 한국어 이름처럼 틀릴 수 있는 읽기
     * @param english     로마자로 읽히지 않는 영어 낱말
     */
    public static boolean confirms(String korean, Collection<String> cityNames, String placeName, String category,
                                   Collection<String> readings, Collection<String> weak, Collection<String> english) {
        if (korean == null || placeName == null) return false;
        List<String> cities = cityNames.stream().filter(c -> c != null && c.length() >= 2).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        Parsed p = parse(korean, cities);
        String cat = category == null ? "" : category;
        List<String> eng = new ArrayList<>(english);
        eng.addAll(latinWords(placeName));
        for (Mark g : p.generics()) {
            if (g.head() && g.generic().koreanWord() && g.generic().categoryKeywords().stream().noneMatch(cat::contains)
                    && g.generic().localChars().stream().noneMatch(placeName::contains) && !englishGeneric(g.generic(), eng)) {
                return false;
            }
        }
        boolean corresponds = p.generics().stream().anyMatch(g -> g.generic().localChars().stream()
                .anyMatch(placeName::contains) || englishGeneric(g.generic(), eng));
        List<String> reliable = new ArrayList<>(readings);
        if (hasHangul(placeName)) reliable.add(placeName);
        List<String> forms = new ArrayList<>();
        reliable.forEach(r -> forms.add(withoutCities(r, cities)));
        weak.forEach(r -> forms.add(withoutCities(r, cities)));

        List<Token> tokens = p.tokens().stream().filter(t -> t.text().length() >= 2).toList();
        if (tokens.isEmpty()) return corresponds && onlyCityAndGeneric(korean, cities, placeName, p.tokens());

        Set<Integer> englishHit = englishHits(p.tokens(), eng);
        int total = 0;
        int covered = 0;
        for (Token t : tokens) {
            boolean[] c = coverByReadings(t.text(), forms);
            boolean[] h = coverByHanja(t.text(), placeName);
            boolean all = englishHit.contains(p.tokens().indexOf(t));
            for (int i = 0; i < c.length; i++) {
                if (!t.core()[i]) continue;
                total++;
                if (all || c[i] || h[i]) covered++;
            }
        }
        return total > 0 && (double) covered / total >= COVERED_SHARE;
    }

    private record Token(String text, boolean[] core) {}

    private record Mark(Generic generic, boolean head) {}

    private record Parsed(List<Token> tokens, List<Mark> generics) {}

    private static Parsed parse(String korean, List<String> cities) {
        List<Token> tokens = new ArrayList<>();
        List<Mark> generics = new ArrayList<>();
        String[] raw = SPLIT.split(korean.strip());
        for (int idx = 0; idx < raw.length; idx++) {
            String t = raw[idx];
            if (t.isEmpty() || cities.contains(t)) continue;
            for (String c : cities) {
                if (t.startsWith(c) && t.length() > c.length() + 1) {
                    t = t.substring(c.length());
                    break;
                }
            }
            t = PARTICLE.matcher(t).replaceAll("");
            boolean head = idx == raw.length - 1;
            String kw = suffix(t, T.korean().keySet(), true);
            if (kw != null) {
                generics.add(new Mark(T.korean().get(kw), head));
                t = PARTICLE.matcher(t.substring(0, t.length() - kw.length())).replaceAll("");
                if (t.isEmpty()) continue;
            }
            boolean[] core = new boolean[t.length()];
            java.util.Arrays.fill(core, true);
            String tr = suffix(t, T.transliterated().keySet(), false);
            if (tr != null) {
                generics.add(new Mark(T.transliterated().get(tr), false));
                for (int i = t.length() - tr.length(); i < t.length(); i++) core[i] = false;
                boolean any = false;
                for (boolean b : core) any |= b;
                if (!any) java.util.Arrays.fill(core, true);
            }
            StringBuilder text = new StringBuilder();
            List<Boolean> mask = new ArrayList<>();
            for (int i = 0; i < t.length(); i++) {
                if (isHangul(t.charAt(i))) {
                    text.append(t.charAt(i));
                    mask.add(core[i]);
                }
            }
            if (text.isEmpty() || cities.contains(text.toString())) continue;
            boolean[] m = new boolean[mask.size()];
            for (int i = 0; i < m.length; i++) m[i] = mask.get(i);
            tokens.add(new Token(text.toString(), m));
        }
        return new Parsed(tokens, generics);
    }

    private static String suffix(String t, Set<String> words, boolean wholeToo) {
        String best = null;
        for (String w : words) {
            boolean hit = (wholeToo && t.equals(w)) || (t.endsWith(w) && t.length() > w.length());
            if (hit && (best == null || w.length() > best.length())) best = w;
        }
        return best;
    }

    /** 이름이 도시 이름과 일반어뿐이면 장소 이름에서 일반어 글자와 도시 이름 읽기를 빼고 남는 글자가 없어야 한다. */
    private static boolean onlyCityAndGeneric(String korean, List<String> cities, String placeName, List<Token> shortTokens) {
        List<Character> rest = new ArrayList<>();
        for (char ch : placeName.toCharArray()) if (!NAME_PUNCT.matcher(String.valueOf(ch)).matches()) rest.add(ch);
        for (String raw : SPLIT.split(korean.strip())) {
            String city = cities.stream().filter(raw::startsWith).findFirst().orElse(null);
            if (city == null) continue;
            int[] span = align(city, rest, 1);
            if (span != null) rest.subList(span[0], span[1]).clear();
        }
        StringBuilder s = new StringBuilder();
        rest.forEach(s::append);
        String left = s.toString();
        for (String j : T.localGenericChars()) left = left.replace(j, "");
        left = IGNORED_IN_NAME.matcher(left).replaceAll("");
        Set<String> syllables = new HashSet<>();
        for (Token t : shortTokens) for (char c : t.text().toCharArray()) syllables.add(String.valueOf(c));
        StringBuilder unexplained = new StringBuilder();
        for (char ch : left.toCharArray()) {
            List<String> ko = T.hanjaKo().get(ch);
            if (ko == null || ko.stream().noneMatch(syllables::contains)) unexplained.append(ch);
        }
        return unexplained.isEmpty();
    }

    private static boolean englishGeneric(Generic g, List<String> english) {
        for (String e : english) {
            for (String w : g.englishWords()) if (e.toLowerCase(Locale.ROOT).equals(w)) return true;
        }
        return false;
    }

    private static List<String> latinWords(String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Za-z]{3,}").matcher(name);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static String withoutCities(String s, List<String> cities) {
        String out = s;
        for (String c : cities) out = out.replace(c, " ");
        return out;
    }

    private static final String[] INITIAL = {"K", "K", "N", "T", "T", "R", "M", "P", "P", "S", "S", "", "S", "S", "S", "K",
            "T", "P", "H"};
    private static final String[] VOWEL = {"a", "e", "ya", "e", "o", "e", "yo", "e", "o", "wa", "we", "we", "yo", "u", "wo",
            "we", "wi", "yu", "u", "i", "i"};

    /** 표기 차이(쓰/츠, ㅡ/ㅜ, 거센소리)를 지운 음절 코드. */
    private static String syllable(char c) {
        int o = c - 0xAC00;
        int t = o % 28;
        String fin = t == 4 || t == 21 || t == 16 ? "n" : t == 8 ? "r" : "";
        return INITIAL[o / 588] + VOWEL[(o % 588) / 28] + fin;
    }

    private static List<String> syllables(String s) {
        List<String> out = new ArrayList<>();
        for (char c : s.toCharArray()) if (isHangul(c)) out.add(syllable(c));
        return out;
    }

    /** 두 음절 이상 이어서 같은 부분만 근거로 센다. 한 음절은 다른 이름에서도 흔히 겹친다. */
    private static boolean[] coverByReadings(String token, List<String> forms) {
        List<String> a = syllables(token);
        boolean[] cov = new boolean[a.size()];
        for (String f : forms) {
            List<String> b = syllables(f.replaceAll("\\s", ""));
            for (int[] blk : matchingBlocks(a, b)) {
                if (blk[2] >= 2) for (int i = blk[0]; i < blk[0] + blk[2]; i++) cov[i] = true;
            }
        }
        return cov;
    }

    /** difflib.SequenceMatcher.get_matching_blocks와 같은 규칙(가장 긴 일치를 a·b 앞쪽부터 재귀로)이다. */
    static List<int[]> matchingBlocks(List<String> a, List<String> b) {
        List<int[]> out = new ArrayList<>();
        blocks(a, b, 0, a.size(), 0, b.size(), out);
        out.sort(Comparator.comparingInt((int[] x) -> x[0]));
        return out;
    }

    private static void blocks(List<String> a, List<String> b, int alo, int ahi, int blo, int bhi, List<int[]> out) {
        int bestI = alo, bestJ = blo, bestSize = 0;
        int[] prev = new int[bhi - blo + 1];
        for (int i = alo; i < ahi; i++) {
            int[] cur = new int[bhi - blo + 1];
            for (int j = blo; j < bhi; j++) {
                if (a.get(i).equals(b.get(j))) {
                    int k = prev[j - blo] + 1;
                    cur[j - blo + 1] = k;
                    if (k > bestSize) {
                        bestI = i - k + 1;
                        bestJ = j - k + 1;
                        bestSize = k;
                    }
                }
            }
            prev = cur;
        }
        if (bestSize == 0) return;
        blocks(a, b, alo, bestI, blo, bestJ, out);
        out.add(new int[]{bestI, bestJ, bestSize});
        blocks(a, b, bestI + bestSize, ahi, bestJ + bestSize, bhi, out);
    }

    private static boolean[] coverByHanja(String token, String placeName) {
        boolean[] cov = new boolean[token.length()];
        int from = 0;
        int hits = 0;
        for (int i = 0; i < token.length(); i++) {
            String s = String.valueOf(token.charAt(i));
            for (int j = from; j < placeName.length(); j++) {
                List<String> ko = T.hanjaKo().get(placeName.charAt(j));
                if (ko != null && ko.contains(s)) {
                    cov[i] = true;
                    hits++;
                    from = j + 1;
                    break;
                }
            }
        }
        // 한자음 한 글자만 맞은 것은 우연이 흔하다(정교회의 정 = 元町의 町).
        if (hits < 2 && token.length() > 1) cov = new boolean[token.length()];
        for (Map.Entry<String, List<String>> w : T.words().entrySet()) {
            int at = token.indexOf(w.getKey());
            if (at >= 0 && w.getValue().stream().anyMatch(placeName::contains)) {
                for (int q = at; q < at + w.getKey().length(); q++) cov[q] = true;
            }
        }
        List<Character> chars = new ArrayList<>();
        for (char ch : placeName.toCharArray()) if (!NAME_PUNCT.matcher(String.valueOf(ch)).matches()) chars.add(ch);
        if (align(token, chars, 2) != null) java.util.Arrays.fill(cov, true);
        return cov;
    }

    /**
     * 한국어 표기가 장소 이름의 이어진 글자들의 일본어 읽기(음독·훈독·가나)를 차례로 이어 붙인 것과 같은지. 같으면 그 글자 범위다.
     * 연탁(かわ/がわ)·촉음(にち/닛)·장음은 같은 소리로 본다.
     */
    static int[] align(String korean, List<Character> chars, int minChars) {
        String k = romanize(korean);
        if (k.length() < 3) return null;
        List<Set<String>> opts = new ArrayList<>();
        for (int j = 0; j < chars.size(); j++) {
            char c = chars.get(j);
            String glide = SMALL_GLIDE.get(toHiragana(c));
            if (glide != null && j > 0) {
                // 요음(しょ·じゃ)은 앞 글자 읽기의 끝 i가 반모음이 된다. じゃ·ちゃ는 자·차로 적어 반모음 없이도 본다.
                Set<String> merged = new LinkedHashSet<>();
                for (String r : opts.get(j - 1)) {
                    if (r.endsWith("i")) {
                        merged.add(r.substring(0, r.length() - 1) + "y" + glide);
                        merged.add(r.substring(0, r.length() - 1) + glide);
                    }
                }
                if (!merged.isEmpty()) {
                    opts.set(j - 1, merged);
                    opts.add(Set.of(""));
                    continue;
                }
            }
            Set<String> own = c == '々' && j > 0 ? opts.get(j - 1) : readingsOf(c);
            char h = toHiragana(c);
            Set<String> before = j > 0 ? opts.get(j - 1) : Set.of();
            if (before.equals(Set.of("")) && j > 1) before = opts.get(j - 2);
            if (h == 'う' && endsWithAny(before, "o", "u") || h == 'い' && endsWithAny(before, "e")) {
                // 장음(しょう·せい)은 한국어 표기에 적지 않는다.
                own = new LinkedHashSet<>(own);
                own.add("");
            }
            opts.add(own);
        }
        for (int j0 = 0; j0 < chars.size(); j0++) {
            Set<Integer> reach = Set.of(0);
            for (int j = j0; j < chars.size(); j++) {
                Set<Integer> next = new HashSet<>();
                for (int p : reach) for (String r : opts.get(j)) if (k.startsWith(r, p)) next.add(p + r.length());
                if (next.isEmpty()) break;
                if (next.contains(k.length()) && j - j0 + 1 >= minChars) return new int[]{j0, j + 1};
                reach = next;
            }
        }
        return null;
    }

    private static boolean endsWithAny(Set<String> readings, String... tails) {
        for (String r : readings) for (String t : tails) if (r.endsWith(t)) return true;
        return false;
    }

    private static final Map<Character, String> SMALL_GLIDE = Map.of('ゃ', "a", 'ゅ', "u", 'ょ', "o");

    private static final Map<Character, Set<String>> READINGS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Set<String> readingsOf(char c) {
        return READINGS.computeIfAbsent(c, ch -> {
            Set<String> out = new LinkedHashSet<>();
            List<String> ja = T.hanjaJa().get(ch);
            if (ja != null) {
                for (String r : ja) {
                    String base = rom(r);
                    out.add(base);
                    // 훈독은 어미(오쿠리가나)까지 적혀 있어(asai) 이름에 쓰인 어간(asa)도 읽기로 둔다.
                    if (r.length() >= 4) for (int n = 2; n < r.length(); n++) out.add(rom(r.substring(0, n)));
                    for (String tail : List.of("chi", "tsu", "ku", "ki")) {
                        if (r.endsWith(tail) && r.length() > tail.length()) out.add(rom(r.substring(0, r.length() - tail.length())));
                    }
                    if (base.startsWith("h")) out.add("p" + base.substring(1));
                }
            } else if (T.kana().containsKey(toHiragana(ch))) {
                out.add(rom(T.kana().get(toHiragana(ch))));
            }
            out.remove("");
            if (ch == 'ー' || ch == 'っ' || ch == 'ッ') out.add("");
            return out;
        });
    }

    private static String rom(String r) {
        String s = LONG_VOWEL.matcher(r.toLowerCase(Locale.ROOT)).replaceAll(m -> m.group().substring(0, 1));
        s = SH_GLIDE.matcher(s).replaceAll("sy$1");
        s = SIBILANT.matcher(s).replaceAll("s");
        return s.replace('f', 'h').replace('g', 'k').replace('d', 't').replace('b', 'p').replace('v', 'p').replace('l', 'r');
    }

    private static final String[] ROM_INITIAL = {"k", "k", "n", "t", "t", "r", "m", "p", "p", "s", "s", "", "s", "s", "s",
            "k", "t", "p", "h"};

    static String romanize(String korean) {
        StringBuilder out = new StringBuilder();
        for (char c : korean.toCharArray()) {
            if (!isHangul(c)) continue;
            int o = c - 0xAC00;
            int t = o % 28;
            out.append(ROM_INITIAL[o / 588]).append(VOWEL[(o % 588) / 28]).append(t == 4 || t == 21 || t == 16 ? "n" : "");
        }
        return out.toString();
    }

    private static char toHiragana(char c) {
        return c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c;
    }

    private static final String[] CONSONANT_INITIAL = {"k", "k", "n", "t", "t", "r", "m", "p", "p", "s", "s", "", "s", "s",
            "s", "k", "t", "p", ""};

    private static String hangulConsonants(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (!isHangul(c)) continue;
            int o = c - 0xAC00;
            out.append(CONSONANT_INITIAL[o / 588]);
            out.append(switch (o % 28) {
                case 4, 21 -> "n";
                case 16 -> "m";
                case 8 -> "r";
                case 1 -> "k";
                case 17 -> "p";
                case 19 -> "s";
                case 7 -> "t";
                default -> "";
            });
        }
        return out.toString();
    }

    private static String latinConsonants(String s) {
        String x = s.toLowerCase(Locale.ROOT).replace("ph", "p").replace('f', 'p').replace('v', 'b').replace('l', 'r');
        x = x.replaceAll("sh|ch|ts|j|z|c", "s").replace('g', 'k').replace('d', 't').replace('b', 'p');
        return x.replaceAll("[^a-z]|[aeiouy]|h|w", "");
    }

    private static Set<Integer> englishHits(List<Token> tokens, List<String> english) {
        Set<Integer> hit = new HashSet<>();
        if (english.isEmpty()) return hit;
        for (int i = 0; i < tokens.size(); i++) {
            StringBuilder run = new StringBuilder();
            for (int j = i; j < tokens.size(); j++) {
                run.append(tokens.get(j).text());
                String sk = hangulConsonants(run.toString());
                if (sk.length() < 3) continue;
                for (int a = 0; a < english.size(); a++) {
                    StringBuilder e = new StringBuilder();
                    for (int b = a; b < Math.min(english.size(), a + 3); b++) {
                        e.append(latinConsonants(english.get(b)));
                        if (e.length() >= 3 && ratio(sk, e.toString()) >= ENGLISH_CONSONANT_RATIO) {
                            for (int q = i; q <= j; q++) hit.add(q);
                        }
                    }
                }
            }
        }
        return hit;
    }

    /** difflib의 ratio(2·일치 글자 수 / 두 길이 합). */
    static double ratio(String a, String b) {
        List<String> x = new ArrayList<>();
        List<String> y = new ArrayList<>();
        for (char c : a.toCharArray()) x.add(String.valueOf(c));
        for (char c : b.toCharArray()) y.add(String.valueOf(c));
        int m = matchingBlocks(x, y).stream().mapToInt(blk -> blk[2]).sum();
        return x.isEmpty() && y.isEmpty() ? 1 : 2.0 * m / (x.size() + y.size());
    }

    private static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }

    private static boolean hasHangul(String s) {
        for (char c : s.toCharArray()) if (isHangul(c)) return true;
        return false;
    }

    private static Tables load() {
        Map<String, Generic> korean = new HashMap<>();
        Map<String, Generic> translit = new HashMap<>();
        Map<String, List<String>> words = new HashMap<>();
        Set<String> localChars = new HashSet<>();
        for (String[] f : rows("names/local-name-words.tsv")) {
            if ("generic".equals(f[0])) {
                Generic g = new Generic(f[1], true, list(f, 4), list(f, 5), list(f, 6));
                Generic t = new Generic(f[1], false, g.localChars(), g.categoryKeywords(), g.englishWords());
                list(f, 2).forEach(w -> korean.put(w, g));
                list(f, 3).forEach(w -> translit.put(w, t));
                localChars.addAll(g.localChars());
            } else if ("word".equals(f[0])) {
                words.put(f[1], list(f, 2));
            }
        }
        List<String> generic = localChars.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        return new Tables(Map.copyOf(korean), Map.copyOf(translit), Map.copyOf(words), chars("names/hanja-ko.tsv"),
                chars("names/hanja-ja.tsv"), kana(), generic);
    }

    private static Map<Character, List<String>> chars(String resource) {
        Map<Character, List<String>> out = new HashMap<>();
        for (String[] f : rows(resource)) if (f[0].length() == 1) out.put(f[0].charAt(0), list(f, 1));
        return out;
    }

    private static Map<Character, String> kana() {
        Map<Character, String> out = new HashMap<>();
        for (String[] f : rows("names/kana-romaji.tsv")) out.put(f[0].charAt(0), f.length > 1 ? f[1] : "");
        return out;
    }

    private static List<String> list(String[] f, int i) {
        if (f.length <= i || f[i].isBlank()) return List.of();
        return List.of(f[i].split(","));
    }

    private static List<String[]> rows(String resource) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = LocalNameCheck.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("리소스가 없다: " + resource);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line.split("\t", -1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
