package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;

/** OfficialHoursParser 출력 형식을 일정 쪽 판독기가 그대로 읽는지. */
class OfficialHoursFormatTest {

    @Test
    @DisplayName("휴무·시각 구간·자정 넘김·하루 종일·영업(판독 불가)을 읽는다")
    void readsParserOutput() {
        String hours = "월요일: 휴무, 화요일: 10:00 ~ 20:00, 수요일: 17:00 ~ 02:00, 목요일: 00:00 ~ 24:00, "
                + "금요일: 영업, 토요일: 10:00 ~ 20:00, 일요일: 10:00 ~ 20:00";

        assertThat(OpeningHoursText.isClosedOnDay(hours, DayOfWeek.MONDAY)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.TUESDAY, 9 * 60, 10 * 60)).isFalse();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.TUESDAY, 12 * 60, 13 * 60)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.WEDNESDAY, 23 * 60, 25 * 60)).isTrue();
        assertThat(OpeningHoursText.isOpenDuring(hours, DayOfWeek.THURSDAY, 6 * 60, 7 * 60)).isTrue();
        assertThat(OpeningHoursText.rangesFor(hours, DayOfWeek.FRIDAY)).isNull();
        assertThat(OpeningHoursText.isClosedOnDay(hours, DayOfWeek.FRIDAY)).isFalse();
    }
}
