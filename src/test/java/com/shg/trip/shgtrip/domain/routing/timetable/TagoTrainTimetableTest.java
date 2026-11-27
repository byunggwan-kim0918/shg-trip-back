package com.shg.trip.shgtrip.domain.routing.timetable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TagoTrainTimetableTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 열차_목록을_한국_시각과_요금으로_읽는다() throws IOException {
        var r = TagoTrainTimetable.parse(mapper.readTree("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                 {"trainno":"00001","traingradename":"KTX","depplacename":"서울","arrplacename":"부산",
                  "depplandtime":"20261126051300","arrplandtime":"20261126075000","adultcharge":"54400"}]}}}}"""));

        assertThat(r.status()).isEqualTo(RouteStatus.FOUND);
        var t = r.trains().getFirst();
        assertThat(t.departure().toString()).isEqualTo("2026-11-26T05:13+09:00");
        assertThat(t.minutes()).isEqualTo(157);
        assertThat(t.adultFare()).isEqualTo(new BigDecimal("54400"));
    }

    @Test
    void 한_건이면_객체로_와도_읽는다() throws IOException {
        var r = TagoTrainTimetable.parse(mapper.readTree("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                 {"trainno":"1","traingradename":"ITX","depplacename":"a","arrplacename":"b",
                  "depplandtime":"20261126051300","arrplandtime":"20261126061300","adultcharge":""}}}}}"""));

        assertThat(r.trains()).hasSize(1);
        assertThat(r.trains().getFirst().adultFare()).isNull();
    }

    @Test
    void 운행이_없으면_연결_없음이고_인증_오류는_조회_불가다() throws IOException {
        assertThat(TagoTrainTimetable.parse(mapper.readTree(
                "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[]}}}}")).status())
                .isEqualTo(RouteStatus.NO_CONNECTION);
        assertThat(TagoTrainTimetable.parse(mapper.readTree(
                "{\"response\":{\"header\":{\"resultCode\":\"30\"}}}")).status())
                .isEqualTo(RouteStatus.UNAVAILABLE);
    }

    @Test
    void 전체_편수를_읽고_없으면_받은_쪽이_전부다() throws IOException {
        var m = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(TagoTrainTimetable.totalCount(m.readTree("{\"response\":{\"body\":{\"totalCount\":412}}}")))
                .isEqualTo(412);
        assertThat(TagoTrainTimetable.totalCount(m.readTree("{\"response\":{\"body\":{}}}"))).isZero();
    }
}
