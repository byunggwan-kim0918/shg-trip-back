package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 해외 장소를 한국어 이름으로 찾는 쿼리와 벡터 검색의 name_ko 컬럼을 실제 PostgreSQL에서 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class PlaceNameKoQueryTest {

    private static final String REGION = "ZZ_NAME_KO_TEST";
    private static final int EMBEDDING_DIMENSIONS = 768;

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    PlaceVectorSearchService placeVectorSearchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Place saveSensoji() {
        Place p = Place.builder()
                .name("浅草寺").nameKo("센소지").address("東京都台東区浅草2-3-1")
                .latitude(BigDecimal.valueOf(35.7148)).longitude(BigDecimal.valueOf(139.7967))
                .category("Community and Government > Spiritual Center > Buddhist Temple")
                .region(REGION).country("JP").source("test")
                .build();
        p.markWikidataMatch("Q1", 30);
        return placeRepository.saveAndFlush(p);
    }

    @Test
    @DisplayName("자유입력·키워드 검색이 한국어 이름으로도 찾는다")
    void namedLookupsMatchNameKo() {
        Long id = saveSensoji().getId();

        assertThat(placeRepository.findActiveByNameLike("센소지", PageRequest.of(0, 5)))
                .extracting(Place::getId).contains(id);
        assertThat(placeRepository.findActiveByNameLikeNearest("센소", 35.71, 139.79, PageRequest.of(0, 5)))
                .extracting(Place::getId).contains(id);
        assertThat(placeRepository.searchByKeyword("센소지", "센소지", null, PageRequest.of(0, 5)).getContent())
                .extracting(Place::getId).first().isEqualTo(id);
    }

    @Test
    @DisplayName("벡터 검색 결과에 한국어 이름과 알려진 정도가 실린다")
    void vectorSearchCarriesNameKo() {
        Long id = saveSensoji().getId();
        jdbcTemplate.update("UPDATE places SET traveler_fame = 2 WHERE id = ?", id);
        float[] vector = new float[EMBEDDING_DIMENSIONS];
        Arrays.fill(vector, 0.01f);
        placeVectorSearchService.store(id, vector);

        List<VectorSearchResult> popular = placeVectorSearchService.findPopular(vector, "JP", List.of(REGION), 5);

        assertThat(popular).extracting(VectorSearchResult::placeId, VectorSearchResult::nameKo,
                        VectorSearchResult::travelerFame)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(id, "센소지", (short) 2));
    }
}
