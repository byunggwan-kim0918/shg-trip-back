package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DestinationCatalog;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 여행지 사전(destinations)을 맞춘다. 일정 생성에 쓰는 여행지(destinations.json·국내 도시)는 ACTIVE로 덮고,
 * place-pipeline이 만든 GeoNames 후보 CSV는 없는 것만 CANDIDATE로 넣는다(이미 준비 중이거나 쓰는 여행지를 되돌리지 않는다).
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class DestinationCatalogSync {

    private static final int INSERT_BATCH = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final Destinations destinations;

    /** place-pipeline {@code destinations} 명령의 출력 CSV. 비우면 후보를 넣지 않는다. */
    @Value("${batch.destinations.candidates-file:}")
    private String candidatesFile;

    public void run() {
        int curated = syncCurated();
        int candidates = candidatesFile.isBlank() ? 0 : loadCandidates(Path.of(candidatesFile));
        log.info("[DestinationCatalogSync] 쓰는 여행지 {}곳 맞춤, 후보 {}곳 추가", curated, candidates);
    }

    int syncCurated() {
        List<Object[]> rows = Stream.concat(destinations.all().stream(), DomesticCities.all().stream())
                .map(d -> new Object[]{curatedKey(d.country(), d.name()), d.country(), d.kind() == null ? "city" : d.kind(),
                        d.name(), d.nameKo(), keysArray(DestinationCatalog.aliasKeys(d.name(), d.nameKo(), d.aliases())),
                        d.lat(), d.lng(), d.radiusKm() > 0 ? d.radiusKm() : null,
                        d.parent() == null ? null : curatedKey(d.country(), d.parent()), d.name()})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO destinations (destination_key, country, kind, name, name_ko, alias_keys, latitude, longitude,
                                          radius_km, parent_key, region_key, status, source)
                VALUES (?, ?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?, ?, ?, 'ACTIVE', 'curated')
                ON CONFLICT (destination_key) DO UPDATE SET
                    kind = EXCLUDED.kind, name = EXCLUDED.name, name_ko = EXCLUDED.name_ko,
                    alias_keys = EXCLUDED.alias_keys, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
                    radius_km = EXCLUDED.radius_km, parent_key = EXCLUDED.parent_key, region_key = EXCLUDED.region_key,
                    status = 'ACTIVE', updated_at = now()
                """, rows);
        return rows.size();
    }

    /** @return 새로 넣은 후보 수 */
    int loadCandidates(Path csv) {
        int inserted = 0;
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return 0;
            Map<String, Integer> col = indexOf(header);
            List<Object[]> batch = new ArrayList<>(INSERT_BATCH);
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parsed = CsvLines.parse(line);
                if (parsed == null || parsed.length < col.size()) continue;
                List<String> f = Arrays.asList(parsed);
                List<String> aliases = f.get(col.get("aliases")).isEmpty() ? List.of()
                        : Arrays.asList(f.get(col.get("aliases")).split("\\|"));
                batch.add(new Object[]{f.get(col.get("destination_key")), f.get(col.get("country")),
                        f.get(col.get("kind")), f.get(col.get("name")), blankToNull(f.get(col.get("name_ko"))),
                        keysArray(DestinationCatalog.aliasKeys(f.get(col.get("name")), f.get(col.get("name_ko")), aliases)),
                        Double.parseDouble(f.get(col.get("latitude"))), Double.parseDouble(f.get(col.get("longitude"))),
                        parseInt(f.get(col.get("population"))), f.get(col.get("source")), f.get(col.get("source_id"))});
                if (batch.size() >= INSERT_BATCH) {
                    inserted += insertCandidates(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) inserted += insertCandidates(batch);
        } catch (IOException e) {
            throw new BatchStageFailed("여행지 후보 파일을 읽지 못했다: " + e.getMessage());
        }
        return inserted;
    }

    private int insertCandidates(List<Object[]> batch) {
        int[] counts = jdbcTemplate.batchUpdate("""
                INSERT INTO destinations (destination_key, country, kind, name, name_ko, alias_keys, latitude, longitude,
                                          population, status, source, source_id)
                VALUES (?, ?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?, 'CANDIDATE', ?, ?)
                ON CONFLICT (destination_key) DO NOTHING
                """, batch);
        return IntStream.of(counts).filter(c -> c > 0).sum();
    }

    static String curatedKey(String country, String name) {
        return country + ":curated:" + name;
    }

    /** PostgreSQL 배열 리터럴. 키는 문장부호를 지운 값이라 따옴표·쉼표가 없지만 형식상 감싼다. */
    private static String keysArray(List<String> keys) {
        return keys.stream().map(k -> "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static Map<String, Integer> indexOf(String header) {
        List<String> names = Arrays.asList(CsvLines.parse(header));
        return IntStream.range(0, names.size()).boxed().collect(Collectors.toMap(names::get, i -> i));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
