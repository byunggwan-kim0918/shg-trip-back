package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI Tool Use 응답의 장소 스키마. 좌표·평점·영업시간은 DB(places)에서 해소한다.
 * placeId로, 없으면 이름+주소로 찾고, 못 찾으면 ItineraryDataMapper가 그 스텝을 버린다.
 *
 * @param subRegion   주소에서 추출한 시/군/구 세부지역, story 컨텍스트용. 없으면 null
 * @param description 장소 한 줄 설명, story 컨텍스트용. 없으면 null
 * @param placeId     후보에서 온 장소 id. LLM 응답에서는 읽지 않는다(지어낸 id가 다른 장소로 저장된다)
 * @param nameKo      해외 장소의 한국어 이름. 없으면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceData(
        String name,
        String address,
        String category,
        String region,
        String country,
        String subRegion,
        String description,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long placeId,
        String nameKo
) {
    public PlaceData(String name, String address, String category, String region, String country) {
        this(name, address, category, region, country, null, null);
    }

    public PlaceData(String name, String address, String category, String region, String country,
                     String subRegion, String description) {
        this(name, address, category, region, country, subRegion, description, null, null);
    }

    /** 화면·스토리에 쓰는 이름. 한국어 이름이 있으면 그것이다. */
    public String displayName() {
        return nameKo != null && !nameKo.isBlank() ? nameKo : name;
    }
}
