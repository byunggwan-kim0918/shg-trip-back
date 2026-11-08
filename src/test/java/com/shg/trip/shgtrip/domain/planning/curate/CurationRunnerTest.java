package com.shg.trip.shgtrip.domain.planning.curate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CurationRunnerTest {

    @Test
    @DisplayName("요청 목록 JSON이 생성 요청 레코드로 그대로 읽힌다")
    void itemsDeserializeIntoGenerateRequest() throws Exception {
        String json = """
                [{"key":"jeju/2nights-3days-food","request":{
                  "mode":"AUTO","destination":"제주","themes":["food","local"],"categories":["restaurant","market"],
                  "pace":"normal","transportPref":"car","startDate":"2026-12-11","endDate":"2026-12-13",
                  "description":"제주 2박3일 맛집 위주","companion":"couple","partySize":2}}]
                """;

        List<CurationRunner.Item> items = new ObjectMapper().findAndRegisterModules()
                .readValue(json, new TypeReference<>() {
                });

        ItineraryGenerateRequest req = items.get(0).request();
        assertThat(items.get(0).key()).isEqualTo("jeju/2nights-3days-food");
        assertThat(req.mode()).isEqualTo(ItineraryGenerateRequest.PlanningMode.AUTO);
        assertThat(req.startDate()).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThat(req.themes()).containsExactly("food", "local");
        assertThat(req.companion()).isEqualTo("couple");
        assertThat(req.partySize()).isEqualTo(2);
        assertThat(req.selectedPlaceIds()).isNull();
    }
}
