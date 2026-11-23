package com.shg.trip.shgtrip.domain.place.integrity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceReferenceGuardTest {

    @Test
    @DisplayName("일정이 가리키는 장소가 사라지면 잃은 것으로, 이름·위치가 바뀌거나 꺼지면 따로 센다")
    void classifiesChanges() {
        Map<Long, PlaceReferenceGuard.Ref> before = new HashMap<>();
        before.put(1L, new PlaceReferenceGuard.Ref("성산일출봉", 33.458, 126.942, true));
        before.put(2L, new PlaceReferenceGuard.Ref("광치기해변", 33.452, 126.924, true));
        before.put(3L, new PlaceReferenceGuard.Ref("한라산", 33.362, 126.533, true));
        before.put(4L, new PlaceReferenceGuard.Ref("식당", 33.5, 126.5, true));
        before.put(5L, null);
        Map<Long, PlaceReferenceGuard.Ref> after = new HashMap<>();
        after.put(1L, new PlaceReferenceGuard.Ref("성산일출봉", 33.4581, 126.9421, true));
        after.put(2L, new PlaceReferenceGuard.Ref("다른가게", 33.452, 126.924, true));
        after.put(3L, new PlaceReferenceGuard.Ref("한라산", 33.5, 126.533, false));

        PlaceReferenceGuard.Report report = PlaceReferenceGuard.compare(before, after);

        assertThat(report.missing()).containsExactly(4L);
        assertThat(report.renamed()).containsExactly(2L);
        assertThat(report.moved()).containsExactly(3L);
        assertThat(report.deactivated()).containsExactly(3L);
        assertThat(report.lostPlaces()).isTrue();
    }
}
