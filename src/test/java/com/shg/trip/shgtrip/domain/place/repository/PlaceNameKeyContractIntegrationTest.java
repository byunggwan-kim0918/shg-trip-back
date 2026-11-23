package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 함수 place_name_key가 Java·Python과 같은 계약을 지키고, 이름 조회가 그 키로 띄어쓰기 차이를 넘는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class PlaceNameKeyContractIntegrationTest {

    private static final String REGION = "ZZ_NAME_KEY_TEST";

    @MockBean
    S3Client s3Client;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlaceRepository placeRepository;

    @Test
    @DisplayName("place_name_key는 계약 파일과 같은 키를 낸다")
    void databaseKeyFollowsContract() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/place-names/exact-keys.tsv")) {
            List<String[]> rows = Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split("\t", -1)).toList();
            assertThat(rows).isNotEmpty();
            for (String[] row : rows) {
                assertThat(jdbcTemplate.queryForObject("SELECT place_name_key(?)", String.class, row[0]))
                        .as(row[0]).isEqualTo(row[1]);
            }
        }
    }

    @Test
    @DisplayName("키워드 검색과 자유입력 장소 조회는 띄어쓰기만 다른 이름도 찾는다")
    void keywordSearchIgnoresSpacing() {
        Long id = placeRepository.saveAndFlush(Place.builder()
                .name("福岡タワー").nameKo("후쿠오카 타워").address("福岡市早良区百道浜2-3-26")
                .latitude(BigDecimal.valueOf(33.5933)).longitude(BigDecimal.valueOf(130.3515))
                .category("Landmarks and Outdoors > Scenic Lookout").region(REGION).country("JP").source("test")
                .build()).getId();

        assertThat(placeRepository.searchByKeywordInRegions("후쿠오카타워", "후쿠오카타워",
                PlaceNames.exactKey("후쿠오카타워"), List.of(REGION), PageRequest.of(0, 5)))
                .extracting(Place::getId).containsExactly(id);
        assertThat(placeRepository.findActiveByNameLikeNearest("후쿠오카타워", PlaceNames.exactKey("후쿠오카타워"),
                33.59, 130.35, PageRequest.of(0, 5))).extracting(Place::getId).contains(id);
        assertThat(placeRepository.searchByKeywordInRegions("!!!", "!!!", PlaceNames.exactKey("!!!"),
                List.of(REGION), PageRequest.of(0, 5))).isEmpty();
    }

    @Test
    @DisplayName("이름 조회는 역할이 없는 행과 같은 곳으로 합쳐진 행도 돌려준다")
    void compactNameReturnsRoleLessAndMergedRows() {
        Place representative = placeRepository.saveAndFlush(Place.builder()
                .name("承天寺").nameKo("조텐지").address("福岡市博多区博多駅前1-29-9").placeRole("SIGHT")
                .latitude(BigDecimal.valueOf(33.596)).longitude(BigDecimal.valueOf(130.411))
                .category("Community and Government > Spiritual Center > Buddhist Temple").region(REGION)
                .country("JP").source("test").build());
        Place merged = placeRepository.saveAndFlush(Place.builder()
                .name("承天寺 Jotenji").nameKo("쇼텐지").address("福岡市博多区博多駅前1-29-9").placeRole("SIGHT")
                .latitude(BigDecimal.valueOf(33.596)).longitude(BigDecimal.valueOf(130.412))
                .category("Community and Government > Spiritual Center > Buddhist Temple").region(REGION)
                .country("JP").source("test").build());
        jdbcTemplate.update("UPDATE places SET merged_into_id = ?, merge_relation = 'SAME' WHERE id = ?",
                representative.getId(), merged.getId());
        Long roleLess = placeRepository.saveAndFlush(Place.builder()
                .name("제주민속촌").address("서귀포시 표선면").latitude(BigDecimal.valueOf(33.32))
                .longitude(BigDecimal.valueOf(126.84)).category("Landmarks and Outdoors > Tourist Attraction")
                .region(REGION).country("KR").source("test").build()).getId();

        assertThat(placeRepository.findActiveByCompactName("쇼텐지", 3, List.of(REGION), List.of("SIGHT")))
                .extracting(Place::getId).containsExactly(merged.getId());
        assertThat(placeRepository.findActiveByCompactName("제주민속촌", 3, List.of(REGION), List.of("SIGHT")))
                .extracting(Place::getId).containsExactly(roleLess);
    }
}
