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
 * 이 행의 원천 id(place_source_refs)·fsq id·합쳐진 구성원의 원천 id·같은 출처의 같은 이름·주소·좌표 순으로 같은 장소 행을 찾아
 * 그 id를 그대로 쓴다(합쳐진 행은 대표 행). 대표 원천 행과 DB 행이 달라도 구성원 원천 id로 그 행을 찾고, 올린 행에는 이 행의
 * 원천 id를 이어 둔다. 추출에서 빠져 꺼진 행(stale)은 다시 켜고, 정책으로 끈 행(policy)은 쓰지 않는다. 없으면 새로 넣는다.
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
    /** 카탈로그 태그와 같은 구분자다. 원천 id에는 쓰이지 않는다. */
    private static final String REF_SEPARATOR = "\u001f";
    /** existing의 순위. 0 이 행의 원천 id, 1 fsq id, 2 구성원 원천 id, 3 이름·주소·좌표다. */
    private static final int MEMBER_REF_RANK = 2;

    private final java.util.Set<String> mappedCountries = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        List<Map<String, Object>> hits = existing(c);
        Map<String, Object> row = hits.isEmpty() ? null : hits.get(0);
        long id;
        if (row == null) {
            if (c.sourceRefs().size() > 1 && !refsLoaded(c.country())) {
                log.warn("원천 id 매핑 전이라 합쳐진 대표 행을 올리지 않음: {} ({})", c.name(), c.country());
                return Optional.empty();
            }
            id = insert(c.withEmbedding(catalog.embeddingOf(c)));
        } else {
            id = ((Number) row.get("id")).longValue();
            if (!Boolean.TRUE.equals(row.get("active"))) {
                if (!STALE.equals(row.get("deactivated_reason"))) return Optional.empty();
                named.update("UPDATE places SET active = true, deactivated_at = NULL, deactivated_reason = NULL, "
                        + "updated_at = now() WHERE id = :id AND NOT active", new MapSqlParameterSource("id", id));
            }
        }
        if (memberRefsAgree(hits)) recordOwnRef(c, id);
        enricher.request(id);
        return placeRepository.findById(id);
    }

    private static String lockKey(CatalogPlace c) {
        if (!c.sourceRefs().isEmpty()) return "ref:" + c.sourceRefs().get(0);
        return c.fsqPlaceId() != null ? "fsq:" + c.fsqPlaceId()
                : c.source() + "|" + c.name() + "|" + address(c) + "|" + Math.round(c.latitude() / SAME_COORDINATE)
                + "|" + Math.round(c.longitude() / SAME_COORDINATE);
    }

    /**
     * 같은 장소의 DB 행(id·active·deactivated_reason). 원천 id는 DB마다 같은 외부 id라 이름·주소가 바뀌어도 같은 행이고, fsq id는
     * 한 행에만 둘 수 있어 FSQ 원천 id는 매핑이 없어도 fsq_place_id로 찾는다. 합쳐진 행이면 대표 행을 돌려준다.
     */
    private List<Map<String, Object>> existing(CatalogPlace c) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("fsq", c.fsqPlaceId()).addValue("name", c.name())
                .addValue("address", address(c)).addValue("source", c.source()).addValue("lat", c.latitude())
                .addValue("lon", c.longitude()).addValue("eps", SAME_COORDINATE)
                .addValue("refs", String.join(REF_SEPARATOR, c.sourceRefs()));
        List<Map<String, Object>> rows = named.queryForList("""
                WITH refs AS (
                    SELECT split_part(ref, ':', 1) AS source, substr(ref, strpos(ref, ':') + 1) AS record,
                           CASE WHEN ord = 1 THEN 0 ELSE 2 END AS rank
                    FROM unnest(string_to_array(:refs, chr(31))) WITH ORDINALITY AS x(ref, ord)
                ),
                hits AS (
                    SELECT s.place_id AS id, r.rank FROM refs r
                    JOIN place_source_refs s ON s.source = r.source AND s.source_record_id = r.record
                    UNION ALL
                    SELECT p.id, r.rank FROM refs r JOIN places p ON r.source = 'fsq' AND p.fsq_place_id = r.record
                    UNION ALL
                    SELECT id, 1 FROM places WHERE CAST(:fsq AS VARCHAR) IS NOT NULL AND fsq_place_id = :fsq
                    UNION ALL
                    SELECT id, 3 FROM places
                    WHERE name = :name AND address = :address AND source = :source
                      AND abs(latitude - :lat) < :eps AND abs(longitude - :lon) < :eps
                )
                SELECT coalesce(r.id, p.id) AS id, coalesce(r.active, p.active) AS active,
                       CASE WHEN r.id IS NULL THEN p.deactivated_reason ELSE r.deactivated_reason END AS deactivated_reason,
                       h.rank
                FROM hits h JOIN places p ON p.id = h.id LEFT JOIN places r ON r.id = p.merged_into_id
                ORDER BY h.rank, coalesce(r.active, p.active) DESC, p.id
                LIMIT 20
                """, p);
        return rows;
    }

    /**
     * 구성원 원천 id로 찾았는데 그 id들이 서로 다른 장소를 가리키면(연결 배치가 다른 곳으로 판정해 잇지 않은 쌍) 고른 장소가
     * 확실하지 않아 이 행의 원천 id를 잇지 않는다. 이으면 다음 승격부터 그 장소로 굳는다.
     */
    private static boolean memberRefsAgree(List<Map<String, Object>> hits) {
        if (hits.isEmpty() || ((Number) hits.get(0).get("rank")).intValue() != MEMBER_REF_RANK) return true;
        return hits.stream().filter(h -> ((Number) h.get("rank")).intValue() == MEMBER_REF_RANK)
                .map(h -> ((Number) h.get("id")).longValue()).distinct().count() == 1;
    }

    /**
     * 그 나라 장소가 있는데 원천 id 매핑이 하나도 없으면 매핑을 채우기 전이다. 그때 구성원 원천 id로 기존 장소를 찾지 못한 대표
     * 행을 넣으면 core 행과 같은 장소가 두 번 생긴다. 매핑이 생긴 나라는 다시 묻지 않는다.
     */
    private boolean refsLoaded(String country) {
        if (country == null || mappedCountries.contains(country)) return true;
        Boolean ready = named.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM places WHERE country = :country AND active)
                    OR EXISTS (SELECT 1 FROM place_source_refs r JOIN places p ON p.id = r.place_id WHERE p.country = :country)
                """, new MapSqlParameterSource("country", country), Boolean.class);
        if (Boolean.TRUE.equals(ready)) mappedCountries.add(country);
        return Boolean.TRUE.equals(ready);
    }

    /** 이 행의 원천 id가 아직 어느 장소에도 없으면 이 장소에 잇는다. 다른 장소에 이어진 원천 id는 옮기지 않는다. */
    private void recordOwnRef(CatalogPlace c, long id) {
        if (c.sourceRefs().isEmpty()) return;
        String ref = c.sourceRefs().get(0);
        int colon = ref.indexOf(':');
        if (colon <= 0 || colon == ref.length() - 1) return;
        named.update("""
                INSERT INTO place_source_refs (source, source_record_id, place_id) VALUES (:source, :record, :id)
                ON CONFLICT (source, source_record_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("source", ref.substring(0, colon))
                .addValue("record", ref.substring(colon + 1)).addValue("id", id));
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
