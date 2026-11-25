package com.shg.trip.shgtrip.domain.place.catalog;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.math.BigDecimal;
import java.util.List;

/**
 * 카탈로그의 장소 한 행. 아직 운영 DB에 없을 수 있어 id가 없다.
 *
 * @param embedding halfvec 문자열("[0.1,...]"). 이름 대조 결과에는 읽지 않아 null이다
 * @param readings  이름의 규칙 한글 읽기(place-pipeline readings). 초안 이름과 같은 곳인지 대조하는 근거이고 신원은 아니다
 * @param latin     로마자로 읽히지 않는 영어 낱말
 */
public record CatalogPlace(String name, String nameKo, String address, double latitude, double longitude,
                           String category, String region, String country, String source, String fsqPlaceId,
                           String wikidataId, Integer wikidataSitelinks, String placeRole, String cuisine,
                           String description, List<String> tags, String embedding, List<String> readings,
                           List<String> latin) {

    public CatalogPlace {
        readings = readings == null ? List.of() : List.copyOf(readings);
        latin = latin == null ? List.of() : List.copyOf(latin);
    }

    public CatalogPlace(String name, String nameKo, String address, double latitude, double longitude,
                        String category, String region, String country, String source, String fsqPlaceId,
                        String wikidataId, Integer wikidataSitelinks, String placeRole, String cuisine,
                        String description, List<String> tags, String embedding) {
        this(name, nameKo, address, latitude, longitude, category, region, country, source, fsqPlaceId, wikidataId,
                wikidataSitelinks, placeRole, cuisine, description, tags, embedding, List.of(), List.of());
    }

    public CatalogPlace withEmbedding(String value) {
        return new CatalogPlace(name, nameKo, address, latitude, longitude, category, region, country, source, fsqPlaceId,
                wikidataId, wikidataSitelinks, placeRole, cuisine, description, tags, value, readings, latin);
    }

    /** 이름 해석 규칙에 넣을 저장 전 장소. id는 같은 해석 안에서만 쓰는 음수 자리표다. */
    public Place transientPlace(long placeholderId) {
        return Place.builder().id(placeholderId).name(name).nameKo(nameKo).address(address == null ? "" : address)
                .latitude(BigDecimal.valueOf(latitude)).longitude(BigDecimal.valueOf(longitude)).category(category)
                .region(region).country(country).source(source).wikidataId(wikidataId)
                .wikidataSitelinks(wikidataSitelinks).placeRole(placeRole).cuisine(cuisine).description(description)
                .tags(tags).active(true).build();
    }
}
