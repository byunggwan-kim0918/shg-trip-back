package com.shg.trip.shgtrip.domain.place.vector;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 작은 지역은 HNSW 대신 정확 검색으로 가장 가까운 장소를 빠짐없이 돌려준다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class PgVectorExactScanIntegrationTest {

    private static final String REGION = "ZZ_EXACT_SCAN";
    private static final int DIMENSIONS = 768;

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    PgVectorPlaceSearchService service;

    private long save(String name, float first) {
        Place p = placeRepository.saveAndFlush(Place.builder().name(name).address("addr").category("Landmarks and Outdoors > Park")
                .latitude(BigDecimal.valueOf(35.0)).longitude(BigDecimal.valueOf(135.0)).region(REGION).country("JP")
                .source("test").build());
        float[] v = new float[DIMENSIONS];
        v[0] = first;
        v[1] = 1 - first;
        service.store(p.getId(), v);
        return p.getId();
    }

    @Test
    @DisplayName("행 수를 센 작은 지역은 정확 검색 경로로 가까운 순서대로 돌려준다")
    void smallRegionUsesExactScan() {
        long near = save("가까운 공원", 0.9f);
        long mid = save("중간 공원", 0.5f);
        long far = save("먼 공원", 0.1f);
        float[] q = new float[DIMENSIONS];
        q[0] = 1;
        // 다른 테스트가 먼저 센 행 수가 남아 있으면 이 지역을 모른다(운영에서는 다음 갱신까지 HNSW로 간다).
        org.springframework.test.util.ReflectionTestUtils.setField(service, "regionSizesAt", java.time.Instant.EPOCH);

        assertThat(service.smallEnoughForExactScan(List.of(REGION))).isTrue();
        List<VectorSearchResult> results = service.search(new VectorSearchRequest(q, "JP", List.of(REGION), List.of(),
                List.of(), null, 3, List.of(), null, 0, false));
        assertThat(results).extracting(VectorSearchResult::placeId).containsExactly(near, mid, far);
        assertThat(service.smallEnoughForExactScan(List.of("ZZ_UNKNOWN_REGION"))).isFalse();
    }
}
