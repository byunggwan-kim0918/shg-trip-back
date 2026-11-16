package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;

/**
 * 일정을 확정한 방식. 생성 기록에 남긴다. v2 기록은 {@code --eval.pipeline=v2 --eval.selection=guided}로 돌려야 같은
 * 일정이 다시 나온다.
 *
 * @param dayPicks v2가 날짜별로 고른 장소. v1으로 확정했으면 null이다
 */
public record ScheduledBy(Pipeline pipeline, SelectionOutput dayPicks) {

    public static final ScheduledBy V1 = new ScheduledBy(Pipeline.V1, null);

    public enum Pipeline {
        V1("v1"),
        V2("v2"),
        /** v2가 예외로 끝나 그 생성만 v1이 확정했다. */
        V2_FALLBACK("v2-fallback");

        private final String label;

        Pipeline(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
