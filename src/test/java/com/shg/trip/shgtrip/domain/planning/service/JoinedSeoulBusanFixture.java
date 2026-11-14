package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;

/**
 * 운영 기록 19(서울 2박 3일)와 23(부산 2박 3일)을 {@code scripts/eval/join-traces.py}로 이어 붙인 도보 5박 6일.
 * 국내에는 도시를 옮기는 생성 기록이 없어 도시 구간 분리의 기준 입력으로 쓴다. 저장된 스텝은 없다.
 */
final class JoinedSeoulBusanFixture {

    private JoinedSeoulBusanFixture() {}

    static TraceFile load() {
        return TraceFixtures.load("/fixtures/joined-19-23-seoul-busan.json");
    }
}
