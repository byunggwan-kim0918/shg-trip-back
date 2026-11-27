package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.RouteEngine;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import com.shg.trip.shgtrip.domain.routing.timetable.TagoTrainTimetable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 같은 구간을 지금 추정식과 실제 경로 엔진에 물어 나란히 적는다(--eval.mode=route).
 * 추정식이 package-private이라 생성기 패키지에 둔다.
 */
@Slf4j
@Component
@Profile("eval")
// DraftEvalRunner가 모르는 eval.mode를 거부하므로 그보다 먼저 돌고 끝낸다.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class RouteCompareRunner implements CommandLineRunner {

    @Value("${eval.mode:trip}")
    private String mode;

    @Value("${eval.route-cases:scripts/routing/compare-cases.jsonl}")
    private String casesFile;

    @Value("${eval.out:build/eval-route}")
    private String outDir;

    private final ObjectMapper objectMapper;
    private final RouteEngine engine;
    private final TagoTrainTimetable tago;
    private final ApplicationContext context;

    @Override
    public void run(String... args) throws IOException {
        if (!"route".equals(mode)) return;
        Path out = Path.of(outDir);
        Files.createDirectories(out);
        try (Writer md = Files.newBufferedWriter(out.resolve("route-compare.md"), StandardCharsets.UTF_8)) {
            md.write("| id | 지역 | 추정(지금) | 실제 경로 | 출발→도착 | 환승 | 상태·공급자 | 근거 | 기대 | 구간 |\n"
                    + "|---|---|---|---|---|---|---|---|---|---|\n");
            List<Long> elapsed = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(Path.of(casesFile), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode c = objectMapper.readTree(line);
                long started = System.nanoTime();
                String row = c.has("intercity") ? intercityRow(c) : dayLegRow(c);
                elapsed.add((System.nanoTime() - started) / 1_000_000);
                md.write(row);
            }
            md.write("\n" + latency(elapsed) + "\n");
        }
        log.info("route compare written to {}", out.resolve("route-compare.md"));
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private String dayLegRow(JsonNode c) {
        PlaceCandidate from = candidate(c.path("from"));
        PlaceCandidate to = candidate(c.path("to"));
        TravelMode travelMode = TravelMode.valueOf(c.path("mode").asText("TRANSIT"));
        String pref = switch (travelMode) {
            case WALK -> "walk";
            case CAR, TAXI -> "car";
            default -> "any";
        };
        TransportLeg estimate = DayBuilder.legOf(from, to, pref, false);
        ZonedDateTime departAt = ZonedDateTime.parse(c.path("departAt").asText());
        RouteResult r = engine.route(new RouteQuery(point(c.path("from")), point(c.path("to")), departAt, travelMode));
        String est = estimate == null ? "구간 없음" : estimate.mode() + " " + estimate.durationMin() + "분";
        String actual = "-";
        String times = "-";
        String transfers = "-";
        String basis = "-";
        String expected = expectation(c, null);
        String legs = r.reason() == null ? "-" : r.reason();
        if (r.status() == RouteStatus.FOUND) {
            RouteItinerary best = r.earliestArrival().orElseThrow();
            long untilArrival = Duration.between(departAt.toInstant(), best.arrival().toInstant()).toMinutes();
            actual = best.durationMinutes() + "분 (요청 시각부터 도착 " + untilArrival + "분)";
            times = best.departure().toLocalTime() + "→" + best.arrival().toLocalTime();
            transfers = String.valueOf(best.transfers());
            basis = best.source().basis() + (best.usesTransit() && !best.source().officialData() ? ", 비공식 데이터" : "");
            expected = expectation(c, best);
            legs = describe(best) + (r.reason() == null ? "" : " / " + r.reason())
                    + (best.endpointGapMeters() > 0 ? " (끝점 간격 " + best.endpointGapMeters() + "m는 소요 시간 밖)" : "");
        }
        return row(c, travelMode + " " + est, actual + " | " + times + " | " + transfers,
                r.status() + " " + (r.providerId() == null ? "" : r.providerId()), basis + " | " + expected, legs);
    }

    private String intercityRow(JsonNode c) {
        JsonNode ic = c.path("intercity");
        TripCities.City from = city(ic.path("fromCity"));
        TripCities.City to = city(ic.path("toCity"));
        IntercityLegs.IntercityLeg estimate = IntercityLegs.legBetween(from, to, c.path("pref").asText("any"));
        ZonedDateTime departAt = ZonedDateTime.parse(c.path("departAt").asText());
        TagoTrainTimetable.Result r = tago.trains(ic.path("tagoFrom").asText(), ic.path("tagoTo").asText(),
                departAt.toLocalDate());
        String est = estimate.mode() + " " + estimate.minutes() + "분(역 오가는 시간 포함)"
                + (estimate.costWon() == null ? "" : ", " + estimate.costWon() + "원");
        if (r.status() != RouteStatus.FOUND) {
            return row(c, est, "- | - | -", r.status() + " tago-train", "- | -", r.reason());
        }
        TagoTrainTimetable.ScheduledTrain next = r.trains().stream()
                .filter(t -> !t.departure().toInstant().isBefore(departAt.toInstant()))
                .min(Comparator.comparing(TagoTrainTimetable.ScheduledTrain::departure)).orElse(null);
        TagoTrainTimetable.ScheduledTrain fastest = r.trains().stream()
                .min(Comparator.comparingInt(TagoTrainTimetable.ScheduledTrain::minutes)).orElseThrow();
        String actual = "열차만 " + fastest.minutes() + "분(최단 " + fastest.grade() + ")"
                + (next == null ? "" : ", 다음 열차 " + next.grade() + " " + next.departure().toLocalTime() + "→"
                + next.arrival().toLocalTime() + (next.adultFare() == null ? "" : " " + next.adultFare() + "원"));
        return row(c, est, actual + " | - | 0", "FOUND tago-train", "OFFICIAL_SCHEDULE | -", "그날 " + r.trains().size() + "편");
    }

    /** 캐시를 거친 호출과 공급자 호출이 섞여 있으므로 처음 실행(캐시 없음)의 값을 응답 시간으로 본다. */
    private static String latency(List<Long> ms) {
        if (ms.isEmpty()) return "응답 시간: 없음";
        List<Long> sorted = ms.stream().sorted().toList();
        return "응답 시간(ms, 구간 " + sorted.size() + "건): p50=" + sorted.get((sorted.size() - 1) / 2) + " p95="
                + sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) + " max=" + sorted.getLast();
    }

    /** expect.line·operator·mode 정규식이 모두 한 구간에 맞으면 통과다. 기대가 없으면 "-"다. */
    private static String expectation(JsonNode c, RouteItinerary it) {
        JsonNode e = c.path("expect");
        if (e.isMissingNode()) return "-";
        if (it == null) return "실패(경로 없음)";
        boolean ok = it.legs().stream().anyMatch(l -> matches(e.path("mode"), String.valueOf(l.mode()))
                && matches(e.path("line"), l.line()) && matches(e.path("operator"), l.operator()));
        return (ok ? "통과" : "실패") + "(" + e.path("label").asText("") + ")";
    }

    private static boolean matches(JsonNode pattern, String value) {
        return pattern.isMissingNode() || (value != null && java.util.regex.Pattern.compile(pattern.asText()).matcher(value).find());
    }

    private static String describe(RouteItinerary it) {
        return it.legs().stream().map(RouteCompareRunner::leg).collect(Collectors.joining(" → "))
                + (it.fares().isEmpty() ? "" : " / " + it.fares().stream()
                .map(f -> f.kind() + " " + f.amount().stripTrailingZeros().toPlainString() + " " + f.currency())
                .collect(Collectors.joining(", ")));
    }

    private static String leg(RouteLeg l) {
        String who = l.operator() == null ? "" : l.operator() + " ";
        return l.mode() + (l.line() == null ? "" : "(" + who + l.line() + ")") + " "
                + (l.mode().isTransit() ? l.departure().toLocalTime() + "-" + l.arrival().toLocalTime() + " " : "")
                + Math.round(l.durationSeconds() / 60.0) + "분";
    }

    private static String row(JsonNode c, String est, String actual, String status, String basis, String legs) {
        return "| " + String.join(" | ", c.path("id").asText(), c.path("region").asText(), est, actual, status, basis,
                legs.replace("|", "/")) + " |\n";
    }

    private static GeoPoint point(JsonNode p) {
        return new GeoPoint(p.path("lat").asDouble(), p.path("lng").asDouble());
    }

    private static TripCities.City city(JsonNode p) {
        return new TripCities.City(p.path("name").asText(), p.path("name").asText(), "KR",
                p.path("lat").asDouble(), p.path("lng").asDouble());
    }

    private static PlaceCandidate candidate(JsonNode p) {
        return new PlaceCandidate(0, null, p.path("name").asText(), null, p.path("category").asText(null), List.of(),
                null, p.path("country").asText(), BigDecimal.valueOf(p.path("lat").asDouble()),
                BigDecimal.valueOf(p.path("lng").asDouble()), null, null, 0, null, null, null, null, false, null,
                null, null, null, null, null, false, null, null, null);
    }
}
