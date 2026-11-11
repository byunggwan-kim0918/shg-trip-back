package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewaySeederTest {

    private final Destinations destinations = Destinations.fromClasspath();

    @Test
    @DisplayName("모든 관문은 공항 아니면 역이고, 넣은 행은 교통 허브로 분류된다")
    void everyGatewayIsTransitHub() {
        assertThat(destinations.all()).allSatisfy(d -> assertThat(d.gateways()).allSatisfy(g -> {
            assertThat(g.name()).containsAnyOf("Airport", "Station");
            Place place = GatewaySeeder.toPlace(d, g);
            assertThat(PlaceCategoryConstants.majorCategory(place.getCategory())).isEqualTo("TRANSIT_HUB");
            assertThat(place.getRegion()).isEqualTo(d.name());
            assertThat(place.getCountry()).isEqualTo(d.country());
        }));
        assertThat(GatewaySeeder.categoryOf("Odawara Station")).endsWith("Rail Station");
        assertThat(GatewaySeeder.categoryOf("Narita International Airport")).endsWith("Airport");
    }

    @Test
    @DisplayName("적재 범위 나라의 관문만 넣고, 같은 여행지·이름이 있으면 건너뛴다")
    void seedsScopedCountriesAndSkipsExisting() {
        PlaceRepository repository = mock(PlaceRepository.class);
        GatewaySeeder seeder = new GatewaySeeder(repository, destinations);
        ReflectionTestUtils.setField(seeder, "targetCountries", List.of("JP"));
        when(repository.existsBySourceAndRegionAndName(anyString(), anyString(), anyString())).thenReturn(false);
        when(repository.existsBySourceAndRegionAndName(GatewaySeeder.SOURCE, "Tokyo", "Haneda Airport")).thenReturn(true);

        seeder.seed();

        ArgumentCaptor<Place> saved = ArgumentCaptor.forClass(Place.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(p -> assertThat(p.getCountry()).isEqualTo("JP"));
        assertThat(saved.getAllValues()).extracting(Place::getName)
                .contains("Narita International Airport")
                .doesNotContain("Haneda Airport");
        verify(repository, never()).existsBySourceAndRegionAndName(eq(GatewaySeeder.SOURCE), eq("Shanghai"), any());
    }
}
