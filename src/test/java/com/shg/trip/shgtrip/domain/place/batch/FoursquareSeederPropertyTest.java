package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.Size;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Feature: llm-optimization, Property 1: Upsert 시 핵심 데이터 보존
class FoursquareSeederPropertyTest {

    /**
     * Property 1: Upsert 시 핵심 데이터 보존
     *
     * For any Place가 Place_Pool에 이미 존재할 때, upsert 작업 수행 후
     * 해당 장소의 핵심 필드(name, address, latitude, longitude)는 변경되지 않아야 하며,
     * 데이터 소스 접근 실패 시에도 기존 데이터는 그대로 유지되어야 한다.
     *
     */
    @Property(tries = 100)
    void upsertPreservesCoreFields(
            @ForAll("existingPlaces") Place existingPlace,
            @ForAll("upsertRows") String[] upsertRow
    ) {
        // Arrange: capture original core field values before upsert
        String originalName = existingPlace.getName();
        String originalAddress = existingPlace.getAddress();
        BigDecimal originalLatitude = existingPlace.getLatitude();
        BigDecimal originalLongitude = existingPlace.getLongitude();

        // Set up mock repository to return the existing place
        PlaceRepository mockRepo = Mockito.mock(PlaceRepository.class);
        when(mockRepo.findAllByCountryAndRegionAndSourceFoursquare(anyString(), anyString()))
                .thenReturn(List.of(existingPlace));
        when(mockRepo.saveAll(Mockito.anyList())).thenAnswer(inv -> inv.getArgument(0));

        FoursquareSeeder seeder = new FoursquareSeeder(mockRepo, null);

        // The upsert row uses the same name/country/region as existing place to trigger update path
        String[] row = new String[]{
                existingPlace.getName(),                    // name (matches existing)
                upsertRow[1],                              // latitude (new value from CSV)
                upsertRow[2],                              // longitude (new value from CSV)
                existingPlace.getCountry(),                // country (matches existing)
                existingPlace.getRegion(),                 // region (matches existing)
                upsertRow[5],                              // category (new metadata)
                upsertRow[6],                              // address (new value in CSV)
                upsertRow[7],                              // tags
                upsertRow[8]                               // description
        };

        Map<String, Integer> columnIndex = Map.of(
                "name", 0, "latitude", 1, "longitude", 2,
                "country", 3, "region", 4, "category", 5,
                "address", 6, "tags", 7, "description", 8
        );

        // Act: process chunk (upsert)
        List<String[]> rows = new ArrayList<>();
        rows.add(row);
        seeder.processChunk(rows, columnIndex);

        // Assert: core fields must NOT change after upsert
        assertThat(existingPlace.getName()).isEqualTo(originalName);
        assertThat(existingPlace.getAddress()).isEqualTo(originalAddress);
        assertThat(existingPlace.getLatitude()).isEqualByComparingTo(originalLatitude);
        assertThat(existingPlace.getLongitude()).isEqualByComparingTo(originalLongitude);
    }

    /**
     * Property 1 (data source failure case):
     * When data source access fails (IOException during CSV read), existing data remains unchanged.
     * We simulate this by verifying that processChunk with an empty list does not affect existing places.
     *
     */
    @Property(tries = 100)
    void dataSourceFailurePreservesExistingData(
            @ForAll("existingPlaces") Place existingPlace
    ) {
        // Arrange: capture original state
        String originalName = existingPlace.getName();
        String originalAddress = existingPlace.getAddress();
        BigDecimal originalLatitude = existingPlace.getLatitude();
        BigDecimal originalLongitude = existingPlace.getLongitude();
        String originalCategory = existingPlace.getCategory();

        // Set up mock repository
        PlaceRepository mockRepo = Mockito.mock(PlaceRepository.class);

        FoursquareSeeder seeder = new FoursquareSeeder(mockRepo, null);

        Map<String, Integer> columnIndex = Map.of(
                "name", 0, "latitude", 1, "longitude", 2,
                "country", 3, "region", 4, "category", 5,
                "address", 6, "tags", 7, "description", 8
        );

        // Act: process empty chunk (simulates no data available due to source failure)
        int[] result = seeder.processChunk(new ArrayList<>(), columnIndex);

        // Assert: no changes made, existing data untouched
        assertThat(result[0]).isEqualTo(0); // no inserts
        assertThat(result[1]).isEqualTo(0); // no updates
        assertThat(existingPlace.getName()).isEqualTo(originalName);
        assertThat(existingPlace.getAddress()).isEqualTo(originalAddress);
        assertThat(existingPlace.getLatitude()).isEqualByComparingTo(originalLatitude);
        assertThat(existingPlace.getLongitude()).isEqualByComparingTo(originalLongitude);
        assertThat(existingPlace.getCategory()).isEqualTo(originalCategory);

        // Repository should never be called
        verifyNoInteractions(mockRepo);
    }

    // Feature: llm-optimization, Property 2: Foursquare 매핑 필수 필드 존재
    /**
     * Property 2: Foursquare 매핑 필수 필드 존재
     *
     * For any Foursquare 원본 장소 데이터를 매핑한 결과에는 반드시
     * name(destination), category, region, country, latitude, longitude 필드가
     * null이 아닌 값으로 존재해야 한다.
     *
     */
    @Property(tries = 100)
    void mapToRecordWithValidInputHasAllRequiredFieldsNonNull(
            @ForAll("validCsvRows") String[] validRow
    ) {
        PlaceRepository mockRepo = Mockito.mock(PlaceRepository.class);
        FoursquareSeeder seeder = new FoursquareSeeder(mockRepo, null);

        Map<String, Integer> columnIndex = Map.of(
                "name", 0, "latitude", 1, "longitude", 2,
                "country", 3, "region", 4, "category", 5,
                "address", 6, "tags", 7, "description", 8
        );

        FoursquareSeeder.FoursquareRecord result = seeder.mapToRecord(validRow, columnIndex);

        // When mapToRecord returns non-null, all required fields must be non-null
        assertThat(result).isNotNull();
        assertThat(result.name()).isNotNull().isNotBlank();
        assertThat(result.category()).isNotNull().isNotBlank();
        assertThat(result.region()).isNotNull().isNotBlank();
        assertThat(result.country()).isNotNull().isNotBlank();
        assertThat(result.latitude()).isNotNull();
        assertThat(result.longitude()).isNotNull();
    }

    /**
     * Property 2 (inverse): If any required field is null/blank in input, mapToRecord returns null.
     *
     */
    @Property(tries = 100)
    void mapToRecordWithMissingRequiredFieldReturnsNull(
            @ForAll("rowsWithMissingRequiredField") String[] invalidRow
    ) {
        PlaceRepository mockRepo = Mockito.mock(PlaceRepository.class);
        FoursquareSeeder seeder = new FoursquareSeeder(mockRepo, null);

        Map<String, Integer> columnIndex = Map.of(
                "name", 0, "latitude", 1, "longitude", 2,
                "country", 3, "region", 4, "category", 5,
                "address", 6, "tags", 7, "description", 8
        );

        FoursquareSeeder.FoursquareRecord result = seeder.mapToRecord(invalidRow, columnIndex);

        // When any required field is null/blank, mapToRecord must return null
        assertThat(result).isNull();
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<Place> existingPlaces() {
        Arbitrary<String> names = Arbitraries.strings()
                .alpha().ofMinLength(2).ofMaxLength(30);
        Arbitrary<String> addresses = Arbitraries.strings()
                .alpha().ofMinLength(5).ofMaxLength(50);
        Arbitrary<BigDecimal> latitudes = Arbitraries.bigDecimals()
                .between(new BigDecimal("-90"), new BigDecimal("90"))
                .ofScale(6);
        Arbitrary<BigDecimal> longitudes = Arbitraries.bigDecimals()
                .between(new BigDecimal("-180"), new BigDecimal("180"))
                .ofScale(6);
        Arbitrary<String> categories = Arbitraries.of(
                "Restaurant", "Cafe", "Temple", "Museum", "Park", "Hotel", "Shop");
        Arbitrary<String> regions = Arbitraries.of(
                "Tokyo", "Seoul", "Osaka", "Busan", "Kyoto", "Jeju");
        Arbitrary<String> countries = Arbitraries.of("Japan", "Korea", "France");

        return Combinators.combine(names, addresses, latitudes, longitudes, categories, regions, countries)
                .as((name, address, lat, lng, category, region, country) ->
                        Place.builder()
                                .name(name)
                                .address(address)
                                .latitude(lat)
                                .longitude(lng)
                                .category(category)
                                .region(region)
                                .country(country)
                                .source("foursquare")
                                .savedAt(OffsetDateTime.now().minusDays(10))
                                .active(true)
                                .build()
                );
    }

    @Provide
    Arbitrary<String[]> upsertRows() {
        Arbitrary<String> latStrings = Arbitraries.bigDecimals()
                .between(new BigDecimal("-90"), new BigDecimal("90"))
                .ofScale(4)
                .map(BigDecimal::toPlainString);
        Arbitrary<String> lngStrings = Arbitraries.bigDecimals()
                .between(new BigDecimal("-180"), new BigDecimal("180"))
                .ofScale(4)
                .map(BigDecimal::toPlainString);
        Arbitrary<String> categories = Arbitraries.of(
                "NewCategory", "UpdatedCafe", "HistoricTemple", "ModernMuseum", "NaturePark");
        Arbitrary<String> addresses = Arbitraries.strings()
                .alpha().ofMinLength(5).ofMaxLength(40);
        Arbitrary<String> tags = Arbitraries.of(
                "관광;맛집", "쇼핑;패션", "역사;문화", "자연;힐링", "카페;디저트");
        Arbitrary<String> descriptions = Arbitraries.of(
                "Updated description", "New place info", "Great venue", "Popular spot", "Must visit");

        return Combinators.combine(latStrings, lngStrings, categories, addresses, tags, descriptions)
                .as((lat, lng, category, address, tag, desc) ->
                        new String[]{"placeholder", lat, lng, "placeholder", "placeholder",
                                category, address, tag, desc}
                );
    }

    @Provide
    Arbitrary<String[]> validCsvRows() {
        Arbitrary<String> names = Arbitraries.strings()
                .alpha().ofMinLength(2).ofMaxLength(30);
        Arbitrary<String> latStrings = Arbitraries.bigDecimals()
                .between(new BigDecimal("-90"), new BigDecimal("90"))
                .ofScale(4)
                .map(BigDecimal::toPlainString);
        Arbitrary<String> lngStrings = Arbitraries.bigDecimals()
                .between(new BigDecimal("-180"), new BigDecimal("180"))
                .ofScale(4)
                .map(BigDecimal::toPlainString);
        Arbitrary<String> countries = Arbitraries.of("Japan", "Korea", "France", "USA", "Thailand");
        Arbitrary<String> regions = Arbitraries.of(
                "Tokyo", "Seoul", "Osaka", "Busan", "Kyoto", "Jeju", "Paris", "Bangkok");
        Arbitrary<String> categories = Arbitraries.of(
                "Restaurant", "Cafe", "Temple", "Museum", "Park", "Hotel", "Shop");
        Arbitrary<String> addresses = Arbitraries.strings()
                .alpha().ofMinLength(3).ofMaxLength(40);
        Arbitrary<String> tags = Arbitraries.of(
                "관광;맛집", "쇼핑;패션", "역사;문화", "자연;힐링", "카페;디저트");
        Arbitrary<String> descriptions = Arbitraries.of(
                "A great place", "Popular destination", "Historic site", "Scenic spot", "Local favorite");

        return Combinators.combine(names, latStrings, lngStrings, countries, regions, categories)
                .as((name, lat, lng, country, region, category) -> {
                    String address = "Some Address " + region;
                    String tag = "관광;맛집";
                    String desc = "A great place";
                    return new String[]{name, lat, lng, country, region, category, address, tag, desc};
                });
    }

    @Provide
    Arbitrary<String[]> rowsWithMissingRequiredField() {
        // Generate valid base data, then blank out one required field at random
        Arbitrary<String> names = Arbitraries.strings()
                .alpha().ofMinLength(2).ofMaxLength(30);
        Arbitrary<String> latStrings = Arbitraries.bigDecimals()
                .between(new BigDecimal("-90"), new BigDecimal("90"))
                .ofScale(4)
                .map(BigDecimal::toPlainString);
        Arbitrary<String> lngStrings = Arbitraries.bigDecimals()
                .between(new BigDecimal("-180"), new BigDecimal("180"))
                .ofScale(4)
                .map(BigDecimal::toPlainString);
        Arbitrary<String> countries = Arbitraries.of("Japan", "Korea", "France");
        Arbitrary<String> regions = Arbitraries.of("Tokyo", "Seoul", "Osaka", "Busan");
        Arbitrary<String> categories = Arbitraries.of(
                "Restaurant", "Cafe", "Temple", "Museum", "Park");
        // Index of required field to blank: 0=name, 1=lat, 2=lng, 3=country, 4=region, 5=category
        Arbitrary<Integer> fieldToBlank = Arbitraries.integers().between(0, 5);
        // Blank value: empty string or whitespace
        Arbitrary<String> blankValues = Arbitraries.of("", "  ");

        return Combinators.combine(names, latStrings, lngStrings, countries, regions, categories, fieldToBlank)
                .flatAs((name, lat, lng, country, region, category, blankIdx) ->
                        blankValues.map(blankVal -> {
                            String[] row = new String[]{name, lat, lng, country, region, category,
                                    "Some Address", "관광;맛집", "Description"};
                            row[blankIdx] = blankVal;
                            return row;
                        })
                );
    }
}
