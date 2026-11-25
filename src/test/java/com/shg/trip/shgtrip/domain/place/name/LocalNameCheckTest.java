package com.shg.trip.shgtrip.domain.place.name;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalNameCheckTest {

    private static final List<String> CITIES = List.of("오타루", "가나자와", "하코다테", "히로시마", "비에이", "나오시마",
            "하코네", "다카야마", "가와구치코", "벳푸", "이시가키", "교토");

    private static boolean same(String korean, String place, String category, List<String> readings, List<String> weak,
                                List<String> english) {
        return LocalNameCheck.confirms(korean, CITIES, place, category, readings, weak, english);
    }

    @Test
    @DisplayName("현지어 이름이 다른 곳을 가리키면 그 이름의 장소가 있어도 같은 곳으로 보지 않는다")
    void wrongLocalGuessIsRejected() {
        assertThat(same("센닌자카 전망대", "拓真館", "Arts and Entertainment > Art Gallery", List.of("다쿠신칸"),
                List.of(), List.of())).isFalse();
        assertThat(same("오타루 데누키코지", "小樽出世前広場", "Arts and Entertainment > Art Gallery", List.of(),
                List.of("오타루 출세전 광장"), List.of())).isFalse();
        assertThat(same("사이가와 강변", "浅野川", "Landmarks and Outdoors > River", List.of("아사노 강", "아사노"),
                List.of("아사노가와"), List.of())).isFalse();
        assertThat(same("시라시마 신사", "縮景園", "Arts and Entertainment", List.of("슛케이엔"), List.of(),
                List.of())).isFalse();
        assertThat(same("하코다테 시립 박물관", "函館市北方民族資料館", "Arts and Entertainment > Museum > History Museum",
                List.of(), List.of("하코다테시 북방민족자료관"), List.of("Northern", "Peoples"))).isFalse();
    }

    @Test
    @DisplayName("규칙 읽기·한국 한자음·일본어 읽기·영어 낱말·번역어로 설명되면 같은 곳이다")
    void evidenceConfirmsSamePlace() {
        assertThat(same("요네하라 해변", "米原ビーチ", "Landmarks and Outdoors > Beach", List.of("요네하라 해변"),
                List.of("미하라 해변"), List.of())).isTrue();
        assertThat(same("오우라 천주당", "大浦天主堂", "Community and Government > Spiritual Center > Church", List.of(),
                List.of(), List.of())).isTrue();
        assertThat(same("센고쿠하라 억새 평원", "仙石原すすき草原", "Landmarks and Outdoors > Field", List.of(),
                List.of(), List.of())).isTrue();
        assertThat(same("쓰즈미몬", "鼓門", "Landmarks and Outdoors > Monument", List.of(), List.of("가나자와 북문"),
                List.of())).isTrue();
        assertThat(same("햅 파이브", "HEP FIVE", "Retail > Shopping Mall", List.of(), List.of(), List.of("HEP", "FIVE")))
                .isTrue();
        assertThat(same("붉은 호박", "赤かぼちゃ", "Arts and Entertainment > Public Art", List.of(), List.of("아카 가보차"),
                List.of())).isTrue();
        assertThat(same("히다 다카야마 마을 박물관", "飛騨高山まちの博物館",
                "Arts and Entertainment > Museum > History Museum", List.of(), List.of(), List.of())).isTrue();
    }

    @Test
    @DisplayName("요음과 반복 부호가 든 현지 이름도 일본어 읽기로 맞춘다")
    void glidesAndRepeatMarkAlign() {
        assertThat(LocalNameCheck.align("요요기", List.of('代', '々', '木'), 2)).isNotNull();
        assertThat(LocalNameCheck.align("쇼와칸", List.of('し', 'ょ', 'う', 'わ', '館'), 2)).isNotNull();
        assertThat(LocalNameCheck.align("자쿠지", List.of('じ', 'ゃ', 'く', '寺'), 2)).isNotNull();
        assertThat(LocalNameCheck.align("샤토", List.of('シ', 'ャ', 'ト'), 2)).isNotNull();
    }

    @Test
    @DisplayName("공통된 신사 이름이나 한 음절만 다른 절 이름은 같은 곳의 근거가 아니다")
    void similarButDifferentPlacesAreRejected() {
        assertThat(same("가와구치 아사마 신사", "北口本宮 冨士浅間神社", "Community and Government > Spiritual Center",
                List.of("기타구치 혼구 후지 센겐 신사"), List.of("기타구치 혼구 후지 아사마 신사"), List.of())).isFalse();
        assertThat(same("소후쿠지", "聖福寺", "Community and Government > Spiritual Center > Buddhist Temple",
                List.of("쇼후쿠지"), List.of(), List.of())).isFalse();
        assertThat(same("히로시마 미술관", "広島県立美術館", "Arts and Entertainment > Museum",
                List.of("히로시마 현립 미술관"), List.of(), List.of())).isFalse();
    }
}
