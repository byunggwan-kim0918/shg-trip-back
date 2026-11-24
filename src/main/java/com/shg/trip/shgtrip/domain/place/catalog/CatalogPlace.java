package com.shg.trip.shgtrip.domain.place.catalog;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.math.BigDecimal;
import java.util.List;

/**
 * 카탈로그의 장소 한 행. 아직 운영 DB에 없을 수 있어 id가 없다.
 *
 * @param embedding halfvec 문자열("[0.1,...]"). 이름 대조 결과에는 읽지 않아 null이다
 */
public record CatalogPlace(String name, String nameKo, String address, double latitude, double longitude,
                           String category, String region, String country, String source, String fsqPlaceId,
                           String wikidataId, Integer wikidataSitelinks, String placeRole, String cuisine,
                           String description, List<String> tags, String embedding) {

    public CatalogPlace withEmbedding(String value) {
        return new CatalogPlace(name, nameKo, address, latitude, longitude, category, region, country, source, fsqPlaceId,
                wikidataId, wikidataSitelinks, placeRole, cuisine, description, tags, value);
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
