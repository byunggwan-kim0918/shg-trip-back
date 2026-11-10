package com.shg.trip.shgtrip.domain.tripchat.service;

import java.text.Normalizer;
import java.util.Locale;

final class TextNormalizer {

    private TextNormalizer() {}

    /** 공백·문장부호를 빼고 비교한다. "블루라인 파크"와 "블루라인파크"가 같은 말이다. */
    static String key(String text) {
        if (text == null) return "";
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return nfc.replaceAll("[\\s\\p{Punct}·~…“”‘’「」『』()（）]", "");
    }

    static boolean containsKey(String haystack, String needle) {
        String n = key(needle);
        return !n.isEmpty() && key(haystack).contains(n);
    }

    static String cap(String text, int max) {
        if (text == null) return null;
        String t = text.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
