package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업시간 판독 테스트. 케이스 문자열은 모두 <b>DB에 실제로 들어있는 형식</b>에서 가져왔다
 * (places.opening_hours 표본 조사). 형식이 하나 빠지면 그 형식의 장소가 통째로 "열림 가정"으로
 * 새어나가므로, 새 형식을 발견하면 여기에 먼저 케이스를 추가한다.
 */
class OpeningHoursTextTest {

    private static final int LUNCH = 11 * 60 + 30;   // 11:30
    private static final int LUNCH_END = 12 * 60 + 40;
    private static final int DINNER = 17 * 60 + 30;  // 17:30
    private static final int DINNER_END = 18 * 60 + 40;

    @Test
    @DisplayName("표준 표기: 오전/오후 두 쪽 모두 명시")
    void standardKoreanRange() {
        String hours = "월요일: 오전 10:00 ~ 오후 10:00";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, DINNER, DINNER_END)).isTrue();
        // 22:00 마감이라 23:00 시작은 불가
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, 23 * 60, 23 * 60 + 30)).isFalse();
    }

    @Test
    @DisplayName("실측 위반 1: 오후 4시 오픈 식당은 11:30 점심에 들어갈 수 없다 (itinerary 78 부산가든)")
    void afternoonOpeningRejectsLunch() {
        String hours = "월요일: 오후 4:00~10:00, 화요일: 오후 4:00~10:00, 금요일: 오전 11:30 ~ 오후 10:00";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isFalse();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, DINNER, DINNER_END)).isTrue();
        // 같은 가게라도 금요일은 11:30부터 열어 점심이 가능하다 — 요일별로 달라야 한다
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.FRIDAY, LUNCH, LUNCH_END)).isTrue();
    }

    @Test
    @DisplayName("실측 위반 2: 오후 3시 오픈 식당도 11:30 점심 불가 (itinerary 78 아웃닭 서면점)")
    void threePmOpeningRejectsLunch() {
        String hours = "월요일: 오후 3:00 ~ 오전 2:00, 화요일: 오후 3:00 ~ 오전 2:00";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isFalse();
        // 자정을 넘기는 영업이라 저녁은 물론 가능
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, DINNER, DINNER_END)).isTrue();
    }

    @Test
    @DisplayName("실측 위반 3: 정오 마감 등산로는 13시 시작 불가 (itinerary 77 한라산 영실)")
    void noonClosingRejectsAfternoon() {
        String hours = "월요일: 오전 6:00 ~ 오후 12:00, 화요일: 오전 6:00 ~ 오후 12:00";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, 13 * 60, 16 * 60)).isFalse();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, 8 * 60, 11 * 60)).isTrue();
    }

    @Test
    @DisplayName("실측 위반 4: 19시 마감 카페는 20:31 시작 불가 (itinerary 78 더반 베를린)")
    void eveningClosingRejectsNightStep() {
        String hours = "월요일: 오전 11:00 ~ 오후 7:00, 토요일: 오전 9:00 ~ 오후 7:00";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, 20 * 60 + 31, 22 * 60 + 1)).isFalse();
    }

    @Test
    @DisplayName("뒤쪽 오전/오후 생략 표기를 앞쪽에서 물려받는다")
    void inheritsMarkerFromStart() {
        // 오후 12:00~10:00 = 정오~22:00
        String hours = "월요일: 오후 12:00~10:00";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, DINNER, DINNER_END)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isFalse();
        assertThat(OpeningHoursText.rangesFor(hours, DayOfWeek.MONDAY).get(0))
                .containsExactly(12 * 60, 22 * 60);
    }

    @Test
    @DisplayName("브레이크 타임: 한 요일에 두 구간이면 사이 시간은 닫혀 있다")
    void breakTimeBetweenTwoRanges() {
        String hours = "월요일: 오전 11:00 ~ 오후 3:00, 오후 4:30~10:00, 화요일: 휴무일";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, DINNER, DINNER_END)).isTrue();
        // 15:00~16:30은 브레이크
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, 15 * 60 + 30, 16 * 60)).isFalse();
        // 쉼표로 자르면 "오후 4:30~10:00"이 화요일에 붙어 화요일이 열린 것으로 오판한다
        assertThat(OpeningHoursText.isClosedOnDay(hours, DayOfWeek.TUESDAY)).isTrue();
    }

    @Test
    @DisplayName("오전 12:00은 자정, 오후 12:00은 정오로 읽는다")
    void twelveOclockBoundaries() {
        assertThat(OpeningHoursText.rangesFor("월요일: 오전 11:00 ~ 오전 12:00", DayOfWeek.MONDAY).get(0))
                .containsExactly(11 * 60, 24 * 60);
        assertThat(OpeningHoursText.rangesFor("월요일: 오후 12:00 ~ 오전 1:00", DayOfWeek.MONDAY).get(0))
                .containsExactly(12 * 60, 25 * 60);
    }

    @Test
    @DisplayName("24시간 영업과 정기휴무")
    void allDayAndClosed() {
        assertThat(OpeningHoursText.isOpenDuring("월요일: 24시간 영업", DayOfWeek.MONDAY, 0, 1439)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring("Monday: Open 24 hours", DayOfWeek.MONDAY, DINNER, DINNER_END)).isTrue();
        assertThat(OpeningHoursText.isClosedOnDay("월요일: 휴무일", DayOfWeek.MONDAY)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring("월요일: 휴무일", DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isFalse();
    }

    @Test
    @DisplayName("영어 표기: AM/PM 접미, 앞쪽 생략 포함")
    void englishFormat() {
        String hours = "Monday: 11:00 AM ~ 3:00 PM, 4:00 ~ 7:30 PM, Tuesday: 9:00 AM ~ 5:00 PM";
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, 16 * 60 + 30, 17 * 60 + 30)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.MONDAY, 15 * 60 + 30, 16 * 60)).isFalse();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.TUESDAY, DINNER, DINNER_END)).isFalse();
    }

    @Test
    @DisplayName("'오전 9시~오후 6시' 처럼 분이 없는 표기도 읽는다")
    void hourOnlyFormat() {
        String hours = "월요일: 오전 9시~오후 6시";
        assertThat(OpeningHoursText.rangesFor(hours, DayOfWeek.MONDAY).get(0))
                .containsExactly(9 * 60, 18 * 60);
    }

    @Test
    @DisplayName("안전 규약: 판독 불가·요일 미매칭·데이터 없음은 모두 열림으로 간주한다")
    void unreadableMeansOpen() {
        assertThat(OpeningHoursText.isOpenDuring(null, DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring("", DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring("영업시간 문의", DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isTrue();
        // 월요일 정보가 없는 문자열로 월요일을 물으면 판단을 보류한다
        assertThat(OpeningHoursText.isOpenDuring("화요일: 휴무", DayOfWeek.MONDAY, LUNCH, LUNCH_END)).isTrue();
        assertThat(OpeningHoursText.isClosedOnDay("화요일: 휴무", DayOfWeek.MONDAY)).isFalse();
        // 구간을 하나도 못 읽으면 휴무가 아니라 판독 불가다
        assertThat(OpeningHoursText.rangesFor("월요일: 상시 영업", DayOfWeek.MONDAY)).isNull();
    }

    @Test
    @DisplayName("기존 isClosedOnDay 계약 유지: 고신뢰 휴무 신호에만 true")
    void keepsLegacyClosedContract() {
        assertThat(OpeningHoursText.isClosedOnDay("월요일: 휴무, 화요일: 오전 9시~오후 6시",
                DayOfWeek.MONDAY)).isTrue();
        assertThat(OpeningHoursText.isClosedOnDay("Monday: Closed, Tuesday: 9 AM ~ 6 PM",
                DayOfWeek.MONDAY)).isTrue();
        assertThat(OpeningHoursText.isClosedOnDay("월요일: 오전 9시~오후 6시", DayOfWeek.MONDAY)).isFalse();
        assertThat(OpeningHoursText.isClosedOnDay(null, DayOfWeek.MONDAY)).isFalse();
        assertThat(OpeningHoursText.isClosedOnDay("", DayOfWeek.MONDAY)).isFalse();
    }

    @Test
    @DisplayName("earliestStart: 개장 전이면 개장 시각을, 그날 불가능하면 -1, 판독 불가면 null")
    void earliestStartSemantics() {
        String nineToSix = "월요일: 오전 9:00 ~ 오후 6:00";
        // 08:36 도착 + 90분 체류 → 09:00으로 늦추면 들어간다
        assertThat(OpeningHoursText.earliestStart(nineToSix, DayOfWeek.MONDAY, 8 * 60 + 36, 90))
                .isEqualTo(9 * 60);
        // 이미 영업 중이면 그대로
        assertThat(OpeningHoursText.earliestStart(nineToSix, DayOfWeek.MONDAY, 10 * 60, 90))
                .isEqualTo(10 * 60);
        // 마감까지 90분이 안 남으면 그날은 불가능
        assertThat(OpeningHoursText.earliestStart(nineToSix, DayOfWeek.MONDAY, 17 * 60 + 30, 90))
                .isEqualTo(-1);
        // 정기휴무도 불가능
        assertThat(OpeningHoursText.earliestStart("월요일: 휴무일", DayOfWeek.MONDAY, 10 * 60, 90))
                .isEqualTo(-1);
        // 판독 불가 → 제약 없음
        assertThat(OpeningHoursText.earliestStart(null, DayOfWeek.MONDAY, 10 * 60, 90)).isNull();
        assertThat(OpeningHoursText.earliestStart("상시 영업", DayOfWeek.MONDAY, 10 * 60, 90)).isNull();
    }

    @Test
    @DisplayName("earliestStart: 브레이크 타임이 있으면 다음 구간의 시작을 돌려준다")
    void earliestStartSkipsBreak() {
        String withBreak = "월요일: 오전 11:00 ~ 오후 3:00, 오후 4:30~10:00";
        // 15:30 도착 + 70분 → 브레이크라 16:30부터
        assertThat(OpeningHoursText.earliestStart(withBreak, DayOfWeek.MONDAY, 15 * 60 + 30, 70))
                .isEqualTo(16 * 60 + 30);
    }
}
