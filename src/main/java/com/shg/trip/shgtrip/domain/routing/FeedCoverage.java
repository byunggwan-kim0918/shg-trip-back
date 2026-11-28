package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 그래프 빌드가 남긴 피드별 운행 기간·정류장 범위(scripts/routing의 feeds-coverage.json).
 * 그래프 전체 운행 기간 안이어도 한 피드(예: 한 도시 버스)는 먼저 끝날 수 있다. 그날 그 지역은 그 피드 운행이 빠진 채
 * 경로가 나오므로 시간표를 확정하지 않는다. 다른 지역·피드 경로는 건드리지 않는다.
 */
@Slf4j
public final class FeedCoverage {

    /** 정류장 범위 끝에서 이만큼 안은 그 피드 지역으로 본다. 범위 끝 정류장까지 걸어가는 출발지를 포함한다. */
    static final double AREA_BUFFER_DEGREES = 0.01;
    /** 파일을 다시 볼 간격. 그래프를 바꿀 때만 파일이 바뀐다. */
    private static final Duration RECHECK = Duration.ofMinutes(1);
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 피드 하나(판을 이어 실었으면 판 전체). */
    public record Feed(String feed, Set<String> modes, double minLat, double minLng, double maxLat, double maxLng,
                       List<LocalDate[]> periods) {

        boolean near(GeoPoint p) {
            return p.lat() >= minLat - AREA_BUFFER_DEGREES && p.lat() <= maxLat + AREA_BUFFER_DEGREES
                    && p.lng() >= minLng - AREA_BUFFER_DEGREES && p.lng() <= maxLng + AREA_BUFFER_DEGREES;
        }

        boolean serves(LocalDate day) {
            return periods.stream().anyMatch(p -> !day.isBefore(p[0]) && !day.isAfter(p[1]));
        }

        /** 기간이 끝난 뒤면 마지막 운행일, 시작 전이면 첫 운행일. */
        LocalDate edge(LocalDate day) {
            LocalDate last = periods.stream().map(p -> p[1]).filter(d -> d.isBefore(day)).max(LocalDate::compareTo).orElse(null);
            return last != null ? last : periods.stream().map(p -> p[0]).min(LocalDate::compareTo).orElseThrow();
        }
    }

    private final List<Feed> feeds;

    FeedCoverage(List<Feed> feeds) {
        this.feeds = List.copyOf(feeds);
    }

    public static FeedCoverage empty() {
        return new FeedCoverage(List.of());
    }

    static FeedCoverage read(JsonNode root) {
        List<Feed> out = new ArrayList<>();
        for (JsonNode f : root.path("feeds")) {
            JsonNode box = f.path("bbox");
            List<LocalDate[]> periods = new ArrayList<>();
            for (JsonNode p : f.path("periods")) {
                periods.add(new LocalDate[]{LocalDate.parse(p.get(0).asText(), YMD), LocalDate.parse(p.get(1).asText(), YMD)});
            }
            if (box.size() != 4 || periods.isEmpty()) continue;
            Set<String> modes = new TreeSet<>();
            f.path("modes").forEach(m -> modes.add(m.asText()));
            out.add(new Feed(f.path("feed").asText(), modes, box.get(0).asDouble(), box.get(1).asDouble(),
                    box.get(2).asDouble(), box.get(3).asDouble(), periods));
        }
        return new FeedCoverage(out);
    }

    /** 출발지나 도착지 근처 피드 중 그날 운행 기간 밖인 것. */
    public List<Feed> lapsed(GeoPoint origin, GeoPoint destination, LocalDate day) {
        return feeds.stream().filter(f -> f.near(origin) || f.near(destination)).filter(f -> !f.serves(day)).toList();
    }

    /** 사유에 적을 피드 수. 큰 도시는 피드 수십 개가 한꺼번에 끝날 수 있다. */
    private static final int DESCRIBE_FEEDS = 3;

    public static String describe(List<Feed> lapsed, LocalDate day) {
        String named = String.join(", ", lapsed.stream().limit(DESCRIBE_FEEDS).map(f -> f.feed() + " " + f.modes()
                + (f.edge(day).isBefore(day) ? " ended " : " starts ") + f.edge(day)).toList());
        String more = lapsed.size() > DESCRIBE_FEEDS ? " and " + (lapsed.size() - DESCRIBE_FEEDS) + " more" : "";
        return "timetable for " + named + more + " not loaded on " + day + "; route may miss those services";
    }

    /** 파일이 바뀌면 다시 읽는다. 파일이 없거나 깨지면 피드 단위 판정을 끄고 그래프 운행 기간만 본다. */
    public static final class File {

        private final Path path;
        private final ObjectMapper mapper;
        private final Clock clock;
        private volatile FeedCoverage current = empty();
        private volatile Instant checkedAt = Instant.MIN;
        private volatile long modifiedAt = -1;

        public File(Path path, ObjectMapper mapper, Clock clock) {
            this.path = path;
            this.mapper = mapper;
            this.clock = clock;
        }

        public FeedCoverage get() {
            Instant now = clock.instant();
            if (checkedAt.plus(RECHECK).isAfter(now)) return current;
            checkedAt = now;
            try {
                long modified = Files.getLastModifiedTime(path).toMillis();
                if (modified != modifiedAt) {
                    current = read(mapper.readTree(path.toFile()));
                    modifiedAt = modified;
                }
            } catch (IOException | RuntimeException e) {
                if (modifiedAt != -2) log.warn("feed coverage unreadable path={} cause={}", path, e.getClass().getSimpleName());
                current = empty();
                modifiedAt = -2;
            }
            return current;
        }
    }
}
