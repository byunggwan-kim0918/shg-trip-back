package com.shg.trip.shgtrip.domain.place.catalog;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 카탈로그 장소를 운영 DB 장소로 만든다. 일정은 DB 장소 id만 참조하므로 고른 장소는 반드시 이 경로로 저장한다.
 * 같은 출처의 같은 이름·주소·좌표 행이 있으면(꺼진 행 포함) 그 행을 다시 켜고 id를 그대로 쓴다. 없으면 새로 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceCatalogPromoter {

    /** 같은 행으로 볼 좌표 차이(도). 카탈로그와 DB가 같은 원본 좌표를 소수 8자리로 저장한다. */
    private static final double SAME_COORDINATE = 1e-6;

    private final PlaceRepository placeRepository;
    private final PlaceCatalog catalog;
    private final NamedParameterJdbcTemplate named;

    @Transactional
    public Place promote(CatalogPlace c) {
        List<Long> same = named.queryForList("""
                SELECT id FROM places
                WHERE name = :name AND address = :address AND source = :source AND merged_into_id IS NULL
                  AND abs(latitude - :lat) < :eps AND abs(longitude - :lon) < :eps
                ORDER BY active DESC, id LIMIT 1
                """, new MapSqlParameterSource().addValue("name", c.name()).addValue("address", address(c))
                .addValue("source", c.source()).addValue("lat", c.latitude()).addValue("lon", c.longitude())
                .addValue("eps", SAME_COORDINATE), Long.class);
        long id = same.isEmpty() ? insert(c.withEmbedding(catalog.embeddingOf(c))) : reactivate(same.get(0));
        return placeRepository.findById(id).orElseThrow();
    }

    private long reactivate(long id) {
        named.update("UPDATE places SET active = true, deactivated_at = NULL, deactivated_reason = NULL, "
                + "updated_at = now() WHERE id = :id AND NOT active", new MapSqlParameterSource("id", id));
        return id;
    }

    private long insert(CatalogPlace c) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("name", c.name()).addValue("nameKo", c.nameKo()).addValue("address", address(c))
                .addValue("lat", c.latitude()).addValue("lon", c.longitude()).addValue("category", c.category())
                .addValue("region", c.region()).addValue("country", c.country()).addValue("source", c.source())
                .addValue("fsq", c.fsqPlaceId()).addValue("qid", c.wikidataId())
                .addValue("sitelinks", c.wikidataSitelinks()).addValue("role", c.placeRole())
                .addValue("cuisine", c.cuisine()).addValue("description", c.description())
                .addValue("tags", c.tags() == null || c.tags().isEmpty() ? null : String.join("\u001f", c.tags()))
                .addValue("embedding", c.embedding());
        Long id = named.queryForObject("""
                INSERT INTO places (name, name_ko, address, latitude, longitude, category, region, country, source,
                                    fsq_place_id, wikidata_id, wikidata_sitelinks, place_role, cuisine, description, tags,
                                    embedding, active)
                VALUES (:name, :nameKo, :address, :lat, :lon, :category, :region, :country, :source, :fsq, :qid,
                        :sitelinks, :role, :cuisine, :description, string_to_array(:tags, chr(31)),
                        CAST(:embedding AS halfvec(768)), true)
                RETURNING id
                """, p, Long.class);
        log.info("카탈로그 장소 적재: {} ({}) → id {}", c.name(), c.region(), id);
        return id;
    }

    private static String address(CatalogPlace c) {
        return c.address() == null ? "" : c.address();
    }
}
