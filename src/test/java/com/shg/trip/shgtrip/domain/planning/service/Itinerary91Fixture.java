package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;

/**
 * itinerary 91(부산 5박 6일, 커플·여유·자동차) 운영 생성 기록. 후보 70곳·선택·생성 인자·저장된 스텝이다.
 * 같은 날 짬뽕 두 끼, 조개 반복, 카페만 도는 3일차, 하루 136km, 다른 도시 주소를 재현하는 기준 입력이다.
 */
final class Itinerary91Fixture {

    private Itinerary91Fixture() {}

    static TraceFile load() {
        return TraceFixtures.load("/fixtures/itinerary-91-busan.json");
    }
}
