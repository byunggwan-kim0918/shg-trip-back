package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.s3.FoursquareCsvSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class FoursquareSeeder {

    private final PlaceRepository placeRepository;
    private final FoursquareCsvSource csvSource;
    private final PlaceSeedingHistoryRepository historyRepository;
    private final TransactionOperations transactionOperations;

    @Value("${batch.foursquare.chunk-size:1000}")
    private int chunkSize;

    /** 끄면 이번 추출에서 빠진 행 수만 로그로 남긴다. */
    @Value("${batch.foursquare.deactivate-stale.enabled:false}")
    private boolean deactivateStale;

    /** 지역의 활성 행 중 이 비율보다 많이 빠지면 추출 이상으로 보고 그 지역은 끄지 않는다. */
    @Value("${batch.foursquare.deactivate-stale.max-ratio:0.3}")
    private double maxStaleRatio;

    /**
     * 이번 실행 시작보다 이만큼 앞선 saved_at만 "이번에 안 나온 행"으로 본다.
     * saved_at은 DB 시각이고 실행 시작은 컨테이너 시각이라 둘의 차이를 흡수한다.
     */
    private static final Duration STALE_CLOCK_MARGIN = Duration.ofHours(1);

    /** 인덱스(name, address) 행 크기 한도 안에 들도록 자른다. 중국어·일본어는 한 글자가 3바이트다. */
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_ADDRESS_LENGTH = 500;

    /** 실패 행이 처리 행의 이 비율을 넘으면 단계를 실패로 끝낸다. 몇 건의 나쁜 행은 경고만 남긴다. */
    private static final double MAX_FAILED_ROW_RATIO = 0.01;
    /** 행 단위 재시도에서 연속으로 이만큼 실패하면 데이터가 아니라 DB 문제로 보고 청크를 포기한다. */
    private static final int MAX_CONSECUTIVE_ROW_FAILURES = 10;
    private static final int DEACTIVATE_BATCH_SIZE = 1000;

    record RegionKey(String country, String region) {
        static RegionKey of(FoursquareRecord r) {
            return new RegionKey(r.country(), r.region());
        }
    }

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "fsq_place_id", "name", "latitude", "longitude", "country", "region", "category");

    static final Set<String> PRIORITY_CITIES = Set.of(
            "Seoul", "Busan", "Jeju", "Incheon", "Daegu", "Daejeon",
            "Gwangju", "Suwon", "Gangneung", "Gyeongju", "Jeonju", "Yeosu",
            "Tokyo", "Osaka", "Kyoto", "Fukuoka", "Sapporo",
            "Nagoya", "Yokohama", "Kobe", "Nara", "Hiroshima",
            "Okinawa", "Kanazawa", "Hakone", "Kamakura", "Nikko",
            "Sendai", "Nagasaki", "Kagoshima", "Beppu", "Takayama");

    /**
     * 일정 스텝이 될 수 없는 카테고리 분기 키워드(Foursquare 계층 경로의 소문자 부분 문자열).
     * 관광 가치가 있는 Retail·Spiritual(시장·쇼핑몰·사찰·서점·기념품점)은 막지 않는다.
     */
    static final Set<String> BLOCKED_CATEGORY_KEYWORDS = Set.of(
            "business and professional services", // 은행, 오피스, 자동차 서비스, 미용실, 스파, 사진관, 장례
            "health and medicine",                // 병원, 치과, 의원, 메디컬 센터
            "residential building",               // 아파트, 콘도
            "government building",                 // 관공서, 우체국
            "education",                           // 학교, 대학
            "convenience store",
            "pharmacy",
            // 교회(명동성당 등)·공항·역·마트는 명소이거나 동선 기점이라 막지 않는다.
            "transport hub > bus stop",            // 버스정류장
            "travel and transportation > road",    // 도로·교차로
            "fuel station",                        // 주유소
            "travel and transportation > parking", // 주차장
            "gym and studio",                      // 헬스장·필라테스·요가
            "golf driving range",                  // 골프연습장
            "park > playground",                   // 단지 놀이터
            "eyecare store",                       // 안경점
            "computers and electronics retail",    // 전자제품점
            "housing development",
            "polling place",
            "assisted living");

    static boolean isNonTravelCategory(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        return BLOCKED_CATEGORY_KEYWORDS.stream().anyMatch(lower::contains);
    }

    public record FoursquareRecord(
            String fsqPlaceId, String name, BigDecimal latitude, BigDecimal longitude,
            String country, String region, String category,
            String address, List<String> tags, String description, String sourceUrl
    ) {}

    public void seed() {
        // 먼저 RUNNING으로 남겨야 컨테이너가 죽어도 시도가 추적된다
        PlaceSeedingHistory history = historyRepository.save(
                PlaceSeedingHistory.builder()
                        .startedAt(OffsetDateTime.now())
                        .sourceFile(csvSource.sourcePath())
                        .build()
        );

        Set<RegionKey> seenRegions = new HashSet<>();
        Set<RegionKey> failedRegions = new HashSet<>();
        try (InputStream is = csvSource.open();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                log.warn("[FoursquareSeeder] 빈 CSV 데이터");
                history.fail("빈 CSV 데이터");
                historyRepository.save(history);
                throw new BatchStageFailed("Foursquare 빈 CSV 데이터");
            }

            Map<String, Integer> columnIndex = parseHeader(headerLine);
            if (!validateRequiredColumns(columnIndex)) {
                log.error("[FoursquareSeeder] 필수 컬럼 누락");
                history.fail("필수 컬럼 누락");
                historyRepository.save(history);
                throw new BatchStageFailed("Foursquare 필수 컬럼 누락");
            }

            List<String[]> chunk = new ArrayList<>(chunkSize);
            String line;
            int totalProcessed = 0, failedChunks = 0, failedRows = 0;

            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields == null) continue;
                chunk.add(fields);

                if (chunk.size() >= chunkSize) {
                    try {
                        int[] result = processChunk(chunk, columnIndex, seenRegions, failedRegions);
                        totalProcessed += result[0];
                        failedRows += result[1];
                    } catch (Exception e) {
                        failedChunks++;
                        failedRows += chunk.size();
                        log.error("[FoursquareSeeder] 청크 처리 실패 (건너뜀, {}건): {}",
                                chunk.size(), e.getMessage());
                    }
                    chunk.clear();
                }
            }

            if (!chunk.isEmpty()) {
                try {
                    int[] result = processChunk(chunk, columnIndex, seenRegions, failedRegions);
                    totalProcessed += result[0];
                    failedRows += result[1];
                } catch (Exception e) {
                    failedChunks++;
                    failedRows += chunk.size();
                    log.error("[FoursquareSeeder] 마지막 청크 처리 실패 (건너뜀, {}건): {}",
                            chunk.size(), e.getMessage());
                }
            }

            boolean tooManyFailedRows = failedRows > (totalProcessed + failedRows) * MAX_FAILED_ROW_RATIO;
            int deactivated = 0;
            if (failedChunks > 0 || tooManyFailedRows) {
                log.warn("[FoursquareSeeder] 적재 실패(청크 {}개, 행 {}건)가 있어 빠진 장소 비활성화를 건너뜀",
                        failedChunks, failedRows);
            } else {
                // 실패 행이 있는 지역은 그 행의 saved_at이 옛 값이라 멀쩡한 장소를 끌 수 있다.
                seenRegions.removeAll(failedRegions);
                try {
                    deactivated = deactivateStalePlaces(seenRegions, history.getStartedAt().minus(STALE_CLOCK_MARGIN));
                } catch (RuntimeException e) {
                    history.fail("빠진 장소 비활성화 실패: " + e.getMessage());
                    historyRepository.save(history);
                    throw new BatchStageFailed("Foursquare 빠진 장소 비활성화 실패: " + e.getMessage());
                }
            }

            // upsert 반환값은 insert/update를 구분하지 않아 DB에서 직접 센다
            int inserted = placeRepository.countInsertedSince(history.getStartedAt());
            int updated = placeRepository.countUpdatedSince(history.getStartedAt());
            history.complete(totalProcessed, inserted, updated, deactivated, failedRows, failedChunks);
            historyRepository.save(history);

            log.info("[FoursquareSeeder] 완료: processed={}, deactivated={}, failedChunks={}, failedRows={}",
                    totalProcessed, deactivated, failedChunks, failedRows);
            if (failedChunks > 0) {
                throw new BatchStageFailed("Foursquare 청크 " + failedChunks + "개(" + failedRows + "건) 실패");
            }
            if (tooManyFailedRows) {
                throw new BatchStageFailed("Foursquare 실패 행 " + failedRows + "건(처리 " + totalProcessed + "건)");
            }

        } catch (IOException e) {
            log.error("[FoursquareSeeder] CSV 읽기 실패: {}", e.getMessage());
            history.fail(e.getMessage());
            historyRepository.save(history);
            throw new BatchStageFailed("Foursquare CSV 읽기 실패: " + e.getMessage());
        }
    }

    /** 테스트용. 지역 기록 없이 청크를 처리한다. */
    public int[] processChunk(List<String[]> rows, Map<String, Integer> columnIndex) {
        return processChunk(rows, columnIndex, new HashSet<>(), new HashSet<>());
    }

    /**
     * 청크를 한 트랜잭션으로 upsert한다(행마다 커밋하면 왕복·fsync가 행 수만큼 든다).
     * 실패하면 행마다 따로 다시 넣어 문제 행만 실패로 센다. 연결·시간 초과는 행마다 다시 해도
     * 같은 시간만큼 기다리므로 그대로 던져 청크 실패로 센다.
     *
     * @param seenRegions   적재된 행의 (국가, 지역)을 더한다
     * @param failedRegions 실패한 행의 (국가, 지역)을 더한다
     * @return {처리 행 수, 실패 행 수}
     */
    int[] processChunk(List<String[]> rows, Map<String, Integer> columnIndex,
                       Set<RegionKey> seenRegions, Set<RegionKey> failedRegions) {
        if (rows == null || rows.isEmpty()) return new int[]{0, 0};

        // CSV 내부 중복은 fsq_place_id 기준으로 먼저 나온 행을 남긴다
        Map<String, FoursquareRecord> dedupMap = new LinkedHashMap<>();
        rows.stream()
                .map(row -> mapToRecord(row, columnIndex))
                .filter(Objects::nonNull)
                .forEach(r -> dedupMap.putIfAbsent(r.fsqPlaceId(), r));
        if (dedupMap.isEmpty()) return new int[]{0, 0};
        Collection<FoursquareRecord> records = dedupMap.values();

        try {
            transactionOperations.executeWithoutResult(status -> records.forEach(this::upsert));
            records.forEach(r -> seenRegions.add(RegionKey.of(r)));
            return new int[]{records.size(), 0};
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | CannotCreateTransactionException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[FoursquareSeeder] 청크 일괄 처리 실패, 행 단위로 다시 시도: {}", e.getMessage());
        }

        int processed = 0, failed = 0, consecutive = 0;
        for (FoursquareRecord record : records) {
            try {
                transactionOperations.executeWithoutResult(status -> upsert(record));
                seenRegions.add(RegionKey.of(record));
                processed++;
                consecutive = 0;
            } catch (RuntimeException e) {
                failed++;
                failedRegions.add(RegionKey.of(record));
                if (++consecutive >= MAX_CONSECUTIVE_ROW_FAILURES) {
                    throw new IllegalStateException("행 단위 재시도 연속 " + consecutive + "건 실패", e);
                }
                log.warn("[FoursquareSeeder] 행 실패 {}: {}", record.fsqPlaceId(), e.getMessage());
            }
        }
        return new int[]{processed, failed};
    }

    private void upsert(FoursquareRecord record) {
        placeRepository.upsertFoursquarePlace(
                record.fsqPlaceId(),
                record.name(),
                record.address() != null ? record.address() : "",
                record.latitude(),
                record.longitude(),
                record.country(),
                record.region(),
                record.category(),
                toPostgresArray(record.tags()),
                record.description() != null ? record.description() : "",
                record.sourceUrl()
        );
    }

    /**
     * 이번 적재에 나온 (국가, 지역)에서, 이번에 다시 나오지 않은 활성 Foursquare 행을 끈다.
     * 폐업 표시·신고·오래된 행·상한 밖 행이 추출에서 빠져도 upsert만으로는 꺼지지 않기 때문이다.
     * 이번 추출에 없는 국가·지역(단계 적재 중인 나라, 추출 대상이 아닌 옛 지역)은 건드리지 않는다.
     *
     * @return 끈 행 수(dry-run이면 0)
     */
    int deactivateStalePlaces(Set<RegionKey> regions, OffsetDateTime seenSince) {
        int deactivated = 0, skipped = 0;
        List<RegionKey> sorted = regions.stream()
                .sorted(Comparator.comparing(RegionKey::country).thenComparing(RegionKey::region))
                .toList();
        for (RegionKey key : sorted) {
            int stale = placeRepository.countStaleFoursquare(key.country(), key.region(), seenSince);
            if (stale == 0) continue;
            int existing = placeRepository.countExistingFoursquare(key.country(), key.region(), seenSince);
            double ratio = (double) stale / existing;
            if (!deactivateStale) {
                log.info("[FoursquareSeeder] 빠진 장소(미적용) {} {}: {}/{}건 ({}%)",
                        key.country(), key.region(), stale, existing, Math.round(ratio * 100));
            } else if (ratio > maxStaleRatio) {
                skipped++;
                log.warn("[FoursquareSeeder] 빠진 장소 비율 초과로 건너뜀 {} {}: {}/{}건",
                        key.country(), key.region(), stale, existing);
            } else {
                int n;
                do {
                    n = placeRepository.deactivateStaleFoursquare(
                            key.country(), key.region(), seenSince, DEACTIVATE_BATCH_SIZE);
                    deactivated += n;
                } while (n == DEACTIVATE_BATCH_SIZE);
            }
        }
        if (deactivateStale) {
            log.info("[FoursquareSeeder] 빠진 장소 비활성화 {}건, 비율 초과로 건너뛴 지역 {}곳", deactivated, skipped);
        }
        return deactivated;
    }

    /**
     * PostgreSQL 배열 리터럴 형식. 예: {"tag1","tag2"}
     */
    private String toPostgresArray(List<String> values) {
        if (values == null || values.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            String escaped = values.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("\"").append(escaped).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    public FoursquareRecord mapToRecord(String[] fields, Map<String, Integer> columnIndex) {
        if (fields == null) return null;

        String fsqPlaceId = getField(fields, columnIndex, "fsq_place_id");
        String name = getField(fields, columnIndex, "name");
        String latStr = getField(fields, columnIndex, "latitude");
        String lngStr = getField(fields, columnIndex, "longitude");
        String country = getField(fields, columnIndex, "country");
        String region = getField(fields, columnIndex, "region");
        String category = getField(fields, columnIndex, "category");

        if (isBlank(fsqPlaceId) || isBlank(name) || isBlank(latStr) || isBlank(lngStr)
                || isBlank(country) || isBlank(region) || isBlank(category)) {
            return null;
        }

        if (isNonTravelCategory(category)) {
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

        // CSV description에는 설명 대신 웹사이트 URL이 흔히 들어 있어 임베딩 노이즈가 되므로 source_url로 뗀다.
        // "https:/www…"·"www…"도 URL로 보고, 링크로 쓰이도록 https://로 맞춘다.
        String sourceUrl = null;
        if (description != null) {
            String trimmed = description.trim();
            if (trimmed.matches("(?i)^https?://\\S+$")) {
                sourceUrl = trimmed;
            } else if (trimmed.matches("(?i)^https?:/[^/]\\S*$")) {
                sourceUrl = trimmed.replaceFirst("(?i)^(https?):/", "$1://");
            } else if (trimmed.matches("(?i)^www\\.\\S+$")) {
                sourceUrl = "https://" + trimmed;
            }
            if (sourceUrl != null) description = null;
        }

        return new FoursquareRecord(fsqPlaceId.trim(), truncate(name.trim(), MAX_NAME_LENGTH), latitude, longitude,
                country.trim(), truncate(region.trim(), 255), truncate(category.trim(), 255),
                address != null ? truncate(address.trim(), MAX_ADDRESS_LENGTH) : null, tags, description, sourceUrl);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * category는 별도 컬럼이라 태그로 복사하지 않고, '>'가 있는 tagsRaw는 경로 나열이라 통째로 버린다.
     * 경로명 안의 쉼표("Cafe, Coffee, and Tea House")를 구분자로 읽으면 파편 태그가 생긴다.
     */
    public List<String> parseTags(String tagsRaw, String category) {
        List<String> result = new ArrayList<>();
        if (tagsRaw == null || tagsRaw.isBlank()) return result;
        if (tagsRaw.contains(">")) return result;

        String delimiter = tagsRaw.contains(";") ? ";" : ",";
        for (String part : tagsRaw.split(delimiter)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(category) && !result.contains(trimmed)) {
                result.add(trimmed);
            }
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
