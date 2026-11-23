package com.shg.trip.shgtrip.domain.place.name;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 일본 지명 한글 표기의 두 갈래. 외래어 표기법은 낱말 첫소리를 예사소리로, ㅈ·ㅊ 뒤 이중모음을 단모음으로 적고
 * (다누키코지·고묘젠지·조조지), 여행자와 여행 정보는 거센소리·이중모음으로 적는다(타누키코지·코묘젠지·조죠지).
 */
final class JapaneseHangul {

    private JapaneseHangul() {}

    private static final int SYLLABLE_BASE = 0xAC00;
    private static final int SYLLABLE_LAST = 0xD7A3;
    private static final int MEDIALS = 21;
    private static final int FINALS = 28;

    // 첫소리 번호: ㄱ0 ㄷ3 ㅈ12 ㅊ14 ㅋ15 ㅌ16
    private static final int[][] INITIAL_PAIRS = {{15, 0}, {16, 3}, {14, 12}};
    // 가운데소리 번호: ㅑ2→ㅏ0, ㅛ12→ㅗ8, ㅠ17→ㅜ13
    private static final int[][] GLIDE_PAIRS = {{2, 0}, {12, 8}, {17, 13}};
    private static final int INITIAL_J = 12;
    private static final int INITIAL_CH = 14;

    static List<String> variants(String name, int max) {
        Set<String> out = new LinkedHashSet<>();
        String[] words = name.split(" ", -1);
        List<Integer> toggleable = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            if (!words[i].isEmpty() && toggledInitial(words[i].charAt(0)) != 0) toggleable.add(i);
        }
        if (!toggleable.isEmpty()) {
            String[] all = words.clone();
            for (int i : toggleable) all[i] = toggleFirst(words[i]);
            out.add(String.join(" ", all));
            for (int i : toggleable) {
                String[] one = words.clone();
                one[i] = toggleFirst(words[i]);
                out.add(String.join(" ", one));
            }
        }
        String plain = withoutGlides(name);
        if (!plain.equals(name)) out.add(plain);
        for (int i = 0; i < name.length(); i++) {
            char glided = withGlide(name.charAt(i));
            if (glided != 0) out.add(name.substring(0, i) + glided + name.substring(i + 1));
        }
        out.remove(name);
        return out.stream().limit(max).toList();
    }

    private static String toggleFirst(String word) {
        return toggledInitial(word.charAt(0)) + word.substring(1);
    }

    /** 첫소리를 짝으로 바꾼 글자, 바꿀 수 없으면 0. */
    private static char toggledInitial(char c) {
        if (c < SYLLABLE_BASE || c > SYLLABLE_LAST) return 0;
        int offset = c - SYLLABLE_BASE;
        int initial = offset / (MEDIALS * FINALS);
        int rest = offset % (MEDIALS * FINALS);
        for (int[] pair : INITIAL_PAIRS) {
            if (initial == pair[0]) return (char) (SYLLABLE_BASE + pair[1] * MEDIALS * FINALS + rest);
            if (initial == pair[1]) return (char) (SYLLABLE_BASE + pair[0] * MEDIALS * FINALS + rest);
        }
        return 0;
    }

    private static String withoutGlides(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            int[] parts = decompose(c);
            if (parts != null && (parts[0] == INITIAL_J || parts[0] == INITIAL_CH)) {
                for (int[] pair : GLIDE_PAIRS) {
                    if (parts[1] == pair[0]) {
                        c = compose(parts[0], pair[1], parts[2]);
                        break;
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 받침 없는 ㅈ·ㅊ 단모음 글자를 이중모음으로 바꾼 글자, 해당 없으면 0. */
    private static char withGlide(char c) {
        int[] parts = decompose(c);
        if (parts == null || parts[2] != 0 || (parts[0] != INITIAL_J && parts[0] != INITIAL_CH)) return 0;
        for (int[] pair : GLIDE_PAIRS) {
            if (parts[1] == pair[1]) return compose(parts[0], pair[0], 0);
        }
        return 0;
    }

    private static int[] decompose(char c) {
        if (c < SYLLABLE_BASE || c > SYLLABLE_LAST) return null;
        int offset = c - SYLLABLE_BASE;
        return new int[]{offset / (MEDIALS * FINALS), offset % (MEDIALS * FINALS) / FINALS, offset % FINALS};
    }

    private static char compose(int initial, int medial, int fin) {
        return (char) (SYLLABLE_BASE + (initial * MEDIALS + medial) * FINALS + fin);
    }
}
