package com.shg.trip.shgtrip.domain.place.catalog;

import com.shg.trip.shgtrip.domain.place.batch.PlaceEnrichment;
import com.shg.trip.shgtrip.domain.place.enrich.PromotedPlaceEnricher;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 카탈로그 장소를 운영 DB 장소로 만든다. 일정은 DB 장소 id만 참조하므로 고른 장소는 반드시 이 경로로 저장한다.
 * 같은 fsq id 행이나 같은 출처의 같은 이름·주소·좌표 행이 있으면 그 행의 id를 그대로 쓴다(합쳐진 행은 대표 행). 추출에서 빠져
 * 꺼진 행(stale)은 다시 켜고, 정책으로 끈 행(policy)은 쓰지 않는다. 없으면 새로 넣는다.
 * 올린 행이 보강 전이면 커밋 뒤 바로 보강하도록 요청한다({@link PromotedPlaceEnricher}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceCatalogPromoter {

    /** 같은 행으로 볼 좌표 차이(도). 카탈로그와 DB가 같은 원본 좌표를 소수 8자리로 저장한다. */
    private static final double SAME_COORDINATE = 1e-6;
    /** 적재기 upsert와 같은 기준이다. 추출에서 빠져 꺼진 행만 다시 켜고, 정책·사유 없이 꺼진 행은 그대로 둔다. */
    private static final String STALE = "stale";

    private final PlaceRepository placeRepository;
    private final PlaceCatalog catalog;
    private final NamedParameterJdbcTemplate named;
    private final PromotedPlaceEnricher enricher;

    /** @return 올린 장소. 같은 장소가 추출 누락 말고 다른 이유로 꺼져 있으면 비어 있다 */
    @Transactional
    public Optional<Place> promote(CatalogPlace c) {
        // 두 생성이 같은 장소를 동시에 올리면 둘 다 없는 것으로 보고 두 번 넣는다. 인스턴스가 여럿이어도 같은 키로 막는다.
        named.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))::text",
                new MapSqlParameterSource("key", "place-promote:" + lockKey(c)), String.class);
        Map<String, Object> row = existing(c);
        long id;
        if (row == null) {
            id = insert(c.withEmbedding(catalog.embeddingOf(c)));
        } else {
            id = ((Number) row.get("id")).longValue();
            if (!Boolean.TRUE.equals(row.get("active"))) {
                if (!STALE.equals(row.get("deactivated_reason"))) return Optional.empty();
                named.update("UPDATE places SET active = true, deactivated_at = NULL, deactivated_reason = NULL, "
                        + "updated_at = now() WHERE id = :id AND NOT active", new MapSqlParameterSource("id", id));
            }
        }
        enricher.request(id);
        return placeRepository.findById(id);
    }

    private static String lockKey(CatalogPlace c) {
        return c.fsqPlaceId() != null ? "fsq:" + c.fsqPlaceId()
                : c.source() + "|" + c.name() + "|" + address(c) + "|" + Math.round(c.latitude() / SAME_COORDINATE)
                + "|" + Math.round(c.longitude() / SAME_COORDINATE);
    }

    /**
     * 같은 장소의 DB 행(id·active·deactivated_reason). fsq id가 같으면 이름·주소가 바뀌었어도 같은 행이고, fsq id는 한 행에만
     * 둘 수 있다. 합쳐진 행이면 대표 행을 돌려준다.
     */
    private Map<String, Object> existing(CatalogPlace c) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("fsq", c.fsqPlaceId()).addValue("name", c.name())
                .addValue("address", address(c)).addValue("source", c.source()).addValue("lat", c.latitude())
                .addValue("lon", c.longitude()).addValue("eps", SAME_COORDINATE);
        List<Map<String, Object>> rows = named.queryForList("""
                SELECT coalesce(r.id, p.id) AS id, coalesce(r.active, p.active) AS active,
                       CASE WHEN r.id IS NULL THEN p.deactivated_reason ELSE r.deactivated_reason END AS deactivated_reason
                FROM places p LEFT JOIN places r ON r.id = p.merged_into_id
                WHERE (CAST(:fsq AS VARCHAR) IS NOT NULL AND p.fsq_place_id = :fsq)
                   OR (p.name = :name AND p.address = :address AND p.source = :source
                       AND abs(p.latitude - :lat) < :eps AND abs(p.longitude - :lon) < :eps)
                ORDER BY (p.fsq_place_id IS NOT DISTINCT FROM CAST(:fsq AS VARCHAR)) DESC, coalesce(r.active, p.active) DESC, p.id
                LIMIT 1
                """, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insert(CatalogPlace c) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("name", c.name())
                // 카탈로그 이름은 Wikidata·OSM 한국어 라벨이라 보강 응답과 같은 검사를 통과한 것만 바로 보인다.
                .addValue("nameKo", PlaceEnrichment.verifiedNameKo(c.nameKo(), c.name(), c.country()))
                .addValue("address", address(c))
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
