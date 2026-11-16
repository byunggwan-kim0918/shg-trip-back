package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningPipelineTest {

    @Test
    @DisplayName("v1이면 어느 나라도 날짜별 확정으로 보내지 않는다")
    void v1KeepsEveryTripOnRouteOptimizer() {
        PlanningPipeline pipeline = new PlanningPipeline("v1");

        assertThat(pipeline.serialFor("KR")).isFalse();
        assertThat(pipeline.serialFor("JP")).isFalse();
    }

    @Test
    @DisplayName("v2는 국내 여행만 날짜별 확정으로 보내고 해외와 나라를 모르는 요청은 v1으로 둔다")
    void v2SendsOnlyDomesticTrips() {
        PlanningPipeline pipeline = new PlanningPipeline(" V2 ");

        assertThat(pipeline.serialFor("KR")).isTrue();
        assertThat(pipeline.serialFor("kr ")).isTrue();
        assertThat(pipeline.serialFor("JP")).isFalse();
        assertThat(pipeline.serialFor(null)).isFalse();
    }

    @Test
    @DisplayName("모르는 값이면 기동할 때 실패한다")
    void unknownValueFailsFast() {
        assertThatThrownBy(() -> new PlanningPipeline("v3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningPipeline("")).isInstanceOf(IllegalArgumentException.class);
    }
}
