package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.s3.FoursquareCsvSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoursquareSeederTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private FoursquareCsvSource csvSource;

    @InjectMocks
    private FoursquareSeeder seeder;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(seeder, "chunkSize", 1000);
    }

    @Nested
    @DisplayName("CSV 파싱")
    class CsvParsing {

        @Test
        @DisplayName("CSV 헤더를 올바르게 파싱한다")
        void parseHeader_success() {
            String header = "name,latitude,longitude,country,region,category,address,tags";
            Map<String, Integer> index = seeder.parseHeader(header);

            assertThat(index).containsEntry("name", 0);
            assertThat(index).containsEntry("latitude", 1);
            assertThat(index).containsEntry("longitude", 2);
            assertThat(index).containsEntry("country", 3);
            assertThat(index).containsEntry("region", 4);
            assertThat(index).containsEntry("category", 5);
        }

        @Test
        @DisplayName("따옴표 내부 쉼표를 올바르게 처리한다")
        void parseCsvLine_quotedComma() {
            String line = "\"Cafe, Bakery\",35.6762,139.6503,Japan,Tokyo,Restaurant";
            String[] fields = seeder.parseCsvLine(line);

            assertThat(fields[0]).isEqualTo("Cafe, Bakery");
            assertThat(fields[1]).isEqualTo("35.6762");
        }

        @Test
        @DisplayName("빈 라인은 null을 반환한다")
        void parseCsvLine_emptyLine() {
            assertThat(seeder.parseCsvLine("")).isNull();
            assertThat(seeder.parseCsvLine("   ")).isNull();
            assertThat(seeder.parseCsvLine(null)).isNull();
        }

        @Test
        @DisplayName("이스케이프된 따옴표를 올바르게 처리한다")
        void parseCsvLine_escapedQuotes() {
            String line = "\"He said \"\"hello\"\"\",35.6762,139.6503,Japan,Tokyo,Cafe";
            String[] fields = seeder.parseCsvLine(line);

            assertThat(fields[0]).isEqualTo("He said \"hello\"");
        }
    }

    @Nested
    @DisplayName("필수 컬럼 검증")
    class ColumnValidation {

        @Test
        @DisplayName("모든 필수 컬럼이 있으면 true를 반환한다")
        void validateRequiredColumns_allPresent() {
            Map<String, Integer> index = Map.of(
                    "name", 0, "latitude", 1, "longitude", 2,
                    "country", 3, "region", 4, "category", 5
            );
            assertThat(seeder.validateRequiredColumns(index)).isTrue();
        }

        @Test
        @DisplayName("필수 컬럼이 누락되면 false를 반환한다")
        void validateRequiredColumns_missing() {
            Map<String, Integer> index = Map.of(
                    "name", 0, "latitude", 1, "longitude", 2
            );
            assertThat(seeder.validateRequiredColumns(index)).isFalse();
        }
    }

    @Nested
    @DisplayName("레코드 매핑")
    class RecordMapping {

        private Map<String, Integer> columnIndex;

        @BeforeEach
        void setUp() {
            columnIndex = Map.of(
                    "name", 0, "latitude", 1, "longitude", 2,
                    "country", 3, "region", 4, "category", 5,
                    "address", 6, "tags", 7, "description", 8
            );
        }

        @Test
        @DisplayName("유효한 행을 FoursquareRecord로 매핑한다")
        void mapToRecord_validRow() {
            String[] fields = {"센소지", "35.7148", "139.7967", "Japan", "Tokyo", "Temple",
                    "2 Chome-3-1 Asakusa", "관광;사찰", "유명 사찰"};

            FoursquareSeeder.FoursquareRecord record = seeder.mapToRecord(fields, columnIndex);

            assertThat(record).isNotNull();
            assertThat(record.name()).isEqualTo("센소지");
            assertThat(record.latitude()).isEqualByComparingTo("35.7148");
            assertThat(record.longitude()).isEqualByComparingTo("139.7967");
            assertThat(record.country()).isEqualTo("Japan");
            assertThat(record.region()).isEqualTo("Tokyo");
            assertThat(record.category()).isEqualTo("Temple");
            assertThat(record.tags()).containsExactly("Temple", "관광", "사찰");
        }

        @Test
        @DisplayName("필수 필드 누락 시 null을 반환한다")
        void mapToRecord_missingRequired() {
            String[] fields = {"", "35.7148", "139.7967", "Japan", "Tokyo", "Temple",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }

        @Test
        @DisplayName("위도가 범위를 벗어나면 null을 반환한다")
        void mapToRecord_invalidLatitude() {
            String[] fields = {"Test", "91.0", "139.0", "Japan", "Tokyo", "Cafe",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }

        @Test
        @DisplayName("경도가 범위를 벗어나면 null을 반환한다")
        void mapToRecord_invalidLongitude() {
            String[] fields = {"Test", "35.0", "181.0", "Japan", "Tokyo", "Cafe",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }

        @Test
        @DisplayName("좌표가 숫자가 아니면 null을 반환한다")
        void mapToRecord_nonNumericCoordinates() {
            String[] fields = {"Test", "abc", "139.0", "Japan", "Tokyo", "Cafe",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }
    }

    @Nested
    @DisplayName("태그 파싱")
    class TagParsing {

        @Test
        @DisplayName("세미콜론으로 구분된 태그를 파싱한다")
        void parseTags_semicolonDelimited() {
            List<String> tags = seeder.parseTags("관광;맛집;쇼핑", "Cafe");
            assertThat(tags).containsExactly("Cafe", "관광", "맛집", "쇼핑");
        }

        @Test
        @DisplayName("쉼표로 구분된 태그를 파싱한다")
        void parseTags_commaDelimited() {
            List<String> tags = seeder.parseTags("sightseeing,food", "Restaurant");
            assertThat(tags).containsExactly("Restaurant", "sightseeing", "food");
        }

        @Test
        @DisplayName("태그가 null이면 카테고리만 포함한다")
        void parseTags_null() {
            List<String> tags = seeder.parseTags(null, "Cafe");
            assertThat(tags).containsExactly("Cafe");
        }

        @Test
        @DisplayName("카테고리가 이미 태그에 있으면 중복 추가하지 않는다")
        void parseTags_categoryAlreadyPresent() {
            List<String> tags = seeder.parseTags("Cafe;커피", "Cafe");
            assertThat(tags).containsExactly("Cafe", "커피");
        }
    }

    @Nested
    @DisplayName("우선 도시 판별")
    class PriorityCities {

        @Test
        @DisplayName("인기 도시는 true를 반환한다")
        void isPriorityCity_yes() {
            assertThat(seeder.isPriorityCity("Tokyo")).isTrue();
            assertThat(seeder.isPriorityCity("Seoul")).isTrue();
            assertThat(seeder.isPriorityCity("Daegu")).isTrue();
            assertThat(seeder.isPriorityCity("Sapporo")).isTrue();
        }

        @Test
        @DisplayName("인기 도시가 아니면 false를 반환한다")
        void isPriorityCity_no() {
            assertThat(seeder.isPriorityCity("Paris")).isFalse();
            assertThat(seeder.isPriorityCity("Berlin")).isFalse();
        }

        @Test
        @DisplayName("null이면 false를 반환한다")
        void isPriorityCity_null() {
            assertThat(seeder.isPriorityCity(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Upsert 로직")
    class UpsertLogic {

        @Test
        @DisplayName("신규 장소를 삽입한다")
        void processChunk_insertsNewPlace() {
            Map<String, Integer> columnIndex = Map.of(
                    "name", 0, "latitude", 1, "longitude", 2,
                    "country", 3, "region", 4, "category", 5,
                    "address", 6, "tags", 7, "description", 8
            );
            String[] fields = {"센소지", "35.7148", "139.7967", "Japan", "Tokyo", "Temple",
                    "2-3-1 Asakusa", "관광", "유명 사찰"};

            when(placeRepository.findAllByCountryAndRegionAndSourceFoursquare("Japan", "Tokyo"))
                    .thenReturn(Collections.emptyList());

            int[] result = seeder.processChunk(List.<String[]>of(fields), columnIndex);

            assertThat(result[0]).isEqualTo(1); // inserted
            assertThat(result[1]).isEqualTo(0); // updated
            verify(placeRepository).saveAll(argThat(list -> {
                @SuppressWarnings("unchecked")
                List<Place> places = (List<Place>) list;
                Place place = places.get(0);
                return place.getName().equals("센소지")
                        && place.getSource().equals("foursquare")
                        && place.getCountry().equals("Japan");
            }));
        }

        @Test
        @DisplayName("기존 장소가 있으면 메타데이터만 갱신한다 (핵심 필드 보존)")
        void processChunk_updatesMetadataOnly() {
            Map<String, Integer> columnIndex = Map.of(
                    "name", 0, "latitude", 1, "longitude", 2,
                    "country", 3, "region", 4, "category", 5,
                    "address", 6, "tags", 7, "description", 8
            );
            String[] fields = {"센소지", "35.7148", "139.7967", "Japan", "Tokyo", "HistoricTemple",
                    "2-3-1 Asakusa", "관광;역사", "Updated description"};

            Place existingPlace = Place.builder()
                    .name("센소지")
                    .address("2-3-1 Asakusa")
                    .latitude(new BigDecimal("35.7148"))
                    .longitude(new BigDecimal("139.7967"))
                    .country("Japan")
                    .region("Tokyo")
                    .category("Temple")
                    .source("foursquare")
                    .savedAt(OffsetDateTime.now().minusDays(30))
                    .active(true)
                    .build();

            when(placeRepository.findAllByCountryAndRegionAndSourceFoursquare("Japan", "Tokyo"))
                    .thenReturn(List.of(existingPlace));

            int[] result = seeder.processChunk(List.<String[]>of(fields), columnIndex);

            assertThat(result[0]).isEqualTo(0); // inserted
            assertThat(result[1]).isEqualTo(1); // updated

            // 핵심 필드 보존 확인
            assertThat(existingPlace.getName()).isEqualTo("센소지");
            assertThat(existingPlace.getAddress()).isEqualTo("2-3-1 Asakusa");
            assertThat(existingPlace.getLatitude()).isEqualByComparingTo("35.7148");
            assertThat(existingPlace.getLongitude()).isEqualByComparingTo("139.7967");

            // 메타데이터 갱신 확인
            assertThat(existingPlace.getCategory()).isEqualTo("HistoricTemple");
            assertThat(existingPlace.getSource()).isEqualTo("foursquare");
        }
    }

    @Nested
    @DisplayName("seed() 통합 동작")
    class SeedIntegration {

        @Test
        @DisplayName("CSV 소스가 빈 스트림을 반환하면 건너뛴다")
        void seed_emptyStream() throws IOException {
            when(csvSource.open()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
            seeder.seed();
            verifyNoInteractions(placeRepository);
        }

        @Test
        @DisplayName("CSV 파일을 읽어 장소를 생성한다")
        void seed_processesFile() throws IOException {
            String csv = """
                    name,latitude,longitude,country,region,category,address,tags,description
                    센소지,35.7148,139.7967,Japan,Tokyo,Temple,2-3-1 Asakusa,관광;사찰,유명 사찰
                    에펠탑,48.8584,2.2945,France,Paris,Landmark,Champ de Mars,관광;랜드마크,프랑스 랜드마크
                    """;
            when(csvSource.open()).thenReturn(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
            when(placeRepository.findAllByCountryAndRegionAndSourceFoursquare(anyString(), anyString()))
                    .thenReturn(Collections.emptyList());
            seeder.seed();
            verify(placeRepository, atLeastOnce()).saveAll(anyList());
        }

        @Test
        @DisplayName("빈 CSV 헤더만 있으면 처리하지 않는다")
        void seed_headerOnly() throws IOException {
            String csv = "name,latitude,longitude,country,region,category,address,tags,description\n";
            when(csvSource.open()).thenReturn(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
            seeder.seed();
            verifyNoInteractions(placeRepository);
        }

        @Test
        @DisplayName("CSV 소스에서 IOException 발생 시 처리를 중단한다")
        void seed_ioException() throws IOException {
            when(csvSource.open()).thenThrow(new IOException("S3 connection failed"));
            seeder.seed();
            verifyNoInteractions(placeRepository);
        }
    }
}
