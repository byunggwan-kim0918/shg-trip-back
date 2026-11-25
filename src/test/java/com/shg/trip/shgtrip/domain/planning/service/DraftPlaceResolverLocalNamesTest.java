package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.catalog.CatalogPlace;
import com.shg.trip.shgtrip.domain.place.catalog.PlaceCatalog;
import com.shg.trip.shgtrip.domain.place.catalog.PlaceCatalogPromoter;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 초안이 함께 낸 현지어 이름으로 장소를 찾는다. 현지어 이름은 검색어라 정확·별칭 일치만 받는다. */
class DraftPlaceResolverLocalNamesTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";

    @TempDir
    Path dir;

    private final PlaceRepository repository = mock(PlaceRepository.class);
    private final PlaceCatalogPromoter promoter = mock(PlaceCatalogPromoter.class);
    private final List<Place> db = new ArrayList<>();
    private PlaceCatalog catalog;
    private DraftPlaceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("""
                    COPY (SELECT name, name_key, NULL::VARCHAR AS name_ko, NULL::VARCHAR AS name_ko_key, '' AS address,
                                 lat AS latitude, 136.65 AS longitude, 'Landmarks and Outdoors > Historic and Protected Site'
                                 AS category, region, 'JP' AS country, 'overture' AS source, NULL::VARCHAR AS fsq_place_id,
                                 NULL::VARCHAR AS wikidata_id, NULL::INTEGER AS wikidata_sitelinks, NULL::VARCHAR AS place_role,
                                 NULL::VARCHAR AS cuisine, NULL::VARCHAR AS description, []::VARCHAR[] AS tags,
                                 NULL::VARCHAR AS embedding
                          FROM (VALUES ('東茶屋街', '東茶屋街', 36.572, 'Kanazawa'),
                                       ('主計町茶屋街', '主計町茶屋街', 36.571, 'Kanazawa'),
                                       ('北一硝子三号館', '北一硝子三号館', 43.19, 'Sapporo'),
                                       ('浅野川', '浅野川', 36.57, 'Kanazawa'),
                                       ('八幡神社', '八幡神社', 34.65, 'Okayama'),
                                       ('八幡神社', '八幡神社', 35.66, 'Tokyo'),
                                       ('八幡神社', '八幡神社', 35.28, 'Hakone'))
                               t(name, name_key, lat, region))
                    TO '%s' (FORMAT parquet)""".formatted(dir.resolve("jp.parquet")));
        }
        catalog = new PlaceCatalog(dir.toString());
        resolver = new DraftPlaceResolver(repository, Destinations.fromClasspath(), catalog, promoter);
        given(repository.findActiveByCompactName(anyString(), anyInt(), any(), any())).willAnswer(inv -> {
            String q = inv.getArgument(0);
            int min = inv.getArgument(1);
            Collection<String> regions = inv.getArgument(2);
            return db.stream().filter(p -> regions.contains(p.getRegion()))
                    .filter(p -> contains(p.getName(), q, min) || contains(p.getNameKo(), q, min)).toList();
        });
        given(repository.findActiveByNameEdges(anyString(), anyString(), any(), any())).willReturn(List.of());
        given(promoter.promote(any())).willAnswer(inv -> {
            CatalogPlace c = inv.getArgument(0);
            return java.util.Optional.of(Place.builder().id(900L).name(c.name()).address("").category(c.category()).region(c.region())
                    .latitude(BigDecimal.valueOf(c.latitude())).longitude(BigDecimal.valueOf(c.longitude())).build());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        catalog.close();
    }

    private static boolean contains(String name, String q, int min) {
        if (name == null) return false;
        String k = PlaceNames.exactKey(name);
        return k.contains(q) || k.length() >= min && q.contains(k);
    }

    private static Place place(long id, String name, String nameKo, String region, double lat, double lng) {
        return Place.builder().id(id).name(name).nameKo(nameKo).address("").category(SIGHT).placeRole("SIGHT")
                .region(region).latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng)).active(true)
                .build();
    }

    @Test
    @DisplayName("한국어 이름이 없는 카탈로그 행은 현지어 이름이 정확히 같을 때 끌어온다")
    void localExactPullsUnnamedCatalogRow() {
        DraftPlaceResolver.Resolution r = resolver.resolveNamed("히가시차야가이", List.of("東茶屋街"), "sight",
                List.of("Kanazawa"), List.of("가나자와"), null);

        ArgumentCaptor<CatalogPlace> pulled = ArgumentCaptor.forClass(CatalogPlace.class);
        verify(promoter).promote(pulled.capture());
        assertThat(pulled.getValue().name()).isEqualTo("東茶屋街");
        assertThat(r.place().getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("현지어 이름을 잘못 추측해 일부만 맞거나 다른 여행지 장소와 같으면 끌어오지 않는다")
    void wrongLocalGuessDoesNotLink() {
        assertThat(resolver.resolveNamed("히가시차야", List.of("茶屋街"), "sight", List.of("Kanazawa"),
                List.of("가나자와"), null).place()).isNull();
        assertThat(resolver.resolveNamed("기타이치 글라스", List.of("北一硝子三号館"), "sight", List.of("Kanazawa"),
                List.of("가나자와"), null).place()).isNull();
        verify(promoter, never()).promote(any());
    }

    @Test
    @DisplayName("현지어 이름이 정확히 같아도 한국어 이름이 다른 곳을 가리키면 잇지 않는다")
    void localNameOfAnotherPlaceIsNotLinked() {
        assertThat(resolver.resolveNamed("사이가와 강변", List.of("浅野川"), "sight", List.of("Kanazawa"),
                List.of("가나자와"), null).place()).isNull();
        verify(promoter, never()).promote(any());

        assertThat(resolver.resolveNamed("아사노가와", List.of("浅野川"), "sight", List.of("Kanazawa"),
                List.of("가나자와"), null).place().getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("한국어 이름은 딸린 시설에만 일부 맞고 현지어 이름이 본래 장소와 같으면 본래 장소를 고른다")
    void localExactBeatsKoreanPartialMatch() {
        db.add(place(1, "厳島神社 能舞台", "이쓰쿠시마 신사 노 무대", "Miyajima", 34.295, 132.319));
        db.add(place(2, "厳島神社", null, "Miyajima", 34.296, 132.320));

        DraftPlaceResolver.Resolution r = resolver.resolveNamed("이쓰쿠시마 신사", List.of("厳島神社"), "sight",
                List.of("Miyajima"), List.of("미야지마"), null);

        assertThat(r.place().getId()).isEqualTo(2L);
        assertThat(r.match()).isEqualTo(PlaceNames.Match.EXACT);
    }

    @Test
    @DisplayName("여행지 반경 밖 이웃 여행지의 장소는 이름이 정확히 같을 때만 잇는다")
    void neighborDestinationNeedsExactName() {
        db.add(place(3, "ファーム富田", "팜 도미타", "Furano", 43.418, 142.427));

        assertThat(resolver.resolveNamed("팜 도미타", List.of(), "sight", List.of("Biei"), List.of("비에이"), null)
                .place().getId()).isEqualTo(3L);
        assertThat(resolver.resolveNamed("도미타", List.of(), "sight", List.of("Biei"), List.of("비에이"), null)
                .place()).isNull();
    }

    @Test
    @DisplayName("이웃 여행지의 장소가 같은 나라 여러 여행지에 흔한 이름이면 잇지 않는다")
    void neighborCommonNameIsNotLinked() {
        assertThat(resolver.neighborRegions(List.of("Naoshima"))).contains("Okayama");

        assertThat(resolver.resolveNamed("하치만 신사", List.of("八幡神社"), "sight", List.of("Naoshima"),
                List.of("나오시마"), null).place()).isNull();
        verify(promoter, never()).promote(any());
    }

    @Test
    @DisplayName("흔한 이름은 DB에만 있어도, 괄호 설명이 붙어도 이웃 여행지에서 잇지 않는다")
    void neighborCommonNameInDbIsNotLinked() {
        db.add(place(10, "天満宮（玉野）", null, "Okayama", 34.49, 133.95));
        db.add(place(11, "天満宮", null, "Tokyo", 35.66, 139.70));
        db.add(place(12, "天満宮", null, "Hakone", 35.23, 139.10));

        assertThat(resolver.resolveNamed("덴만구", List.of("天満宮"), "sight", List.of("Naoshima"),
                List.of("나오시마"), null).place()).isNull();
    }

    @Test
    @DisplayName("이웃 여행지는 반경 합에 근교 여유를 더한 거리 안의 같은 나라 여행지다")
    void neighborRegionsAreNearbySameCountry() {
        assertThat(resolver.neighborRegions(List.of("Biei"))).contains("Furano");
        assertThat(resolver.neighborRegions(List.of("Yufuin"))).contains("Beppu");
        assertThat(resolver.neighborRegions(List.of("Kanazawa"))).doesNotContain("Kyoto", "Biei");
    }
}
