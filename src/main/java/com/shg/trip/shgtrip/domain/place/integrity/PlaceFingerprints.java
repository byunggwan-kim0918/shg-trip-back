package com.shg.trip.shgtrip.domain.place.integrity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 파생 데이터(보강·임베딩·장소 사실)를 만든 입력의 지문. 지문이 바뀐 작업만 다시 돌린다.
 * 버전 접두어를 바꾸면 모든 행이 다시 만들어지므로, 입력 필드가 바뀔 때만 올린다.
 */
public final class PlaceFingerprints {

    private PlaceFingerprints() {}

    private static final int HEX_CHARS = 16;
    /** 장소 사실 좌표 비교 자릿수. 소수 셋째 자리(약 100m)보다 작은 좌표 보정으로는 사실을 다시 만들지 않는다. */
    private static final int FACTS_COORD_SCALE = 3;

    /**
     * 보강 결과를 바꾸는 입력. 주소·인기도는 프롬프트에 들어가도 결과를 거의 바꾸지 않는데, 넣으면 형식 보정만으로
     * 유료 보강이 다시 돈다.
     */
    public static String enrichInput(String name, String category, String region, String country) {
        return hash("enrich-v1", name, category, region, country);
    }

    public static String embeddingText(String text) {
        return hash("text-v1", text);
    }

    /** 한국어 이름은 넣지 않는다. Wikidata 라벨·보정표로 채우기만 해도 같은 곳의 사실이 지워진다. */
    public static String factsInput(String name, String category, BigDecimal latitude, BigDecimal longitude) {
        return hash("facts-v1", name, category, rounded(latitude), rounded(longitude));
    }

    private static String rounded(BigDecimal value) {
        return value == null ? null : value.setScale(FACTS_COORD_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String hash(String version, String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(version.getBytes(StandardCharsets.UTF_8));
            for (String part : parts) {
                digest.update((byte) 0x1f);
                digest.update(Objects.toString(part, "\u0000").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
