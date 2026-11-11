package com.shg.trip.shgtrip.domain.place.destination;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 해외 여행지 하나({@code destinations.json}의 한 항목).
 *
 * @param name     ASCII 표준명. 해외 장소의 {@code places.region} 값이며 국가 간에도 유일하다.
 * @param radiusKm 이 반경 안의 장소만 이 여행지에 배정된다
 * @param kind     city | scenic | landmark
 * @param parent   묶어 다니는 상위 여행지 이름, 없으면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Destination(String country, String name, String nameKo, double lat, double lng, double radiusKm,
                          String kind, String parent, List<String> aliases, List<Gateway> gateways) {

    public Destination {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        gateways = gateways == null ? List.of() : List.copyOf(gateways);
    }

    public boolean isCity() {
        return "city".equals(kind);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gateway(String name, double lat, double lng) {}
}
