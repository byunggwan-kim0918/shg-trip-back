package com.shg.trip.shgtrip.domain.planning.dto;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceDataJsonTest {

    @Test
    @DisplayName("LLM 도구 입력의 placeId는 읽지 않고, 직렬화에는 남긴다")
    void placeIdIsNotReadFromModelOutput() throws Exception {
        Map<String, Object> step = Map.of(
                "place", Map.of("name", "가게", "address", "", "placeId", 42),
                "alternatives", List.of(Map.of("name", "대안", "placeId", 43)));

        StepData fromSdk = JsonValue.from(step).convert(StepData.class);
        StepData fromJackson = new ObjectMapper().convertValue(step, StepData.class);

        assertThat(fromSdk.place().placeId()).isNull();
        assertThat(fromSdk.alternatives().get(0).placeId()).isNull();
        assertThat(fromJackson.place().placeId()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(
                new PlaceData("가게", "", null, null, null, null, null, 42L, "가게"))).contains("\"placeId\":42");
    }
}
