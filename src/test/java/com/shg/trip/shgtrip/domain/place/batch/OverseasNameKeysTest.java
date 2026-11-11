package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.batch.OverseasNameKeys.PlaceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OverseasNameKeysTest {

    private static List<String> keys(String name) {
        return OverseasNameKeys.placeKeys(name).stream().map(PlaceKey::key).toList();
    }

    @Test
    @DisplayName("괄호 속 병기는 겹친 괄호까지 안쪽부터 버린다")
    void dropsBracketedTranslation() {
        assertThat(keys("浅草寺 (Senso-ji Temple)")).containsExactly("浅草寺");
        assertThat(keys("星巴克(南京东路店)")).containsExactly("星巴克");
        assertThat(keys("台北101（Taipei 101）")).containsExactly("台北101");
        assertThat(keys("関西国際空港 (Kansai International Airport (KIX))")).containsExactly("関西国際空港");
    }

    @Test
    @DisplayName("낫표·꺾쇠는 이름을 감싸는 기호라 안의 이름을 남긴다")
    void keepsQuotedNames() {
        assertThat(keys("道の駅「たのうらら」")).containsExactly("道の駅たのうらら");
        assertThat(keys("《印象·刘三姐》")).containsExactly("印象刘三姐");
    }

    @Test
    @DisplayName("문자 종류가 바뀌는 이름은 첫 묶음을 뒤 묶음 조건과 함께 키로 쓴다")
    void firstSegmentKeyCarriesRest() {
        assertThat(OverseasNameKeys.placeKeys("外滩 The Bund")).containsExactly(
                PlaceKey.whole("外滩thebund"), new PlaceKey("外滩", "thebund", false));
        // 숫자만 있는 단어는 앞 묶음에 붙는다
        assertThat(keys("Taipei 101")).containsExactly("taipei101");
    }

    @Test
    @DisplayName("첫 묶음 키는 뒤 묶음이 항목 이름 그대로이거나 항목 이름 + 분류어일 때만 붙는다")
    void firstSegmentNeedsTranslation() {
        assertThat(firstKey("龍安寺 Ryoan-ji Temple").accepts(Set.of("龍安寺", "ryoanji"), false)).isTrue();
        assertThat(firstKey("外滩 The Bund").accepts(Set.of("外滩", "thebund"), false)).isTrue();

        // 딸린 시설: 항목 이름 뒤에 분류어가 아닌 말이 붙는다
        assertThat(firstKey("東大寺 Todaiji Nandaimon").accepts(Set.of("東大寺", "todaiji"), false)).isFalse();
        assertThat(firstKey("東京タワー Tokyo Tower Main Deck").accepts(Set.of("東京タワー", "tokyotower"), false)).isFalse();
        assertThat(firstKey("黒門市場 Kuromon Information Center").accepts(Set.of("黒門市場", "kuromonmarket", "kuromon"), false))
                .isFalse();
        assertThat(firstKey("大阪城 Osaka-jo Hall").accepts(Set.of("大阪城", "osakacastle", "osakajo"), false)).isFalse();
    }

    private static PlaceKey firstKey(String name) {
        return OverseasNameKeys.placeKeys(name).stream().filter(k -> k.rest() != null).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("끝 단어의 店을 뗀 키는 식음료 항목에만 붙는다")
    void branchKeyOnlyForFood() {
        assertThat(OverseasNameKeys.placeKeys("一蘭 天神西通り店"))
                .contains(new PlaceKey("一蘭", null, true));
        PlaceKey fishShop = OverseasNameKeys.placeKeys("黒門市場 鮮魚店").stream()
                .filter(PlaceKey::branch).findFirst().orElseThrow();
        assertThat(fishShop.accepts(Set.of("黒門市場"), false)).isFalse();
        assertThat(fishShop.accepts(Set.of("黒門市場"), true)).isTrue();
        assertThat(keys("誠品書店")).containsExactly("誠品書店");
        // 대만 체인의 "…門市"도 지점명이다
        assertThat(OverseasNameKeys.placeKeys("可不可熟成紅茶 台北信義門市")).contains(new PlaceKey("可不可熟成紅茶", null, true));
    }

    @Test
    @DisplayName("라틴 문자의 발음 구별 기호는 떼고, 가나의 탁점은 남긴다")
    void foldsLatinOnly() {
        assertThat(keys("Chợ Bến Thành")).containsExactly("chobenthanh");
        assertThat(keys("Đà Nẵng Cathedral")).containsExactly("danangcathedral");
        assertThat(keys("がんこ寿司")).containsExactly("がんこ寿司");
        // 전각 영숫자·반각 가나는 NFKC로 맞춘다
        assertThat(keys("ＴＯＫＹＯ　ﾀﾜｰ")).containsExactly("tokyoタワー", "tokyo");
    }

    @Test
    @DisplayName("한자·가나는 두 글자, 라틴 문자는 세 글자부터 키로 쓴다")
    void minimumKeyLength() {
        assertThat(OverseasNameKeys.itemKeys(List.of("豫园", "Yu", "故"))).containsExactly("豫园");
    }

    @Test
    @DisplayName("matches는 인기도 매칭과 같은 조건이다: 포함 관계는 보지 않고, 병기·지점명 조건을 지킨다")
    void matchesSameAsAssignment() {
        Set<String> inari = OverseasNameKeys.itemKeys(List.of("伏見稲荷大社", "Fushimi Inari-taisha"));
        assertThat(OverseasNameKeys.matches(OverseasNameKeys.placeKeys("伏見稲荷大社"), inari, false)).isTrue();
        assertThat(OverseasNameKeys.matches(OverseasNameKeys.placeKeys("伏見稲荷 Souvenir Shop"), inari, false)).isFalse();
        assertThat(OverseasNameKeys.matches(OverseasNameKeys.placeKeys("伏見稲荷"), inari, false)).isFalse();
        assertThat(OverseasNameKeys.matches(OverseasNameKeys.placeKeys("大阪城ホール"),
                OverseasNameKeys.itemKeys(List.of("大阪城")), false)).isFalse();
    }

    @Test
    @DisplayName("현지 이름은 나라 언어 라벨, 없으면 영어, 그것도 없으면 언어 코드 순 첫 라벨")
    void localLabel() {
        Map<String, String> labels = Map.of("zh-hans", "外滩", "zh-hant", "外灘", "en", "The Bund", "ko", "와이탄");
        assertThat(OverseasNameKeys.localLabel(labels, "CN")).isEqualTo("外滩");
        assertThat(OverseasNameKeys.localLabel(labels, "TW")).isEqualTo("外灘");
        assertThat(OverseasNameKeys.localLabel(labels, "VN")).isEqualTo("The Bund");
        assertThat(OverseasNameKeys.localLabel(Map.of("zh-hk", "甲", "ko", "을"), "JP")).isEqualTo("을");
        assertThat(OverseasNameKeys.localLabel(Map.of(), "JP")).isNull();
    }
}
