package com.shg.trip.shgtrip.domain.planning.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.planning.dto.DraftEdit;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryVerifier;
import com.shg.trip.shgtrip.domain.planning.service.TripPlanner;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.DraftItineraryPlanner;
import com.shg.trip.shgtrip.domain.planning.service.ai.DraftCallGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 여행 초안을 모델로 받아 기록하고, 기록된 초안과 편집으로 일정을 다시 지어 검사한다.
 * 파일마다 {@code {"id", "request", "plan", "edits"}}이고, 결과는 읽기용 markdown과 검사용 JSON 줄로 남긴다.
 */
@Slf4j
@Component
@Profile("eval")
@RequiredArgsConstructor
class DraftEvalRunner {

    /** 국내 도시 이름 → 장소 DB region과 중심 좌표. */
    private static final Map<String, Object[]> CITIES = Map.ofEntries(
            Map.entry("서울", new Object[]{"Seoul", 37.5665, 126.9780}),
            Map.entry("부산", new Object[]{"Busan", 35.1796, 129.0756}),
            Map.entry("제주", new Object[]{"Jeju", 33.4996, 126.5312}),
            Map.entry("경주", new Object[]{"Gyeongju", 35.8562, 129.2247}),
            Map.entry("강릉", new Object[]{"Gangneung", 37.7519, 128.8761}),
            Map.entry("전주", new Object[]{"Jeonju", 35.8242, 127.1480}),
            Map.entry("여수", new Object[]{"Yeosu", 34.7604, 127.6622}),
            Map.entry("대구", new Object[]{"Daegu", 35.8714, 128.6014}),
            Map.entry("대전", new Object[]{"Daejeon", 36.3504, 127.3845}),
            Map.entry("광주", new Object[]{"Gwangju", 35.1595, 126.8526}),
            Map.entry("수원", new Object[]{"Suwon", 37.2636, 127.0286}),
            Map.entry("인천", new Object[]{"Incheon", 37.4563, 126.7052}));

    @org.springframework.beans.factory.annotation.Value("${eval.draft-threads:6}")
    private int threads;

    private final ObjectMapper objectMapper;
    private final ItineraryVerifier verifier;
    private final TripPlanner tripPlanner;

    /**
     * 요청마다 초안을 받고, 하루의 취지가 깨졌으면 한 번 고쳐 id마다 기록한다. 이미 있는 파일은 다시 부르지 않는다.
     */
    void recordTrips(String requestsFile, List<String> ids, String outDir) throws IOException {
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(requestsFile), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode row = objectMapper.readTree(line);
            byId.put(row.get("id").asText(), row);
        }
        List<Path> targets = new ArrayList<>();
        for (String id : ids) {
            JsonNode row = byId.get(id);
            Path file = dir.resolve(id + ".json");
            if (row == null || Files.exists(file) && objectMapper.readTree(file.toFile()).path("finished").asBoolean(true)) {
                continue;
            }
            if ("holdout".equals(row.path("split").asText())) {
                throw new IllegalArgumentException("봉인 세트 요청은 개발 중에 쓰지 않는다: " + id);
            }
            targets.add(file);
        }
        inParallel(targets, file -> {
            String id = file.getFileName().toString().replace(".json", "");
            JsonNode row = byId.get(id);
            JsonNode req = row.get("request");
            DraftItineraryPlanner.Request base = requestOf(req, null);
            String prompt = DraftItineraryPlanner.prompt(base.input(), base.cityCenter(), base.arrival(),
                    base.departure(), mustDo(req));
            long started = System.currentTimeMillis();
            Map<String, Object> saved;
            DraftCallGenerator.Drafted drafted;
            long draftMs;
            if (Files.exists(file)) {
                // 지난 실행이 초안만 남기고 멈춘 기록은 초안을 다시 부르지 않고 짓기와 편집만 한다.
                saved = objectMapper.readValue(file.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
                JsonNode usage = objectMapper.valueToTree(saved.get("usage"));
                drafted = new DraftCallGenerator.Drafted(objectMapper.convertValue(saved.get("plan"), DraftPlan.class),
                        usage.path("in").asLong(), usage.path("out").asLong());
                draftMs = usage.path("ms").asLong();
            } else {
                drafted = tripPlanner.draft(base, prompt);
                draftMs = System.currentTimeMillis() - started;
                saved = new LinkedHashMap<>();
                saved.put("id", id);
                saved.put("request", req);
                saved.put("expect", row.get("expect"));
                saved.put("prompt", prompt);
                saved.put("plan", drafted.plan());
                saved.put("usage", Map.of("in", drafted.inputTokens(), "out", drafted.outputTokens(), "ms", draftMs));
                saved.put("finished", false);
                // 짓는 단계에서 실패해도 비용을 낸 초안은 남긴다.
                write(file, saved);
            }
            long finishStarted = System.currentTimeMillis();
            TripPlanner.Planned planned = tripPlanner.finish(base, prompt, drafted);
            saved.put("problems", planned.problems());
            saved.put("edits", planned.edits());
            saved.put("usage", Map.of("in", planned.inputTokens(), "out", planned.outputTokens(),
                    "draftMs", draftMs, "ms", draftMs + System.currentTimeMillis() - finishStarted));
            saved.put("finished", true);
            write(file, saved);
            log.info("여행 기록 {} 편집 {}건 ({}ms)", id, planned.edits().size(), System.currentTimeMillis() - started);
        });
    }

    private void write(Path file, Map<String, Object> saved) throws IOException {
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(saved),
                StandardCharsets.UTF_8);
    }

    /** 기록된 초안과 편집으로 일정을 다시 짓고 정답 조건으로 검사한다. 근처 검색의 질의 임베딩만 부른다. */
    void runTrips(String recordedDir, String outDir) throws IOException {
        Path out = Path.of(outDir);
        Files.createDirectories(out);
        List<Path> files = draftFiles(recordedDir);
        Map<Path, String[]> built = new java.util.concurrent.ConcurrentHashMap<>();
        inParallel(files, file -> {
            JsonNode root = objectMapper.readTree(file.toFile());
            if (!root.hasNonNull("plan")) return;
            String id = root.get("id").asText();
            JsonNode req = root.get("request");
            try {
                DraftItineraryPlanner.Request base = requestOf(req, null);
                DraftPlan plan = objectMapper.treeToValue(root.get("plan"), DraftPlan.class);
                List<DraftEdit> edits = root.hasNonNull("edits")
                        ? List.of(objectMapper.treeToValue(root.get("edits"), DraftEdit[].class)) : List.of();
                TripPlanner.Built trip = tripPlanner.build(base, plan, edits);
                DraftItineraryPlanner.Result result = trip.result();
                int days = result.frames().size();
                List<ItineraryVerifier.Violation> violations = verifier.verify(
                        withSatisfy(conditionsOf(root.path("expect"), req), trip.skeleton().conditions()),
                        base.input().startDate(), days, result.steps(), result.candidates(), base.cityNames());
                StringBuilder md = new StringBuilder(render(id, req, result));
                if (!root.path("finished").asBoolean(true)) md.append("편집 단계 미완료 기록\n");
                if (!trip.dayNotes().isEmpty() || !trip.tripNotes().isEmpty()) {
                    md.append("여행자에게 남긴 말:\n");
                    trip.dayNotes().forEach((d, lines) -> md.append("- ").append(d).append("일차: ")
                            .append(String.join(" ", lines)).append('\n'));
                    trip.tripNotes().forEach(n -> md.append("- ").append(n).append('\n'));
                }
                md.append('\n');
                built.put(file, new String[]{md.toString(), objectMapper.writeValueAsString(Map.of(
                        "id", id, "request", req, "steps", stepRows(result), "notes", result.notes(),
                        "violations", violations, "problems", root.path("problems"),
                        "edits", edits.size(), "finished", root.path("finished").asBoolean(true)))});
            } catch (RuntimeException e) {
                log.warn("여행 재구성 실패 {}: {}", id, e.toString());
                built.put(file, new String[]{"## 일정 " + id + "\n실패: " + e + "\n\n", null});
            }
        });
        StringBuilder md = new StringBuilder("각 줄: 시작-끝 장소 | 분류 | 앞 장소에서 이동\n\n");
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            String[] r = built.get(file);
            if (r == null) continue;
            md.append(r[0]);
            if (r[1] != null) lines.add(r[1]);
        }
        Files.writeString(out.resolve("draft-itineraries.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("draft-itineraries.jsonl"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
        log.info("여행 재구성 {}건", lines.size());
    }

    /**
     * 정답 조건마다 초안이 같은 요구로 적은 장소 이름과 음식을 붙인다. 음식 요구는 메뉴로 확인하고, 메뉴 정보가 없는
     * 식당만 이름으로 본다.
     */
    private static TripConditions withSatisfy(TripConditions expect, TripConditions designed) {
        List<TripConditions.Want> wants = new ArrayList<>();
        for (TripConditions.Want w : expect.wants()) {
            List<String> satisfy = new ArrayList<>(w.satisfy());
            String food = w.food();
            String k = w.text().replaceAll("\\s", "");
            for (TripConditions.Want d : designed.wants()) {
                String dk = d.text().replaceAll("\\s", "");
                if (!dk.contains(k) && !k.contains(dk)) continue;
                satisfy.addAll(d.satisfy());
                if (food == null && d.food() != null) food = d.food();
            }
            wants.add(new TripConditions.Want(w.text(), w.timing(), satisfy, food));
        }
        return new TripConditions(wants, expect.cityDays(), expect.avoid(), expect.lowMobility(), expect.transport(),
                expect.arrival(), expect.departure(), designed.pace());
    }

    private static List<String> mustDo(JsonNode req) {
        List<String> out = new ArrayList<>();
        req.path("requiredExperiences").forEach(x -> out.add(x.path("text").asText()
                + ("morning".equals(x.path("timing").asText()) ? " (아침)" : " (저녁)")));
        return out;
    }

    @FunctionalInterface
    private interface FileTask {
        void run(Path file) throws IOException;
    }

    /** 평가 요청에 적어 둔 정답 조건. */
    private static TripConditions conditionsOf(JsonNode expect, JsonNode req) {
        List<TripConditions.Want> wants = new ArrayList<>();
        expect.path("wanted").forEach(w -> wants.add(new TripConditions.Want(w.asText(), null, List.of(), null)));
        JsonNode required = expect.path("required");
        if (required.hasNonNull("text")) {
            String text = required.get("text").asText();
            String timing = required.path("timing").asText(null);
            if ("morning".equals(timing) && text.contains("일출")) timing = "sunrise";
            wants.add(new TripConditions.Want(text, timing, List.of(), null));
        }
        Map<String, Integer> cityDays = new LinkedHashMap<>();
        expect.path("cityDays").fields().forEachRemaining(e -> cityDays.put(e.getKey(), e.getValue().asInt()));
        List<PlaceRestriction> avoid = new ArrayList<>();
        req.path("restrictions").forEach(r -> {
            try {
                avoid.add(PlaceRestriction.valueOf(r.asText()));
            } catch (IllegalArgumentException ignored) {
                // 평가 요청의 제약 이름이 열거형에 없으면 검사하지 않는다.
            }
        });
        String mode = expect.path("mode").asText("transit");
        return new TripConditions(wants, cityDays, avoid,
                expect.path("lowWalking").asBoolean(false) || expect.path("elderly").asBoolean(false), mode,
                req.hasNonNull("arrivalTime") ? LocalTime.parse(req.get("arrivalTime").asText()) : null,
                req.hasNonNull("departureTime") ? LocalTime.parse(req.get("departureTime").asText()) : null,
                req.path("pace").asText(null));
    }

    /** 초안 한 건은 대부분 모델 응답과 DB 검색을 기다리는 시간이라 여러 건을 함께 돌린다. */
    private void inParallel(List<Path> files, FileTask task) {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Path file : files) {
                futures.add(pool.submit(() -> {
                    try {
                        task.run(file);
                    } catch (IOException | RuntimeException e) {
                        log.warn("초안 처리 실패 {}: {}", file.getFileName(), e.toString());
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warn("초안 처리 실패: {}", e.getCause().toString());
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private static List<Path> draftFiles(String draftsDir) throws IOException {
        try (Stream<Path> s = Files.list(Path.of(draftsDir))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private DraftItineraryPlanner.Request requestOf(JsonNode req, DraftPlan draft) {
        List<String> cityNames = new ArrayList<>();
        for (String c : req.get("destination").asText().split(",")) cityNames.add(c.trim());
        List<String> regions = cityNames.stream().map(c -> (String) CITIES.get(c)[0]).toList();
        Object[] first = CITIES.get(cityNames.get(0));
        List<PlaceRestriction> restrictions = new ArrayList<>();
        if (req.has("restrictions")) req.get("restrictions").forEach(r -> restrictions.add(PlaceRestriction.valueOf(r.asText())));
        List<String> themes = new ArrayList<>();
        req.path("themes").forEach(t -> themes.add(t.asText()));
        List<String> categories = new ArrayList<>();
        req.path("categories").forEach(t -> categories.add(t.asText()));
        VectorEnrichedInput input = new VectorEnrichedInput(
                req.get("destination").asText(), themes, categories, req.path("pace").asText("normal"),
                req.path("transportPref").asText("any"), BigDecimal.valueOf(req.path("budget").asLong(1_000_000)),
                LocalDate.parse(req.get("startDate").asText()), LocalDate.parse(req.get("endDate").asText()),
                req.path("description").asText(null), List.of(), req.get("destination").asText(), "KR", regions,
                List.of(), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.companionOf(req.path("companion").asText(null)),
                req.has("partySize") ? req.get("partySize").asInt() : null, List.of(),
                restrictions.isEmpty() ? null : restrictions);
        return new DraftItineraryPlanner.Request(input, draft, cityNames,
                new double[]{(double) first[1], (double) first[2]},
                req.has("arrivalTime") ? LocalTime.parse(req.get("arrivalTime").asText()) : null,
                req.has("departureTime") ? LocalTime.parse(req.get("departureTime").asText()) : null,
                null, null, mustDo(req), Map.of(), Set.of());
    }

    /** 이보다 긴 빈 시간은 자유 시간으로 보인다. 빈칸으로 두면 일정이 끊긴 것처럼 읽힌다. */
    private static final int FREE_BLOCK = 90;

    private static String render(String id, JsonNode req, DraftItineraryPlanner.Result result) {
        StringBuilder sb = new StringBuilder("## 일정 ").append(id).append('\n');
        sb.append("요청: ").append(req.get("destination").asText()).append(' ')
                .append(req.get("startDate").asText()).append('~').append(req.get("endDate").asText())
                .append(" / 이동 ").append(req.path("transportPref").asText())
                .append(" / 페이스 ").append(req.path("pace").asText())
                .append(" / 동행 ").append(req.path("companion").asText()).append(' ')
                .append(req.path("partySize").asText()).append("명");
        if (req.has("arrivalTime")) sb.append(" / 도착 ").append(req.get("arrivalTime").asText());
        if (req.has("departureTime")) sb.append(" / 출발 ").append(req.get("departureTime").asText());
        sb.append("\n요청 문장: ").append(req.path("description").asText("")).append('\n');
        if (req.has("requiredExperiences")) sb.append("꼭 할 것: ").append(req.get("requiredExperiences")).append('\n');
        if (req.has("restrictions")) sb.append("피할 것: ").append(req.get("restrictions")).append('\n');
        int day = 0;
        Integer prevEnd = null;
        for (StepData s : result.steps()) {
            if (s.dayNumber() != day) {
                day = s.dayNumber();
                prevEnd = null;
                sb.append("\n[").append(day).append("일차]\n");
            }
            Integer start = minutesOf(s.startTime());
            int leg = s.transportationDuration() == null ? 0 : s.transportationDuration();
            if (prevEnd != null && start != null && start - leg - prevEnd >= FREE_BLOCK) {
                sb.append(hhmm(prevEnd)).append('-').append(hhmm(start - leg)).append(" 자유 시간\n");
            }
            prevEnd = minutesOf(s.endTime());
            sb.append(s.startTime()).append('-').append(s.endTime()).append(' ').append(s.place().name())
                    .append(" | ").append(leaf(s.place().category()));
            if (s.transportationMode() != null) {
                sb.append(" | ").append(s.transportationMode()).append(' ').append(s.transportationDuration())
                        .append("분 ").append(s.transportationDistance()).append("km");
            }
            sb.append('\n');
        }
        sb.append("\n");
        return sb.toString();
    }

    private static List<Map<String, Object>> stepRows(DraftItineraryPlanner.Result result) {
        Map<Long, PlaceCandidate> byId = result.candidates().stream()
                .filter(c -> c.placeId() != null)
                .collect(Collectors.toMap(PlaceCandidate::placeId, Function.identity(), (a, b) -> a));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StepData s : result.steps()) {
            PlaceCandidate c = s.place().placeId() == null ? null : byId.get(s.place().placeId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", s.dayNumber());
            row.put("start", s.startTime());
            row.put("end", s.endTime());
            row.put("name", s.place().name());
            row.put("category", s.place().category());
            row.put("placeId", s.place().placeId());
            row.put("role", c == null ? null : c.placeRole());
            row.put("lat", c == null ? null : c.latitude());
            row.put("lon", c == null ? null : c.longitude());
            row.put("openingHours", c == null ? null : c.openingHours());
            row.put("mode", s.transportationMode());
            row.put("minutes", s.transportationDuration());
            row.put("km", s.transportationDistance());
            rows.add(row);
        }
        return rows;
    }

    private static Integer minutesOf(String hhmm) {
        if (hhmm == null) return null;
        LocalTime t = LocalTime.parse(hhmm);
        return t.getHour() * 60 + t.getMinute();
    }

    private static String hhmm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String leaf(String category) {
        if (category == null) return "";
        int i = category.lastIndexOf('>');
        return i < 0 ? category : category.substring(i + 1).trim();
    }
}
