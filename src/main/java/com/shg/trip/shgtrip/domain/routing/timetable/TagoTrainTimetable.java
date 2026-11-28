package com.shg.trip.shgtrip.domain.routing.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.ProviderCallBudget;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한국 TAGO 열차 운행 시간표(국토교통부). 역 id 두 개와 날짜로 그날 운행 열차를 받는다.
 * 좌표 경로가 아니라 도시 간 구간의 공식 시각·요금이라 경로 공급자와 따로 둔다.
 */
@Component
public class TagoTrainTimetable {

    public static final String PROVIDER_ID = "tago-train";
    private static final String PATH = "/1613000/TrainInfo/GetStrtpntAlocFndTrainInfo";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 서울-부산 하루 운행이 약 80편이라 대부분 한 쪽에 하루치를 받는다. */
    private static final int ROWS = 300;
    /** 한 구간 하루치가 이 쪽 수를 넘는 일은 없다. 응답이 이상해도 호출이 끝없이 늘지 않게 막는다. */
    private static final int MAX_PAGES = 4;
    private static final String NO_DATA = "03";

    private final RoutingProperties.TagoSettings settings;
    private final RoutingHttp http;
    private final ObjectMapper mapper;
    private final ProviderCallBudget budget;

    public TagoTrainTimetable(RoutingProperties properties, RoutingHttp http, ObjectMapper mapper,
                              ProviderCallBudget budget) {
        this.settings = properties.tago();
        if (settings != null && settings.enabled()) RoutingHttp.requireSafeBaseUrl(PROVIDER_ID, settings.baseUrl());
        this.http = http;
        this.mapper = mapper;
        this.budget = budget;
    }

    /** 열차 한 편. 요금은 일반실 어른 운임이고 없으면 null이다. */
    public record ScheduledTrain(String trainNo, String grade, String fromStation, String toStation,
                                 OffsetDateTime departure, OffsetDateTime arrival, BigDecimal adultFare) {
        public int minutes() {
            return (int) java.time.Duration.between(departure, arrival).toMinutes();
        }
    }

    public record Result(RouteStatus status, List<ScheduledTrain> trains, String reason) {}

    /**
     * @param depStationId TAGO 역 id(예: NAT010000 서울)
     */
    public Result trains(String depStationId, String arrStationId, LocalDate date) {
        if (settings == null || !settings.enabled()) return new Result(RouteStatus.NOT_COVERED, List.of(), "disabled");
        if (settings.serviceKey() == null || settings.serviceKey().isBlank()) {
            return new Result(RouteStatus.UNAVAILABLE, List.of(), "service key missing");
        }
        List<ScheduledTrain> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Page p = page(depStationId, arrStationId, date, page);
            if (p.result().status() != RouteStatus.FOUND) {
                // 둘째 쪽부터 실패하면 하루치 일부만 남아 공식 시간표에 없는 편으로 잘못 판정된다.
                return page == 1 ? p.result()
                        : new Result(RouteStatus.UNAVAILABLE, List.of(), "partial timetable " + p.result().reason());
            }
            all.addAll(p.result().trains());
            if (all.size() >= p.totalCount()) return new Result(RouteStatus.FOUND, all, null);
        }
        return new Result(RouteStatus.UNAVAILABLE, List.of(), "timetable longer than " + MAX_PAGES + " pages");
    }

    private record Page(Result result, int totalCount) {}

    private Page page(String depStationId, String arrStationId, LocalDate date, int page) {
        if (!budget.tryAcquire(PROVIDER_ID, settings.dailyLimit(), 0, 0, KOREA)) {
            return new Page(new Result(RouteStatus.UNAVAILABLE, List.of(), "call budget exhausted"), 0);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serviceKey", settings.serviceKey());
        params.put("_type", "json");
        params.put("numOfRows", String.valueOf(ROWS));
        params.put("pageNo", String.valueOf(page));
        params.put("depPlaceId", depStationId);
        params.put("arrPlaceId", arrStationId);
        params.put("depPlandTime", date.format(DAY));
        try {
            RoutingHttp.Response r = http.get(RoutingHttp.uri(settings.baseUrl(), PATH, params), Map.of(),
                    settings.timeout());
            if (!r.ok()) return new Page(new Result(RouteStatus.UNAVAILABLE, List.of(), "http " + r.status()), 0);
            JsonNode root = mapper.readTree(r.body());
            return new Page(parse(root), totalCount(root));
        } catch (RoutingHttp.CallFailed e) {
            return new Page(new Result(RouteStatus.UNAVAILABLE, List.of(), e.getMessage()), 0);
        } catch (IOException | RuntimeException e) {
            return new Page(new Result(RouteStatus.UNAVAILABLE, List.of(),
                    "unreadable response " + e.getClass().getSimpleName()), 0);
        }
    }

    /** totalCount가 없으면 받은 쪽이 전부라고 본다. */
    static int totalCount(JsonNode root) {
        return root.path("response").path("body").path("totalCount").asInt(0);
    }

    static Result parse(JsonNode root) {
        JsonNode response = root.path("response");
        String code = response.path("header").path("resultCode").asText("");
        if (NO_DATA.equals(code)) return new Result(RouteStatus.NO_CONNECTION, List.of(), "tago " + code);
        if (!"00".equals(code)) return new Result(RouteStatus.UNAVAILABLE, List.of(), "tago " + code);
        // 한 건이면 배열이 아니라 객체로, 없으면 빈 문자열로 온다.
        JsonNode item = response.path("body").path("items").path("item");
        List<JsonNode> rows = new ArrayList<>();
        if (item.isArray()) item.forEach(rows::add);
        else if (item.isObject()) rows.add(item);
        List<ScheduledTrain> trains = rows.stream().map(TagoTrainTimetable::train).toList();
        if (trains.isEmpty()) return new Result(RouteStatus.NO_CONNECTION, List.of(), "tago empty");
        return new Result(RouteStatus.FOUND, trains, null);
    }

    private static ScheduledTrain train(JsonNode n) {
        String fare = n.path("adultcharge").asText("");
        return new ScheduledTrain(n.path("trainno").asText(), n.path("traingradename").asText(),
                n.path("depplacename").asText(), n.path("arrplacename").asText(),
                stamp(n.path("depplandtime").asText()), stamp(n.path("arrplandtime").asText()),
                fare.isBlank() || "0".equals(fare) ? null : new BigDecimal(fare));
    }

    private static OffsetDateTime stamp(String s) {
        return LocalDateTime.parse(s, STAMP).atZone(KOREA).toOffsetDateTime();
    }
}
