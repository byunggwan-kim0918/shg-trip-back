package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.s3.FoursquareCsvSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Foursquare CSV 데이터를 Place 테이블에 시딩하는 배치 컴포넌트.
 * 데이터 소스는 {@link FoursquareCsvSource}를 통해 추상화 (local/s3 전환 가능).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoursquareSeeder {

    private final PlaceRepository placeRepository;
    private final FoursquareCsvSource csvSource;

    @Value("${batch.foursquare.chunk-size:1000}")
    private int chunkSize;

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "name", "latitude", "longitude", "country", "region", "category");

    static final Set<String> PRIORITY_CITIES = Set.of(
            "Seoul", "Busan", "Jeju", "Incheon", "Daegu", "Daejeon",
            "Gwangju", "Suwon", "Gangneung", "Gyeongju", "Jeonju", "Yeosu",
            "Tokyo", "Osaka", "Kyoto", "Fukuoka", "Sapporo",
            "Nagoya", "Yokohama", "Kobe", "Nara", "Hiroshima",
            "Okinawa", "Kanazawa", "Hakone", "Kamakura", "Nikko",
            "Sendai", "Nagasaki", "Kagoshima", "Beppu", "Takayama");

    public record FoursquareRecord(
            String name, BigDecimal latitude, BigDecimal longitude,
            String country, String region, String category,
            String address, List<String> tags, String description
    ) {}

    public void seed() {
        try (InputStream is = csvSource.open();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                log.warn("[FoursquareSeeder] 빈 CSV 데이터");
                return;
            }

            Map<String, Integer> columnIndex = parseHeader(headerLine);
            if (!validateRequiredColumns(columnIndex)) {
                log.error("[FoursquareSeeder] 필수 컬럼 누락");
                return;
            }

            List<String[]> chunk = new ArrayList<>(chunkSize);
            String line;
            int totalInserted = 0, totalUpdated = 0;

            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields == null) continue;
                chunk.add(fields);

                if (chunk.size() >= chunkSize) {
                    int[] result = processChunk(chunk, columnIndex);
                    totalInserted += result[0];
                    totalUpdated += result[1];
                    chunk.clear();
                }
            }

            if (!chunk.isEmpty()) {
                int[] result = processChunk(chunk, columnIndex);
                totalInserted += result[0];
                totalUpdated += result[1];
            }

            log.info("[FoursquareSeeder] 완료: inserted={}, updated={}", totalInserted, totalUpdated);
        } catch (IOException e) {
            log.error("[FoursquareSeeder] CSV 읽기 실패: {}", e.getMessage());
        }
    }

    @Transactional
    public int[] processChunk(List<String[]> rows, Map<String, Integer> columnIndex) {
        if (rows == null || rows.isEmpty()) return new int[]{0, 0};

        List<FoursquareRecord> records = rows.stream()
                .map(row -> mapToRecord(row, columnIndex))
                .filter(Objects::nonNull)
                .toList();

        if (records.isEmpty()) return new int[]{0, 0};

        Map<String, List<FoursquareRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(r -> r.country() + "|" + r.region()));

        int inserted = 0, updated = 0;
        List<Place> toSave = new ArrayList<>();

        for (Map.Entry<String, List<FoursquareRecord>> entry : grouped.entrySet()) {
            List<FoursquareRecord> groupRecords = entry.getValue();
            String country = groupRecords.get(0).country();
            String region = groupRecords.get(0).region();

            List<Place> existing = placeRepository.findAllByCountryAndRegionAndSourceFoursquare(country, region);
            Map<String, Place> existingByName = existing.stream()
                    .collect(Collectors.toMap(Place::getName, p -> p, (a, b) -> a));

            for (FoursquareRecord record : groupRecords) {
                Place existingPlace = existingByName.get(record.name());
                if (existingPlace != null) {
                    existingPlace.updateFoursquareMetadata(record.category(), record.tags(), record.description());
                    toSave.add(existingPlace);
                    updated++;
                } else {
                    Place newPlace = Place.builder()
                            .name(record.name())
                            .address(record.address() != null ? record.address() : "")
                            .latitude(record.latitude())
                            .longitude(record.longitude())
                            .country(record.country())
                            .region(record.region())
                            .category(record.category())
                            .tags(record.tags())
                            .description(record.description())
                            .source("foursquare")
                            .active(true)
                            .savedAt(OffsetDateTime.now())
                            .build();
                    toSave.add(newPlace);
                    inserted++;
                }
            }
        }

        if (!toSave.isEmpty()) placeRepository.saveAll(toSave);
        return new int[]{inserted, updated};
    }

    public FoursquareRecord mapToRecord(String[] fields, Map<String, Integer> columnIndex) {
        if (fields == null) return null;

        String name = getField(fields, columnIndex, "name");
        String latStr = getField(fields, columnIndex, "latitude");
        String lngStr = getField(fields, columnIndex, "longitude");
        String country = getField(fields, columnIndex, "country");
        String region = getField(fields, columnIndex, "region");
        String category = getField(fields, columnIndex, "category");

        if (isBlank(name) || isBlank(latStr) || isBlank(lngStr)
                || isBlank(country) || isBlank(region) || isBlank(category)) {
            return null;
        }

        BigDecimal latitude, longitude;
        try {
            latitude = new BigDecimal(latStr.trim());
            longitude = new BigDecimal(lngStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }

        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) return null;
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) return null;

        String address = getField(fields, columnIndex, "address");
        String tagsRaw = getField(fields, columnIndex, "tags");
        String description = getField(fields, columnIndex, "description");
        List<String> tags = parseTags(tagsRaw, category);

        return new FoursquareRecord(name.trim(), latitude, longitude,
                country.trim(), truncate(region.trim(), 255), truncate(category.trim(), 255),
                address != null ? address.trim() : null, tags, description);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    public List<String> parseTags(String tagsRaw, String category) {
        List<String> result = new ArrayList<>();
        if (category != null && !category.isBlank()) result.add(category.trim());

        if (tagsRaw == null || tagsRaw.isBlank()) return result;

        String delimiter = tagsRaw.contains(";") ? ";" : ",";
        for (String part : tagsRaw.split(delimiter)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) result.add(trimmed);
        }
        return result;
    }

    public boolean isPriorityCity(String region) {
        if (region == null) return false;
        return PRIORITY_CITIES.contains(region);
    }

    public Map<String, Integer> parseHeader(String headerLine) {
        String[] columns = headerLine.split(",");
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < columns.length; i++) {
            index.put(columns[i].trim().toLowerCase(), i);
        }
        return index;
    }

    public String[] parseCsvLine(String line) {
        if (line == null || line.isBlank()) return null;

        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                    else inQuotes = false;
                } else current.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { fields.add(current.toString()); current.setLength(0); }
                else current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    public boolean validateRequiredColumns(Map<String, Integer> columnIndex) {
        return columnIndex.keySet().containsAll(REQUIRED_COLUMNS);
    }

    private String getField(String[] fields, Map<String, Integer> columnIndex, String column) {
        Integer idx = columnIndex.get(column);
        if (idx == null || idx >= fields.length) return null;
        return fields[idx];
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
