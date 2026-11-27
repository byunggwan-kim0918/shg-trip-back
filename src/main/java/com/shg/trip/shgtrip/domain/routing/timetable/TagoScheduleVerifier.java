package com.shg.trip.shgtrip.domain.routing.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.ProviderCallBudget;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingProperties;
import com.shg.trip.shgtrip.domain.routing.ScheduleVerifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 한국 TAGO 공식 시간표로 열차(코레일)·고속버스 구간을 대조한다. SRT는 TAGO에 없어 다루지 않는다.
 * 역·터미널은 이름으로 TAGO id를 찾는다. 이름이 TAGO 목록과 다르면 확인하지 못한 것으로 둔다.
 */
@Component
public class TagoScheduleVerifier implements ScheduleVerifier {

    static final String ID = "tago";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** 코레일이 TAGO에 내는 열차 등급. SRT는 SR 운행이라 TAGO 열차 정보에 없다. */
    private static final Pattern KORAIL_GRADE = Pattern.compile("KTX|ITX|새마을|무궁화|누리로|통근");
    /** KTDB 고속·시외버스 노선 이름은 "출발터미널[도시]-도착터미널[도시]_번호"다. */
    private static final Pattern INTERCITY_BUS_LINE = Pattern.compile("\\]-.*\\[");
    private static final Pattern CITY_TAG = Pattern.compile("\\[[^\\]]*\\]");
    /** 열차 도착 시각은 시간표 반올림 차이로 1분 어긋날 수 있다. */
    private static final long ARRIVAL_TOLERANCE_MINUTES = 2;
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    /** 역·터미널 목록을 다 받지 못했을 때 다시 받기까지의 시간. TAGO 장애 중 구간마다 목록을 다시 부르지 않게 한다. */
    private static final Duration LIST_RETRY = Duration.ofMinutes(10);
    /** 시간표 캐시가 이만큼 차면 만료된 항목을 지운다. */
    private static final int TIMETABLE_CACHE_SWEEP = 5_000;
    private static final int LIST_ROWS = 1000;

    private record Cached<T>(T value, Instant expiresAt) {}

    private record Names(Map<String, String> ids, boolean complete) {}

    private final RoutingProperties.TagoSettings settings;
    private final TagoTrainTimetable trains;
    private final RoutingHttp http;
    private final ObjectMapper mapper;
    private final ProviderCallBudget budget;
    private final Clock clock;
    private final Map<String, Cached<Map<String, String>>> names = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<String[]>>> timetables = new ConcurrentHashMap<>();
    private final Map<String, Cached<TagoTrainTimetable.Result>> trainTimetables = new ConcurrentHashMap<>();

    public TagoScheduleVerifier(RoutingProperties properties, TagoTrainTimetable trains, RoutingHttp http,
                                ObjectMapper mapper, ProviderCallBudget budget, Clock clock) {
        this.settings = properties.tago();
        this.trains = trains;
        this.http = http;
        this.mapper = mapper;
        this.budget = budget;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Verdict verify(RouteLeg leg) {
        if (settings == null || !settings.enabled() || leg.departure() == null || leg.arrival() == null) {
            return Verdict.UNKNOWN;
        }
        String line = leg.line() == null ? "" : leg.line();
        if (leg.mode() == LegMode.RAIL && KORAIL_GRADE.matcher(line).find()) return verifyTrain(leg);
        if (leg.mode() == LegMode.BUS && INTERCITY_BUS_LINE.matcher(line).find()) return verifyExpressBus(leg);
        return Verdict.UNKNOWN;
    }

    private Verdict verifyTrain(RouteLeg leg) {
        Optional<String> from = id("train", normalize(leg.fromName()));
        Optional<String> to = id("train", normalize(leg.toName()));
        if (from.isEmpty() || to.isEmpty()) return Verdict.UNKNOWN;
        LocalDate day = leg.departure().atZoneSameInstant(KOREA).toLocalDate();
        TagoTrainTimetable.Result r = trains(from.get(), to.get(), day);
        // 두 역 사이 운행이 아예 없으면 다른 운영사 노선이거나 역 이름이 어긋난 것이라 확인하지 못한 것으로 둔다.
        if (r.status() != RouteStatus.FOUND) return Verdict.UNKNOWN;
        boolean match = r.trains().stream().anyMatch(t -> sameMinute(t.departure(), leg.departure())
                && Math.abs(Duration.between(t.arrival(), leg.arrival()).toMinutes()) <= ARRIVAL_TOLERANCE_MINUTES);
        return match ? Verdict.CONFIRMED : Verdict.NOT_IN_OFFICIAL;
    }

    /** 고속버스 도착 시각은 TAGO도 예정값이라 출발 시각만 맞춘다. 고속버스 노선이 아니면(시외버스) 확인하지 못한다. */
    private Verdict verifyExpressBus(RouteLeg leg) {
        Optional<String> from = id("express", normalize(leg.fromName()));
        Optional<String> to = id("express", normalize(leg.toName()));
        if (from.isEmpty() || to.isEmpty()) return Verdict.UNKNOWN;
        String day = leg.departure().atZoneSameInstant(KOREA).format(DAY);
        Optional<List<String[]>> rows = expressRows(from.get(), to.get(), day);
        if (rows.isEmpty() || rows.get().isEmpty()) return Verdict.UNKNOWN;
        String dep = leg.departure().atZoneSameInstant(KOREA).format(MINUTE);
        return rows.get().stream().anyMatch(r -> r[0].equals(dep)) ? Verdict.CONFIRMED : Verdict.NOT_IN_OFFICIAL;
    }

    static String normalize(String stopName) {
        if (stopName == null) return "";
        return CITY_TAG.matcher(stopName).replaceAll("").replaceAll("역$", "").trim();
    }

    private static boolean sameMinute(OffsetDateTime a, OffsetDateTime b) {
        return a.toInstant().getEpochSecond() / 60 == b.toInstant().getEpochSecond() / 60;
    }

    /** 동시에 처음 쓰여도 목록은 한 번만 받는다. */
    private synchronized Optional<String> id(String kind, String name) {
        Cached<Map<String, String>> c = names.get(kind);
        Instant now = clock.instant();
        if (c == null || !c.expiresAt().isAfter(now)) {
            Names loaded = "train".equals(kind) ? loadStations() : loadTerminals();
            c = new Cached<>(loaded.ids(), now.plus(loaded.complete() ? CACHE_TTL : LIST_RETRY));
            names.put(kind, c);
        }
        return Optional.ofNullable(c.value().get(name));
    }

    private Names loadStations() {
        Map<String, String> out = new HashMap<>();
        Optional<JsonNode> cities = call("/1613000/TrainInfo/GetCtyCodeList", Map.of());
        if (cities.isEmpty()) return new Names(out, false);
        boolean complete = true;
        for (JsonNode city : items(cities.get())) {
            Optional<JsonNode> stations = call("/1613000/TrainInfo/GetCtyAcctoTrainSttnList",
                    Map.of("cityCode", city.path("citycode").asText()));
            complete &= stations.isPresent();
            stations.ifPresent(j -> items(j).forEach(s -> out.put(s.path("nodename").asText(), s.path("nodeid").asText())));
        }
        return new Names(out, complete && !out.isEmpty());
    }

    private Names loadTerminals() {
        Map<String, String> out = new HashMap<>();
        for (int page = 1; ; page++) {
            Optional<JsonNode> j = call("/1613000/ExpBusInfo/GetExpBusTrminlList", Map.of("pageNo", String.valueOf(page)));
            if (j.isEmpty()) return new Names(out, false);
            List<JsonNode> rows = items(j.get());
            rows.forEach(t -> out.putIfAbsent(t.path("terminalNm").asText(), t.path("terminalId").asText()));
            if (rows.size() < LIST_ROWS) return new Names(out, !out.isEmpty());
        }
    }

    /** 캐시된 경로에도 검증은 매번 돌므로 시간표를 캐시하지 않으면 TAGO 한도를 구간마다 쓴다. */
    private TagoTrainTimetable.Result trains(String from, String to, LocalDate day) {
        String key = from + ":" + to + ":" + day;
        Cached<TagoTrainTimetable.Result> c = trainTimetables.get(key);
        if (c != null && c.expiresAt().isAfter(clock.instant())) return c.value();
        TagoTrainTimetable.Result r = trains.trains(from, to, day);
        if (r.status() != RouteStatus.UNAVAILABLE) put(trainTimetables, key, r);
        return r;
    }

    private Optional<List<String[]>> expressRows(String from, String to, String day) {
        String key = from + ":" + to + ":" + day;
        Cached<List<String[]>> c = timetables.get(key);
        if (c != null && c.expiresAt().isAfter(clock.instant())) return Optional.of(c.value());
        Optional<JsonNode> j = call("/1613000/ExpBusInfo/GetStrtpntAlocFndExpbusInfo",
                Map.of("depTerminalId", from, "arrTerminalId", to, "depPlandTime", day));
        if (j.isEmpty()) return Optional.empty();
        List<String[]> rows = items(j.get()).stream()
                .map(r -> new String[]{r.path("depPlandTime").asText(), r.path("arrPlandTime").asText()}).toList();
        put(timetables, key, rows);
        return Optional.of(rows);
    }

    private <T> void put(Map<String, Cached<T>> cache, String key, T value) {
        Instant now = clock.instant();
        if (cache.size() >= TIMETABLE_CACHE_SWEEP) cache.values().removeIf(x -> !x.expiresAt().isAfter(now));
        cache.put(key, new Cached<>(value, now.plus(CACHE_TTL)));
    }

    private Optional<JsonNode> call(String path, Map<String, String> extra) {
        if (settings.serviceKey() == null || settings.serviceKey().isBlank()) return Optional.empty();
        if (!budget.tryAcquire(TagoTrainTimetable.PROVIDER_ID, settings.dailyLimit(), 0, 0, KOREA)) return Optional.empty();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serviceKey", settings.serviceKey());
        params.put("_type", "json");
        params.put("numOfRows", String.valueOf(LIST_ROWS));
        params.putAll(extra);
        try {
            RoutingHttp.Response r = http.get(RoutingHttp.uri(settings.baseUrl(), path, params), Map.of(), settings.timeout());
            if (!r.ok()) return Optional.empty();
            JsonNode root = mapper.readTree(r.body());
            return "00".equals(root.path("response").path("header").path("resultCode").asText())
                    ? Optional.of(root) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** TAGO는 한 건이면 객체로, 없으면 빈 문자열로 준다. */
    private static List<JsonNode> items(JsonNode root) {
        JsonNode item = root.path("response").path("body").path("items").path("item");
        if (item.isArray()) {
            List<JsonNode> out = new java.util.ArrayList<>();
            item.forEach(out::add);
            return out;
        }
        return item.isObject() ? List.of(item) : List.of();
    }
}
