package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RegistryAroundTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private PlaceVectorSearchService placeVectorSearchService;
    @Mock private PlaceFactsReader factsReader;

    @Test
    @DisplayName("여행 도시 범위 밖이면서 공항 둘레인 지점만 여행지 지역으로 거르지 않는다")
    void outsideTripPointSkipsRegionFilter() {
        VectorEnrichedInput input = new VectorEnrichedInput("서울", List.of("food"), List.of("attraction"), "normal",
                "any", new BigDecimal("500000"), LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 2), "서울 여행",
                List.of(), "서울", "KR", List.of("Seoul"), List.of("명소"), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.COUPLE, 2, List.of());
        given(embeddingService.embedBatch(anyList())).willReturn(List.of(new float[]{0.1f, 0.2f}));
        given(placeVectorSearchService.search(any(VectorSearchRequest.class))).willReturn(List.of());
        DraftItineraryPlanner.Registry registry = new DraftItineraryPlanner.Registry(factsReader, List.of("서울"),
                new VectorSearchQueryService(embeddingService, placeVectorSearchService).nearSearch(input),
                List.of(DomesticCities.find("서울").orElseThrow()));
        registry.useHubs(hub("인천국제공항", 37.4602, 126.4407));
        double[] gangnam = {37.498, 127.027};
        double[] nearIncheonAirport = {37.4930, 126.4900};
        double[] farFromCityAndHub = {37.2636, 127.0286};

        registry.around(PlaceCategoryConstants.SearchRole.ACCOMMODATION, gangnam, 6, 10);
        registry.around(PlaceCategoryConstants.SearchRole.ACCOMMODATION, nearIncheonAirport, 6, 10);
        registry.around(PlaceCategoryConstants.SearchRole.ACCOMMODATION, farFromCityAndHub, 6, 10);

        ArgumentCaptor<VectorSearchRequest> requests = ArgumentCaptor.forClass(VectorSearchRequest.class);
        verify(placeVectorSearchService, times(3)).search(requests.capture());
        assertThat(requests.getAllValues()).extracting(VectorSearchRequest::regions)
                .as("도시 안·공항 둘레·잘못 연결된 장소가 만든 먼 중심")
                .containsExactly(List.of("Seoul"), null, List.of("Seoul"));
    }

    private static com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate hub(String name, double lat, double lng) {
        return new com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate(1, 99L, name, null,
                "Travel and Transportation > Transport Hub > Airport", List.of(), "Incheon", "KR",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.0, null, null, null, null, false,
                null, null, null, null, "TRANSIT", null, false, null, null, null, null);
    }
}
