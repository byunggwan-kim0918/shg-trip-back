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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoursquareSeederTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private FoursquareCsvSource csvSource;

    @Mock
    private PlaceSeedingHistoryRepository historyRepository;

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
            String header = "fsq_place_id,name,latitude,longitude,country,region,category,address,tags";
            Map<String, Integer> index = seeder.parseHeader(header);

            assertThat(index).containsEntry("fsq_place_id", 0);
            assertThat(index).containsEntry("name", 1);
            assertThat(index).containsEntry("latitude", 2);
            assertThat(index).containsEntry("longitude", 3);
            assertThat(index).containsEntry("country", 4);
            assertThat(index).containsEntry("region", 5);
            assertThat(index).containsEntry("category", 6);
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
                    "fsq_place_id", 0, "name", 1, "latitude", 2, "longitude", 3,
                    "country", 4, "region", 5, "category", 6
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
                    "fsq_place_id", 0, "name", 1, "latitude", 2, "longitude", 3,
                    "country", 4, "region", 5, "category", 6,
                    "address", 7, "tags", 8, "description", 9
            );
        }

        @Test
        @DisplayName("유효한 행을 FoursquareRecord로 매핑한다")
        void mapToRecord_validRow() {
            String[] fields = {"fsq_abc123", "센소지", "35.7148", "139.7967", "Japan", "Tokyo", "Temple",
                    "2 Chome-3-1 Asakusa", "관광;사찰", "유명 사찰"};

            FoursquareSeeder.FoursquareRecord record = seeder.mapToRecord(fields, columnIndex);

            assertThat(record).isNotNull();
            assertThat(record.fsqPlaceId()).isEqualTo("fsq_abc123");
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
            String[] fields = {"fsq_abc123", "", "35.7148", "139.7967", "Japan", "Tokyo", "Temple",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }

        @Test
        @DisplayName("fsq_place_id 누락 시 null을 반환한다")
        void mapToRecord_missingFsqPlaceId() {
            String[] fields = {"", "센소지", "35.7148", "139.7967", "Japan", "Tokyo", "Temple",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }

        @Test
        @DisplayName("위도가 범위를 벗어나면 null을 반환한다")
        void mapToRecord_invalidLatitude() {
            String[] fields = {"fsq_abc123", "Test", "91.0", "139.0", "Japan", "Tokyo", "Cafe",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }

        @Test
        @DisplayName("경도가 범위를 벗어나면 null을 반환한다")
        void mapToRecord_invalidLongitude() {
            String[] fields = {"fsq_abc123", "Test", "35.0", "181.0", "Japan", "Tokyo", "Cafe",
                    "address", "tags", "desc"};

            assertThat(seeder.mapToRecord(fields, columnIndex)).isNull();
        }

        @Test
        @DisplayName("좌표가 숫자가 아니면 null을 반환한다")
        void mapToRecord_nonNumericCoordinates() {
            String[] fields = {"fsq_abc123", "Test", "abc", "139.0", "Japan", "Tokyo", "Cafe",
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
        @DisplayName("신규 장소를 upsert 한다")
        void processChunk_insertsNewPlace() {
            Map<String, Integer> columnIndex = Map.of(
                    "fsq_place_id", 0, "name", 1, "latitude", 2, "longitude", 3,
                    "country", 4, "region", 5, "category", 6,
                    "address", 7, "tags", 8, "description", 9
            );
            String[] fields = {"fsq_senso", "센소지", "35.7148", "139.7967", "Japan", "Tokyo", "Temple",
                    "2-3-1 Asakusa", "관광", "유명 사찰"};

            int[] result = seeder.processChunk(List.<String[]>of(fields), columnIndex);

            assertThat(result[0]).isEqualTo(1); // processed

            verify(placeRepository).upsertFoursquarePlace(
                    eq("fsq_senso"),
                    eq("센소지"),
                    eq("2-3-1 Asakusa"),
                    argThat(lat -> lat.compareTo(new BigDecimal("35.7148")) == 0),
                    argThat(lng -> lng.compareTo(new BigDecimal("139.7967")) == 0),
                    eq("Japan"),
                    eq("Tokyo"),
                    eq("Temple"),
                    anyString(),
                    eq("유명 사찰")
            );
        }

        @Test
        @DisplayName("청크 내 같은 fsq_place_id 중복은 한 번만 upsert 한다")
        void processChunk_dedupWithinChunk() {
            Map<String, Integer> columnIndex = Map.of(
                    "fsq_place_id", 0, "name", 1, "latitude", 2, "longitude", 3,
                    "country", 4, "region", 5, "category", 6,
                    "address", 7, "tags", 8, "description", 9
            );
            // 같은 fsq_place_id 2건 (재수집 등으로 중복 가능)
            String[] row1 = {"fsq_same", "7-Eleven", "35.0", "139.0", "Japan", "Tokyo", "Store", "", "편의점", ""};
            String[] row2 = {"fsq_same", "7-Eleven", "35.1", "139.1", "Japan", "Tokyo", "Store", "", "편의점", ""};

            int[] result = seeder.processChunk(List.of(row1, row2), columnIndex);

            assertThat(result[0]).isEqualTo(1); // 중복 제거되어 1건만 처리
            verify(placeRepository, times(1)).upsertFoursquarePlace(
                    eq("fsq_same"), eq("7-Eleven"), anyString(), any(), any(),
                    eq("Japan"), eq("Tokyo"), eq("Store"), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("seed() 통합 동작")
    class SeedIntegration {

        @BeforeEach
        void setUp() {
            when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("CSV 소스가 빈 스트림을 반환하면 건너뛴다")
        void seed_emptyStream() throws IOException {
            when(csvSource.open()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
            seeder.seed();
            verifyNoInteractions(placeRepository);
        }        @Test
        @DisplayName("CSV 파일을 읽어 장소를 생성한다")
        void seed_processesFile() throws IOException {
            String csv = """
                    fsq_place_id,name,latitude,longitude,country,region,category,address,tags,description
                    fsq_senso,센소지,35.7148,139.7967,Japan,Tokyo,Temple,2-3-1 Asakusa,관광;사찰,유명 사찰
                    fsq_eiffel,에펠탑,48.8584,2.2945,France,Paris,Landmark,Champ de Mars,관광;랜드마크,프랑스 랜드마크
                    """;
            when(csvSource.open()).thenReturn(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
            seeder.seed();
            verify(placeRepository, atLeastOnce()).upsertFoursquarePlace(
                    anyString(), anyString(), anyString(), any(), any(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("빈 CSV 헤더만 있으면 처리하지 않는다")
        void seed_headerOnly() throws IOException {
            String csv = "fsq_place_id,name,latitude,longitude,country,region,category,address,tags,description\n";
            when(csvSource.open()).thenReturn(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
            seeder.seed();
            verify(placeRepository, never()).upsertFoursquarePlace(
                    any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("CSV 소스에서 IOException 발생 시 처리를 중단한다")
        void seed_ioException() throws IOException {
            when(csvSource.open()).thenThrow(new IOException("S3 connection failed"));
            seeder.seed();
            verify(placeRepository, never()).upsertFoursquarePlace(
                    any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }
    }
}
