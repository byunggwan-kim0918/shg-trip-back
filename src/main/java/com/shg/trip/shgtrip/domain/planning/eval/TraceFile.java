package com.shg.trip.shgtrip.domain.planning.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 저장된 일정과 생성 기록을 DB 밖에서 replay하기 위한 JSON 한 건.
 * 운영 기록은 로컬 DB와 일정 번호가 겹쳐서 DB에 넣지 않고 이 형식으로 읽는다.
 */
public record TraceFile(Long itineraryId, String destination, LocalDate startDate, LocalDate endDate,
                        List<StepData> savedSteps, List<PlaceCandidate> candidates,
                        SelectionOutput selection, ScheduleArgs args) {

    public int dayCount() {
        return (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    public static TraceFile parse(ObjectMapper mapper, String json) throws IOException {
        JsonNode n = mapper.readTree(json);
        return new TraceFile(
                n.path("itineraryId").asLong(),
                n.path("destination").asText(null),
                LocalDate.parse(n.path("startDate").asText()),
                LocalDate.parse(n.path("endDate").asText()),
                savedSteps(n.path("savedSteps")),
                mapper.convertValue(n.path("candidates"), new TypeReference<List<PlaceCandidate>>() {}),
                mapper.convertValue(n.path("selection"), SelectionOutput.class),
                mapper.convertValue(n.path("scheduleArgs"), ScheduleArgs.class));
    }

    /** {@link PlaceData#placeId()}는 LLM 응답에서 읽지 않도록 역직렬화가 막혀 있어 직접 만든다. */
    private static List<StepData> savedSteps(JsonNode steps) {
        List<StepData> out = new ArrayList<>();
        for (JsonNode s : steps) {
            JsonNode p = s.path("place");
            PlaceData place = new PlaceData(text(p, "name"), text(p, "address"), text(p, "category"),
                    text(p, "region"), text(p, "country"), null, null,
                    p.hasNonNull("placeId") ? p.get("placeId").asLong() : null, text(p, "nameKo"));
            out.add(new StepData(s.path("stepOrder").asInt(), s.path("dayNumber").asInt(),
                    text(s, "startTime"), text(s, "endTime"), place, List.of(),
                    text(s, "transportationMode"),
                    s.hasNonNull("transportationDuration") ? s.get("transportationDuration").asInt() : null,
                    s.hasNonNull("transportationDistance") ? new BigDecimal(s.get("transportationDistance").asText()) : null,
                    null, null, null));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
