package com.shg.trip.shgtrip.domain.user;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** 프로필 수정과 소셜 가입이 같은 기준으로 닉네임을 저장·중복 검사하도록 한 곳에 둔다. */
public final class NicknamePolicy {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 20;

    /**
     * 글자·숫자가 하나 이상 있고, 화면에 안 보이는 문자가 없어야 한다.
     * 한글 채움 문자(U+115F·U+1160·U+3164·U+FFA0)와 점자 공백은 글자 분류라 \p{Cf}로 걸러지지 않아 따로 막는다.
     */
    public static final String ALLOWED_REGEX =
            "^(?=.*[\\p{L}\\p{N}])[^\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}\\p{Cs}\\u115F\\u1160\\u3164\\uFFA0\\u2800\\u034F\\uFE00-\\uFE0F]*$";

    private static final Pattern ALLOWED = Pattern.compile(ALLOWED_REGEX);
    private static final Pattern SPACES = Pattern.compile("\\p{Z}+");

    private NicknamePolicy() {
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        return SPACES.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }

    /** {@link #normalize}를 거친 값을 받는다. */
    public static boolean isAcceptable(String normalized) {
        return normalized != null
                && normalized.length() >= MIN_LENGTH && normalized.length() <= MAX_LENGTH
                && ALLOWED.matcher(normalized).matches();
    }
}
