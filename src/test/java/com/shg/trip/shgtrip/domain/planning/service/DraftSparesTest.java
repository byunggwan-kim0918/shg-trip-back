package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftSparesTest {

    private static PlaceCandidate park(long id, String name) {
        return new PlaceCandidate((int) id, id, name, "여수 주소", "Landmarks and Outdoors > Park", List.of(),
                "Yeosu", "KR", BigDecimal.valueOf(34.7), BigDecimal.valueOf(127.7), null, null, 0.5, null, null, null,
                null, false, null, null, null, 0, "SIGHT", null, false, null, null, null, null);
    }

    @Test
    @DisplayName("앞선 날에 넣은 곳과 같은 곳(도시 이름만 붙은 다른 행)은 그날 예비 장소에서 뺀다")
    void sparesSkipSamePlaceUnderOtherRow() {
        PlaceFactsReader facts = mock(PlaceFactsReader.class);
        when(facts.find(anyList())).thenReturn(Map.of());
        DraftItineraryPlanner.Registry registry = new DraftItineraryPlanner.Registry(facts, List.of("여수"), null,
                List.of());
        PlaceCandidate dayOne = park(1749198, "엑스포공원");
        List<DayBuilder.Stop> spares = List.of(
                new DayBuilder.Stop(park(1819462, "여수엑스포공원"), "sight", "afternoon", List.of()),
                new DayBuilder.Stop(park(1661014, "돌산공원"), "sight", "afternoon", List.of()));

        assertThat(DraftItineraryPlanner.sparesNotVisited(spares, List.of(dayOne), registry))
                .extracting(s -> s.place().name()).containsExactly("돌산공원");
    }
}
