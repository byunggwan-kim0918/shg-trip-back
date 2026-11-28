package com.shg.trip.shgtrip.domain.routing;

/**
 * 경로 엔진이 계산한 대중교통 구간을 운영기관 공식 시간표와 대조한다. 공급자 설정 verifiers에 id를 적어 붙인다.
 * 공식 시간표로 확인한 구간만 확정 시각으로 올리고, 확인 못 한 구간은 원래 근거를 그대로 둔다.
 */
public interface ScheduleVerifier {

    enum Verdict {
        /** 공식 시간표에 같은 날 같은 출발·도착 편이 있다. */
        CONFIRMED,
        /** 공식 시간표를 받았지만 그 편이 없다. */
        NOT_IN_OFFICIAL,
        /** 이 검증기가 다루지 않거나 공식 시간표를 받지 못했다. */
        UNKNOWN
    }

    /** @param fare 공식 시간표가 준 그 편의 어른 운임. 주지 않으면 null이다 */
    record Check(Verdict verdict, Fare fare) {}

    String id();

    Verdict verify(RouteLeg leg);

    default Check check(RouteLeg leg) {
        return new Check(verify(leg), null);
    }
}
