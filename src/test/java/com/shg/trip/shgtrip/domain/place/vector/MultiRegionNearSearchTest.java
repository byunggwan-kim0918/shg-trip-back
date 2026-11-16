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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 여러 지역 여행의 날짜별 근처 검색이 반경 안 장소를 지역 구분 없이 받는지 실제 PostgreSQL에서 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class MultiRegionNearSearchTest {

    private static final String NEAR_REGION = "ZZ_NEAR_A";
    private static final String FAR_REGION = "ZZ_NEAR_B";
    private static final int EMBEDDING_DIMENSIONS = 768;
    private static final String SIGHT = "Landmarks and Outdoors > Scenic Lookout";

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    PlaceVectorSearchService placeVectorSearchService;

    private final float[] vector = filled();

    private static float[] filled() {
        float[] v = new float[EMBEDDING_DIMENSIONS];
        Arrays.fill(v, 0.01f);
        return v;
    }

    private Place save(String name, String region, double lat, double lng) {
        Place saved = placeRepository.saveAndFlush(Place.builder()
                .name(name).address(region + " 테스트로").category(SIGHT).region(region).country("KR")
                .source("tourapi").latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .build());
        placeVectorSearchService.store(saved.getId(), vector);
        return saved;
    }

    @Test
    @DisplayName("지역이 둘인 반경 검색은 중심 둘레 장소를 받고 다른 지역의 먼 장소는 받지 않는다")
    void radiusSearchAcrossRegionsStaysNearCenter() {
        Place first = save("근처 전망대 하나", NEAR_REGION, 35.1000, 129.0300);
        Place second = save("근처 전망대 둘", NEAR_REGION, 35.1050, 129.0350);
        Place third = save("근처 전망대 셋", NEAR_REGION, 35.0950, 129.0250);
        for (int i = 0; i < 6; i++) {
            save("먼 전망대 " + i, FAR_REGION, 37.5600 + i * 0.001, 126.9800);
        }

        List<VectorSearchResult> found = placeVectorSearchService.search(new VectorSearchRequest(
                vector, "KR", List.of(NEAR_REGION, FAR_REGION), List.of(), List.of(), null, 4, List.of(),
                new double[]{35.1000, 129.0300}, 5, true));

        assertThat(found).extracting(VectorSearchResult::placeId)
                .contains(first.getId(), second.getId(), third.getId());
        assertThat(found).extracting(VectorSearchResult::region).containsOnly(NEAR_REGION);
    }
}
