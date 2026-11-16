package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
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

/** 같은 장소 판정으로 합쳐진 행이 생성 후보를 만드는 쿼리에서 빠지는지 실제 PostgreSQL에서 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class MergedPlaceExclusionTest {

    private static final String REGION = "ZZ_MERGED_TEST";
    private static final int EMBEDDING_DIMENSIONS = 768;

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    PlaceVectorSearchService placeVectorSearchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final float[] vector = filled();

    private static float[] filled() {
        float[] v = new float[EMBEDDING_DIMENSIONS];
        Arrays.fill(v, 0.01f);
        return v;
    }

    private Place save(String name, String category, String source, double dLat) {
        Place p = Place.builder()
                .name(name).address("부산 중구").category(category).region(REGION).country("KR").source(source)
                .latitude(BigDecimal.valueOf(35.1009 + dLat)).longitude(BigDecimal.valueOf(129.0323))
                .imageUrl("https://example.com/" + name + ".jpg")
                .build();
        p.markWikidataMatch("Q" + name.hashCode(), 10);
        Place saved = placeRepository.saveAndFlush(p);
        placeVectorSearchService.store(saved.getId(), vector);
        return saved;
    }

    private void merge(Place row, Place into) {
        jdbcTemplate.update("UPDATE places SET merged_into_id = ?, merge_relation = 'SAME' WHERE id = ?",
                into.getId(), row.getId());
    }

    @Test
    @DisplayName("합쳐진 행은 인기도·유사도 검색에 나오지 않고 대표 행만 나온다")
    void vectorSearchesSkipMergedRows() {
        String sight = "Landmarks and Outdoors > Scenic Lookout";
        Place tower = save("부산타워", sight, "tourapi", 0);
        Place diamond = save("부산다이아몬드타워", sight, "foursquare", 0.0002);
        merge(diamond, tower);

        List<VectorSearchResult> popular = placeVectorSearchService.findPopular(vector, "KR", List.of(REGION), 10);
        List<VectorSearchResult> similar = placeVectorSearchService.search(new VectorSearchRequest(
                vector, "KR", List.of(REGION), List.of(), List.of(), null, 10, List.of(), null, 0, true));

        assertThat(popular).extracting(VectorSearchResult::placeId).containsExactly(tower.getId());
        assertThat(similar).extracting(VectorSearchResult::placeId).contains(tower.getId())
                .doesNotContain(diamond.getId());
    }

    @Test
    @DisplayName("숙소 보완·관문·표지 후보도 합쳐진 행을 고르지 않는다")
    void supplementQueriesSkipMergedRows() {
        Place hotel = save("코오롱호텔", "Travel and Transportation > Lodging > Hotel", "tourapi", 0);
        Place hotelEn = save("Kolon Hotel Gyeongju", "Travel and Transportation > Lodging > Hotel", "foursquare", 0.0005);
        merge(hotelEn, hotel);
        Place gate = save("김해공항", "Travel and Transportation > Transport Hub > Airport", "gateway", 0.01);
        Place gateDup = save("Gimhae Airport", "Travel and Transportation > Transport Hub > Airport", "gateway", 0.0101);
        merge(gateDup, gate);
        Place park = save("용두산공원", "Landmarks and Outdoors > Park", "tourapi", 0.002);
        Place parkDup = save("용두산공원 (Yongdusan Park)", "Landmarks and Outdoors > Park", "foursquare", 0.0021);
        merge(parkDup, park);

        assertThat(placeRepository.findTopAccommodationsByRegions(List.of(REGION), PageRequest.of(0, 10)))
                .extracting(Place::getId).containsExactly(hotel.getId());
        assertThat(placeRepository.findGatewaysByRegions(List.of(REGION)))
                .extracting(Place::getId).containsExactly(gate.getId());
        assertThat(placeRepository.findCoverCandidates(REGION, PageRequest.of(0, 10)))
                .extracting(Place::getId).doesNotContain(parkDup.getId()).contains(park.getId());
    }
}
