package com.shg.trip.shgtrip.domain.place.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 입력은 2026-11 TourAPI detailIntro2 실제 응답에서 가져왔다. */
class OfficialHoursParserTest {

    private static final String ALL_9_18 = "월요일: 09:00 ~ 18:00, 화요일: 09:00 ~ 18:00, 수요일: 09:00 ~ 18:00, "
            + "목요일: 09:00 ~ 18:00, 금요일: 09:00 ~ 18:00, 토요일: 09:00 ~ 18:00, 일요일: 09:00 ~ 18:00";

    @Test
    @DisplayName("단일 구간 + 연중무휴(불국사) → 매일 같은 시간, 입장 마감은 무시")
    void singleRange() {
        assertThat(OfficialHoursParser.toOpeningHours("09:00~18:00 (입장 마감 17:00)", "연중무휴")).isEqualTo(ALL_9_18);
    }

    @Test
    @DisplayName("매주 휴무 요일을 휴무로 표시하고, 공휴일 단서는 무시한다(서울시립미술관)")
    void weeklyClosure() {
        String r = OfficialHoursParser.toOpeningHours(
                "[평일] - 10:00~20:00[주말·공휴일]- 하절기(3월~10월) 10:00~19:00- 동절기(11월~2월) 10:00~18:00",
                "매주 월요일 (단, 월요일이 공휴일인 경우 정상 개관) / 1월 1일");

        assertThat(r).startsWith("월요일: 휴무, 화요일: 10:00 ~ 20:00");
        assertThat(r).endsWith("일요일: 10:00 ~ 20:00");
    }

    @Test
    @DisplayName("매주 두 요일 휴무(순아커피)")
    void twoWeeklyClosures() {
        String r = OfficialHoursParser.toOpeningHours("- 평일 09:30~17:30<br>- 토요일 11:00~19:00", "매주 일요일, 월요일");

        assertThat(r).startsWith("월요일: 휴무, 화요일: 09:30 ~ 19:00");
        assertThat(r).endsWith("일요일: 휴무");
    }

    @Test
    @DisplayName("준비시간 구간은 영업 구간에 넣지 않는다(청송옥)")
    void breakTimeIgnored() {
        String r = OfficialHoursParser.toOpeningHours(
                "- 10:00~22:00<br>- 준비시간 14:30~16:30<br>- 마지막 주문 21:00", "매주 일요일 / 추석연휴");

        assertThat(r).contains("월요일: 10:00 ~ 22:00").endsWith("일요일: 휴무");
    }

    @Test
    @DisplayName("이용시간·쉬는날 필드가 뒤바뀐 행을 바로잡는다(감따남)")
    void swappedFields() {
        String r = OfficialHoursParser.toOpeningHours("매주 월요일", "11:00~18:00 (라스트오더 17:00)");

        assertThat(r).startsWith("월요일: 휴무, 화요일: 11:00 ~ 18:00");
    }

    @Test
    @DisplayName("월 단위 휴무(첫째 주·둘째주)는 매주 휴무로 보지 않는다")
    void monthlyClosureIgnored() {
        assertThat(OfficialHoursParser.closedDays("매월 첫째 주 월요일※ 공휴일인 경우 다음날 휴무")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("매월 둘째주, 넷째주 화요일 / 법정공휴일")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("매월 첫째, 셋째 월요일")).isEmpty();
    }

    @Test
    @DisplayName("계절별 시간은 가장 이른 시작 ~ 가장 늦은 종료로 넓게 잡는다(성산일출봉)")
    void seasonalEnvelope() {
        String r = OfficialHoursParser.toOpeningHours(
                "[11월~2월] - 06:00~18:00 - 매표 마감 17:00 [3월/4월/9월/10월] - 05:00~19:00 [5월~8월] - 04:30~20:00",
                "매월 첫째 주 월요일※ 공휴일인 경우 다음날 휴무");

        assertThat(r).startsWith("월요일: 04:30 ~ 20:00");
    }

    @Test
    @DisplayName("상시 개방은 하루 종일, 하이픈 표기도 읽는다")
    void alwaysOpenAndHyphen() {
        assertThat(OfficialHoursParser.toOpeningHours("상시 개방", "연중무휴")).startsWith("월요일: 00:00 ~ 24:00");
        assertThat(OfficialHoursParser.toOpeningHours("07:30-21:30", "")).startsWith("월요일: 07:30 ~ 21:30");
        assertThat(OfficialHoursParser.toOpeningHours("8:00~22:00", "연중무휴")).startsWith("월요일: 08:00 ~ 22:00");
    }

    @Test
    @DisplayName("시간도 매주 휴무도 없으면 null(한라산 백록담·전화 문의)")
    void nothingReadable() {
        assertThat(OfficialHoursParser.toOpeningHours(
                "성판악·관음사 탐방로 사전예약 필수계절별 입산·하산 통제시간 적용", "연중무휴 ※ 기상 여건에 따라 입산 금지")).isNull();
        assertThat(OfficialHoursParser.toOpeningHours("※ 자세한 사항은 전화문의 요망", "※ 자세한 사항은 전화문의 요망")).isNull();
        assertThat(OfficialHoursParser.toOpeningHours("", "")).isNull();
    }

    @Test
    @DisplayName("시간은 모르고 휴무만 알면 다른 날은 '영업'(판독 불가 = 열림)으로 둔다")
    void closureWithoutHours() {
        assertThat(OfficialHoursParser.toOpeningHours("※ 전화 문의", "매주 화요일 휴무"))
                .isEqualTo("월요일: 영업, 화요일: 휴무, 수요일: 영업, 목요일: 영업, 금요일: 영업, 토요일: 영업, 일요일: 영업");
    }

    @Test
    @DisplayName("자정을 넘기는 영업은 종료를 다음날 시각으로 쓴다")
    void overnight() {
        assertThat(OfficialHoursParser.toOpeningHours("17:00~02:00", "연중무휴")).startsWith("월요일: 17:00 ~ 02:00");
    }

    @Test
    @DisplayName("HTML 태그·탭을 걷어 낸다")
    void clean() {
        assertThat(OfficialHoursParser.clean("08:00~17:00 \n<br>※ 운항정보\t홈페이지")).isEqualTo("08:00~17:00\n\n※ 운항정보 홈페이지");
    }

    @Test
    @DisplayName("요금: 무료만 있으면 0, 성인 요금 우선, 금액이 여럿이면 null")
    void fee() {
        assertThat(OfficialHoursParser.admissionFee("무료※ 단, 유료 특별 전시 제외")).isZero();
        assertThat(OfficialHoursParser.admissionFee("[성인]- 1,000원[초·중·고등학생 및 군경]- 500원 ※ 무료 : 6세 이하")).isEqualTo(1000);
        assertThat(OfficialHoursParser.admissionFee("5,000원")).isEqualTo(5000);
        assertThat(OfficialHoursParser.admissionFee("대인 3,000원 / 소인 2,000원")).isNull();
        assertThat(OfficialHoursParser.admissionFee("")).isNull();
    }

    @Test
    @DisplayName("조건부·월 단위·계절·반일 휴무는 매주 휴무로 보지 않는다")
    void conditionalClosuresIgnored() {
        assertThat(OfficialHoursParser.closedDays("매주 월요일 (월요일이 공휴일인 경우 화요일 휴관)")).containsExactly(0);
        assertThat(OfficialHoursParser.closedDays("월요일이 공휴일이면 화요일 휴관")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("매월 마지막 주 월요일 휴무")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("매월 1,3주 일요일 휴무")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("2·4주 일요일 휴무")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("동절기(12~2월) 매주 월요일 휴무")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("매주 월요일 오전 휴무")).isEmpty();
    }

    @Test
    @DisplayName("흔한 표기의 매주 휴무는 그대로 읽는다")
    void commonWeeklyClosures() {
        assertThat(OfficialHoursParser.closedDays("매주 월요일, 1월 1일, 설·추석 당일")).containsExactly(0);
        assertThat(OfficialHoursParser.closedDays("매주 월요일 / 1월 1일※월요일이 휴일인 경우 정상개관")).containsExactly(0);
        assertThat(OfficialHoursParser.closedDays("월요일")).containsExactly(0);
        assertThat(OfficialHoursParser.closedDays("화요일, 수요일 휴무")).containsExactly(1, 2);
        assertThat(OfficialHoursParser.closedDays("연중무휴")).isEmpty();
        assertThat(OfficialHoursParser.closedDays("매주 화요일~목요일")).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    @DisplayName("익일·오전/오후 표기, 상시 개방과 시각이 섞인 표기는 시간을 비운다(휴무는 그대로)")
    void unreadableTimes() {
        assertThat(OfficialHoursParser.envelope("11:00~15:00, 17:00~익일 02:00")).isNull();
        assertThat(OfficialHoursParser.envelope("오전 9:00~11:30, 오후 1:00~5:00")).isNull();
        assertThat(OfficialHoursParser.envelope("체험 10:00~12:00 / 공원은 상시개방")).isNull();
        assertThat(OfficialHoursParser.toOpeningHours("11:00~15:00, 17:00~익일 02:00", "매주 월요일"))
                .startsWith("월요일: 휴무, 화요일: 영업");
    }

    @Test
    @DisplayName("입장료가 아닌 요금이 섞이면 비운다")
    void nonAdmissionFee() {
        assertThat(OfficialHoursParser.admissionFee("주차료 2,000원")).isNull();
        assertThat(OfficialHoursParser.admissionFee("체험료 성인 10,000원")).isNull();
    }
}
