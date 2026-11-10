package com.shg.trip.shgtrip.domain.tripchat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DateCandidatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10); // 화요일

    @Test
    @DisplayName("11월 중순은 11~20일 사이 금·토 출발 주말로 제안한다")
    void midNovember() {
        DateCandidates.Approx approx = DateCandidates.parseApprox("11|mid");
        List<String> phrases = DateCandidates.forApprox(approx, null, TODAY).stream()
                .map(r -> DateCandidates.phrase(r, TODAY)).toList();

        assertThat(phrases).containsExactly(
                "11월 13일(금)부터 15일(일)까지",
                "11월 14일(토)부터 15일(일)까지",
                "11월 20일(금)부터 22일(일)까지");
    }

    @Test
    @DisplayName("박 수를 알면 그 길이로 제안한다")
    void usesNights() {
        DateCandidates.Approx approx = DateCandidates.parseApprox("11|late");
        List<DateCandidates.Range> ranges = DateCandidates.forApprox(approx, 3, TODAY);

        assertThat(ranges).allSatisfy(r -> assertThat(r.end()).isEqualTo(r.start().plusDays(3)));
        assertThat(ranges.get(0).start()).isEqualTo(LocalDate.of(2026, 11, 21));
    }

    @Test
    @DisplayName("지난 달은 다음 해로 보고 문구에 연도를 드러낸다")
    void pastMonthMovesToNextYearWithLabel() {
        DateCandidates.Approx approx = DateCandidates.parseApprox("3|whole");

        assertThat(DateCandidates.resolveMonth(approx, TODAY).getYear()).isEqualTo(2027);
        assertThat(DateCandidates.approxLabel(approx, TODAY)).isEqualTo("2027년 3월");
        assertThat(DateCandidates.phrase(DateCandidates.forApprox(approx, null, TODAY).get(0), TODAY))
                .startsWith("2027년 3월");
    }

    @Test
    @DisplayName("형식이 틀린 대략 날짜는 무시한다")
    void rejectsMalformed() {
        assertThat(DateCandidates.parseApprox("13|mid")).isNull();
        assertThat(DateCandidates.parseApprox("11월 중순")).isNull();
    }

    @Test
    @DisplayName("구간이 이미 지났으면 후보가 없다")
    void emptyWhenWindowPassed() {
        assertThat(DateCandidates.forApprox(DateCandidates.parseApprox("11|early"), null, TODAY)).isEmpty();
    }
}
