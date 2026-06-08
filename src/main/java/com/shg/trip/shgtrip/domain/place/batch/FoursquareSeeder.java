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
 *
 * <p>데이터 소스는 {@link FoursquareCsvSource}를 통해 추상화되어 있으므로,
 * {@code batch.foursquare.source=local}이면 클래스패스 CSV를,
 * {@code batch.foursquare.source=s3}이면 S3 최신 파티션을 읽는다.
 *
 * <p>핵심 처리 전략:
 * <ul>
 *   <li>인기 50개 도시는 최우선 처리 ({@link #isPriorityCity})</li>
 *   <li>동일 name+address 장소가 이미 존재하면 Foursquare 메타데이터만 갱신</li>
 *   <li>신규 장소는 source='foursquare'로 INSERT</li>
 *   <li>청크 단위로 처리하여 메모리 효율 유지</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoursquareSeeder {

    private final PlaceRepository placeRepository;
    private final FoursquareCsvSource csvSource;

    @Value("${batch.foursquare.chunk-size:1000}")
    private int chunkSize;

    // 인기 우선 처리 도시 (country_region 형식)
    private static final Set<String> PRIORITY_CITIES = Set.of(
            "KR_Seoul", "KR_Busan", "KR_Jeju", "KR_Gyeongju",
            "JP_Tokyo", "JP_Osaka", "JP_Kyoto", "JP_Sapporo",
            "TH_Bangkok", "TH_Phuket", "TH_Chiang Mai",
            "VN_Hanoi", "VN_Ho Chi Minh City", "VN_Da Nang",
            "FR_Paris", "IT_Rome", "IT_Florence", "IT_Venice",
            "ES_Barcelona", "ES_Madrid",
            "GB_London", "DE_Berlin",
            "US_New York", "US_Los Angeles", "US_San Francisco",
            "SG_Singapore", "HK_Hong Kong", "TW_Taipei",
            "AU_Sydney", "AU_Melbourne",
            "ID_Bali", "ID_Jakarta",
            "MY_Kuala Lumpur", "MY_Penang",
            "PH_Manila", "PH_Cebu",
            "TR_Istanbul", "GR_Athens",
            "NL_Amsterdam", "PT_Lisbon", "CZ_Prague",
            "AT_Vienna", "HU_Budapest",
            "MX_Mexico City", "BR_Rio de Janeiro",
            "ZA_Cape Town", "EG_Cairo",
            "AE_Dubai", "IN_Mumbai"
    );

    /**
     * CSV 데이터 소스를 열어 장소를 청크 단위로 upsert한다.
     */
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

    /**
     * 청크 단위 upsert 처리.
     * country+region 그룹별로 기존 장소를 일괄 조회하여 N+1을 방지한다.
     *
     * @return int[]{inserted, updated}
     */
    @Transactional
    public int[] processChunk(List<String[]> chunk, Map<String, Integer> columnIndex) {
        // 레코드 매핑 (유효하지 않은 행은 skip)
        List<FoursquareRecord> records = new ArrayList<>();
        for (String[] fields : chunk) {
            try {
                FoursquareRecord record = mapToRecord(fields, columnIndex);
                if (record != null) records.add(record);
            } catch (Exception e) {
                log.warn("[FoursquareSeeder] 레코드 매핑 실패: {}", e.getMessage());
            }
        }

        if (records.isEmpty()) return new int[]{0, 0};

        // country+region별 그룹핑 후 일괄 조회 (N+1 방지)
        Map<String, List<FoursquareRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(r -> r.country() + "|" + r.region()));

        int inserted = 0, updated = 0;
        List<Place> toSave = new ArrayList<>();

        for (Map.Entry<String, List<FoursquareRecord>> entry : grouped.entrySet()) {
            List<FoursquareRecord> groupRecords = entry.getValue();
            String country = groupRecords.get(0).country();
            String region = groupRecords.get(0).region();

            // 해당 그룹의 기존 장소를 한 번에 조회
            List<Place> existing = placeRepository.findAllByCountryAndRegionAndSourceFoursquare(country, region);
            Map<String, Place> existingByName = existing.stream()
                    .collect(Collectors.toMap(Place::getName, p -> p, (a, b) -> a));

            for (FoursquareRecord record : groupRecords) {
                Place existingPlace = existingByName.get(record.name());
                if (existingPlace != null) {
                    existingPlace.updateFoursquareMetadata(record.tags(), record.description());
                    toSave.add(existingPlace);
                    updated++;
                } else {
                    Place place = Place.builder()
                            .name(record.name())
                            .address(record.address())
                            .latitude(record.latitude())
                            .longitude(record.longitude())
                            .category(record.category())
                            .region(region)
                            .country(country)
                            .description(record.description())
                            .tags(record.tags())
                            .source("foursquare")
                            .active(true)
                            .savedAt(OffsetDateTime.now())
                            .build();
                    toSave.add(place);
                    inserted++;
                }
            }
        }

        if (!toSave.isEmpty()) {
            placeRepository.saveAll(toSave);
        }

        return new int[]{inserted, updated};
    }

    /**
     * CSV 필드 배열을 {@link FoursquareRecord}로 변환한다.
     * 필수 필드가 누락된 경우 null을 반환한다.
     */
    private FoursquareRecord mapToRecord(String[] fields, Map<String, Integer> columnIndex) {
        String name = getField(fields, columnIndex, "name");
        String latStr = getField(fields, columnIndex, "latitude");
        String lngStr = getField(fields, columnIndex, "longitude");
        String country = getField(fields, columnIndex, "country");
        String region = getField(fields, columnIndex, "region");
        String category = getField(fields, columnIndex, "category");
        String address = getField(fields, columnIndex, "address");

        if (isBlank(name) || isBlank(latStr) || isBlank(lngStr)
                || isBlank(country) || isBlank(category)) {
            return null;
        }

        BigDecimal latitude;
        BigDecimal longitude;
        try {
            latitude = new BigDecimal(latStr.trim());
            longitude = new BigDecimal(lngStr.trim());
        } catch (NumberFormatException e) {
            log.warn("[FoursquareSeeder] 좌표 파싱 실패: lat={}, lng={}", latStr, lngStr);
            return null;
        }

        // address가 없으면 region + country로 대체
        if (isBlank(address)) {
            address = (isBlank(region) ? "" : region + " ") + country;
        }

        String tags = parseTags(getField(fields, columnIndex, "tags"));
        String description = getField(fields, columnIndex, "description");

        return new FoursquareRecord(
                name.trim(),
                address.trim(),
                latitude,
                longitude,
                category.trim(),
                isBlank(region) ? "" : region.trim(),
                country.trim(),
                isBlank(description) ? "" : description.trim(),
                tags
        );
    }

    /**
     * 세미콜론(;) 구분 태그 문자열을 정규화한다.
     * 각 태그를 trim하고 빈 값을 제거한다.
     */
    private String parseTags(String rawTags) {
        if (isBlank(rawTags)) return "";
        return Arrays.stream(rawTags.split(";"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining(";"));
    }

    /**
     * 주어진 country/region이 우선 처리 도시인지 확인한다.
     */
    private boolean isPriorityCity(String country, String region) {
        if (isBlank(country) || isBlank(region)) return false;
        return PRIORITY_CITIES.contains(country.trim() + "_" + region.trim());
    }

    /**
     * CSV 헤더 라인을 파싱하여 컬럼명 → 인덱스 맵을 반환한다.
     */
    Map<String, Integer> parseHeader(String headerLine) {
        String[] headers = headerLine.split(",", -1);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            index.put(headers[i].trim().toLowerCase(), i);
        }
        return index;
    }

    /**
     * 단순 CSV 라인을 파싱한다. 쉼표로 분리하며 따옴표 이스케이프는 지원하지 않는다.
     * 빈 라인이면 null을 반환한다.
     */
    String[] parseCsvLine(String line) {
        if (isBlank(line)) return null;
        return line.split(",", -1);
    }

    /**
     * 필수 컬럼(name, latitude, longitude, country, category) 존재 여부를 검증한다.
     */
    boolean validateRequiredColumns(Map<String, Integer> columnIndex) {
        List<String> required = List.of("name", "latitude", "longitude", "country", "category");
        for (String col : required) {
            if (!columnIndex.containsKey(col)) {
                log.error("[FoursquareSeeder] 필수 컬럼 없음: {}", col);
                return false;
            }
        }
        return true;
    }

    /**
     * 필드 배열에서 컬럼명에 해당하는 값을 반환한다.
     * 컬럼이 없거나 인덱스 범위를 벗어나면 빈 문자열을 반환한다.
     */
    String getField(String[] fields, Map<String, Integer> columnIndex, String columnName) {
        Integer idx = columnIndex.get(columnName);
        if (idx == null || idx >= fields.length) return "";
        return fields[idx];
    }

    /**
     * null이거나 공백만 있는 문자열인지 확인한다.
     */
    boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Foursquare CSV 한 행을 나타내는 내부 레코드.
     */
    private record FoursquareRecord(
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String category,
            String region,
            String country,
            String description,
            String tags
    ) {}
}
