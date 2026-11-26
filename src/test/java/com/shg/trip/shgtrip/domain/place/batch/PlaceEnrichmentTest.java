package com.shg.trip.shgtrip.domain.place.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceEnrichmentTest {

    @Test
    @DisplayName("해외 카탈로그 한국어 이름은 보강 응답과 같은 검사를 통과해야 쓴다")
    void overseasCatalogNameKoIsChecked() {
        assertThat(PlaceEnrichment.verifiedNameKo("나고야성", "名古屋城", "JP")).isEqualTo("나고야성");
        assertThat(PlaceEnrichment.verifiedNameKo("大須 상점가", "大須商店街", "JP")).isNull();
        assertThat(PlaceEnrichment.verifiedNameKo("ガーラタウン", "ガーラタウン", "JP")).isNull();
        assertThat(PlaceEnrichment.verifiedNameKo(null, "大須商店街", "JP")).isNull();
    }

    @Test
    @DisplayName("국내 장소는 한국어 표시 이름을 쓰지 않아 값을 그대로 둔다")
    void domesticValueIsKept() {
        assertThat(PlaceEnrichment.verifiedNameKo("경복궁", "경복궁", "KR")).isEqualTo("경복궁");
    }
}
