package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTraceReplayTest {

    /** Spring Boot 기본 ObjectMapper 설정과 맞춘다. */
    private final ObjectMapper mapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    private final ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
            List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
            false, List.of("restaurant"), true);

    @Test
    @DisplayName("JSON으로 저장했다 읽은 입력으로 돌려도 스텝이 바이트 단위로 같다")
    void replayFromJsonMatchesOriginal() throws Exception {
        List<PlaceCandidate> candidates = Itinerary115Fixture.candidates();
        SelectionOutput selection = Itinerary115Fixture.selection();

        List<StepData> original = routeOptimizer.schedule(selection, candidates, args).steps();

        List<PlaceCandidate> candidatesBack = mapper.readValue(mapper.writeValueAsString(candidates),
                new TypeReference<>() {});
        SelectionOutput selectionBack = mapper.readValue(mapper.writeValueAsString(selection), SelectionOutput.class);
        ScheduleArgs argsBack = mapper.readValue(mapper.writeValueAsString(args), ScheduleArgs.class);

        List<StepData> replayed = routeOptimizer.schedule(selectionBack, candidatesBack, argsBack).steps();

        assertThat(candidatesBack).isEqualTo(candidates);
        assertThat(mapper.writeValueAsString(replayed)).isEqualTo(mapper.writeValueAsString(original));
    }

    @Test
    @DisplayName("나중에 생긴 필드가 섞인 옛 기록도 읽힌다")
    void readsRecordWithUnknownField() throws Exception {
        PlaceCandidate one = Itinerary115Fixture.candidates().get(0);
        ObjectNode node = mapper.valueToTree(one);
        node.put("fieldAddedLater", "x");

        PlaceCandidate back = mapper.treeToValue(node, PlaceCandidate.class);

        assertThat(back).isEqualTo(one);
    }
}
