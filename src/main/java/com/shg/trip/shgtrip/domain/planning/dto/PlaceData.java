package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * AI Tool Use 응답의 장소 스키마. 좌표·평점·영업시간은 DB(places)에서 해소한다.
 * 이름+주소로 못 찾으면 ItineraryDataMapper가 그 스텝을 버린다.
 *
 * @param subRegion   주소에서 추출한 시/군/구 세부지역, story 컨텍스트용. 없으면 null
 * @param description 장소 한 줄 설명, story 컨텍스트용. 없으면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceData(
        String name,
        String address,
        String category,
        String region,
        String country,
        String subRegion,
        String description
) {
    public PlaceData(String name, String address, String category, String region, String country) {
        this(name, address, category, region, country, null, null);
    }
}
