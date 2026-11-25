package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.catalog.CatalogPlace;
import com.shg.trip.shgtrip.domain.place.catalog.PlaceCatalog;
import com.shg.trip.shgtrip.domain.place.catalog.PlaceCatalogPromoter;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 운영 DB에 없는 초안 장소를 카탈로그(Parquet)에서 같은 규칙으로 찾아 DB로 올린다. */
class DraftPlaceResolverCatalogTest {

    @TempDir
    Path dir;

    private final PlaceRepository repository = mock(PlaceRepository.class);
    private final PlaceCatalogPromoter promoter = mock(PlaceCatalogPromoter.class);
    private PlaceCatalog catalog;
    private DraftPlaceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("""
                    COPY (SELECT name, name_key, name_key AS bare_key, name_ko, name_ko_key, 'Tokyo, Asakusa' AS address,
                                 35.7114 AS latitude, 139.7966 AS longitude, category, 'Tokyo' AS region,
                                 'JP' AS country, 'overture' AS source, NULL::VARCHAR AS fsq_place_id,
                                 NULL::VARCHAR AS wikidata_id, NULL::INTEGER AS wikidata_sitelinks, place_role,
                                 NULL::VARCHAR AS cuisine, NULL::VARCHAR AS description, []::VARCHAR[] AS tags,
                                 CASE WHEN place_role = 'SHOPPING' THEN '[0.5,0.5]' END AS embedding
                          FROM (VALUES
                              ('仲見世商店街', '仲見世商店街', '나카미세 상점가', '나카미세상점가', 'Retail > Shopping Mall', 'SHOPPING'),
                              ('仲見世 煎餅', '仲見世煎餅', NULL, NULL, 'Dining and Drinking > Snack Place', 'MEAL'))
                               t(name, name_key, name_ko, name_ko_key, category, place_role))
                    TO '%s' (FORMAT parquet)""".formatted(dir.resolve("jp.parquet")));
        }
        catalog = new PlaceCatalog(dir.toString());
        resolver = new DraftPlaceResolver(repository, Destinations.fromClasspath(), catalog, promoter);
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willReturn(List.of());
        given(repository.findActiveByNameEdges(anyString(), anyString(), any(), any())).willReturn(List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        catalog.close();
    }

    @Test
    @DisplayName("DB에 없는 상점가는 카탈로그에서 한국어 이름으로 찾아 DB로 올린 행을 돌려준다")
    void pullsMissingPlaceFromCatalog() {
        Place saved = Place.builder().id(77L).name("仲見世商店街").address("Tokyo, Asakusa").category("Retail")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build();
        given(promoter.promote(any())).willReturn(java.util.Optional.of(saved));

        DraftPlaceResolver.Resolution r = resolver.resolve("나카미세 상점가", "shop", List.of("Tokyo"), List.of("도쿄"), null);

        ArgumentCaptor<CatalogPlace> pulled = ArgumentCaptor.forClass(CatalogPlace.class);
        verify(promoter).promote(pulled.capture());
        assertThat(pulled.getValue().name()).isEqualTo("仲見世商店街");
        assertThat(pulled.getValue().embedding()).isNull();
        assertThat(catalog.embeddingOf(pulled.getValue())).isEqualTo("[0.5,0.5]");
        assertThat(r.place().getId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("다른 여행지 카탈로그 행은 올리지 않는다")
    void otherRegionStaysInCatalog() {
        DraftPlaceResolver.Resolution r = resolver.resolve("나카미세 상점가", "shop", List.of("Osaka"), List.of("오사카"), null);

        assertThat(r.place()).isNull();
        verify(promoter, never()).promote(any());
    }

    @Test
    @DisplayName("DB에서 이름 일부만 맞은 곳보다 카탈로그에서 이름이 정확히 같은 곳을 고른다")
    void exactCatalogRowBeatsPartialDbMatch() {
        Place partial = Place.builder().id(6L).name("仲見世商店街 休憩所").nameKo("나카미세 상점가 휴게소").address("")
                .category("Retail > Shopping Mall").placeRole("SHOPPING").latitude(BigDecimal.valueOf(35.71))
                .longitude(BigDecimal.valueOf(139.79)).active(true).build();
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willReturn(List.of(partial));
        given(promoter.promote(any())).willReturn(java.util.Optional.of(Place.builder().id(78L).name("仲見世商店街")
                .address("").category("Retail").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build()));

        assertThat(resolver.resolve("나카미세 상점가", "shop", List.of("Tokyo"), List.of("도쿄"), null).place().getId())
                .isEqualTo(78L);
    }

    @Test
    @DisplayName("카탈로그에서 정확히 맞은 곳이 꺼져 있어 올리지 못하면 DB에서 일부만 맞은 곳을 쓴다")
    void partialDbMatchStaysWhenCatalogRowIsOff() {
        Place partial = Place.builder().id(6L).name("仲見世商店街 休憩所").nameKo("나카미세 상점가 휴게소").address("")
                .category("Retail > Shopping Mall").placeRole("SHOPPING").latitude(BigDecimal.valueOf(35.71))
                .longitude(BigDecimal.valueOf(139.79)).active(true).build();
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willReturn(List.of(partial));
        given(promoter.promote(any())).willReturn(java.util.Optional.empty());

        assertThat(resolver.resolve("나카미세 상점가", "shop", List.of("Tokyo"), List.of("도쿄"), null).place().getId())
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("이름이 여행지 이름뿐인 행은 더 긴 요청 이름의 일부로 잇지 않는다")
    void destinationNamedRowIsNotPartialMatch() {
        Place area = Place.builder().id(7L).name("HAKODATE").nameKo("하코다테").address("")
                .category("Arts and Entertainment > Exhibit").placeRole("SIGHT").region("Hakodate")
                .latitude(BigDecimal.valueOf(41.77)).longitude(BigDecimal.valueOf(140.73)).active(true).build();
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willReturn(List.of(area));

        assertThat(resolver.resolve("하코다테야마 전망대", "sight", List.of("Hakodate"), List.of(), null).place()).isNull();
        assertThat(resolver.resolve("하코다테", "sight", List.of("Hakodate"), List.of(), null).place().getId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("DB에서 찾은 이름은 카탈로그를 보지 않는다")
    void dbHitSkipsCatalog() {
        Place inDb = Place.builder().id(5L).name("仲見世商店街").nameKo("나카미세 상점가").address("")
                .category("Retail > Shopping Mall").placeRole("SHOPPING").latitude(BigDecimal.valueOf(35.71))
                .longitude(BigDecimal.valueOf(139.79)).active(true).build();
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willReturn(List.of(inDb));

        assertThat(resolver.resolve("나카미세 상점가", "shop", List.of("Tokyo"), List.of("도쿄"), null).place().getId())
                .isEqualTo(5L);
        verify(promoter, never()).promote(any());
    }
}
