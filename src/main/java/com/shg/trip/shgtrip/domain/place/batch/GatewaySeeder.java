package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 해외 여행지 관문(destinations.json gateways)을 그 여행지 장소로 넣는다. 관문 공항은 대개 여행지 반경 밖이라
 * FSQ 추출에 들어오지 않아, 없으면 도착·출발 허브가 시내 역으로 잡힌다. 이미 있으면(같은 여행지·이름) 넣지 않는다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class GatewaySeeder {

    static final String SOURCE = "gateway";

    private final PlaceRepository placeRepository;
    private final Destinations destinations;

    /** 외부 호출 없이 수십 행이라 기본은 전 국가다. 일정 생성은 planning.enabled-countries로 따로 막는다. */
    @Value("${batch.gateways.countries:ALL}")
    private List<String> targetCountries;

    public void seed() {
        List<String> countries = WikidataSitelinksJob.countryScope(targetCountries, destinations, "batch.gateways.countries");
        int inserted = 0;
        int existing = 0;
        for (Destination d : destinations.all()) {
            if (!countries.isEmpty() && !countries.contains(d.country())) continue;
            for (Destination.Gateway g : d.gateways()) {
                if (placeRepository.existsBySourceAndRegionAndName(SOURCE, d.name(), g.name())) {
                    existing++;
                    continue;
                }
                placeRepository.save(toPlace(d, g));
                inserted++;
            }
        }
        log.info("해외 관문 적재 완료 - 신규 {}곳, 기존 {}곳", inserted, existing);
    }

    static Place toPlace(Destination d, Destination.Gateway g) {
        OffsetDateTime now = OffsetDateTime.now();
        return Place.builder()
                .name(g.name())
                .address(g.name() + ", " + d.name())
                .latitude(BigDecimal.valueOf(g.lat()))
                .longitude(BigDecimal.valueOf(g.lng()))
                .category(categoryOf(g.name()))
                .region(d.name())
                .country(d.country())
                .source(SOURCE)
                .placeRole("TRANSIT")
                .active(true)
                .savedAt(now)
                .build();
    }

    /** 관문은 공항 아니면 기차역이다. 리프가 둘 중 하나여야 대분류가 교통 허브로 잡힌다. */
    static String categoryOf(String name) {
        return name.toLowerCase(Locale.ROOT).contains("airport")
                ? "Travel and Transportation > Transport Hub > Airport"
                : "Travel and Transportation > Transport Hub > Rail Station";
    }
}
