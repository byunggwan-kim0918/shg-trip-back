package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchEnrichSchedulerTest {

    @Mock
    private PlaceRepository placeRepository;

    private BatchEnrichScheduler scheduler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scheduler = new BatchEnrichScheduler(placeRepository, objectMapper);
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "test-api-key");
        ReflectionTestUtils.setField(scheduler, "enrichModel", "claude-haiku-4-5-20251001");
    }

    @Test
    @DisplayName("API 키가 없으면 보강을 건너뛴다")
    void enrich_noApiKey_skips() {
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "");

        scheduler.enrich();

        verifyNoInteractions(placeRepository);
    }

    @Test
    @DisplayName("미보강 장소가 없으면 아무 작업도 수행하지 않는다")
    void enrich_noUnenrichedPlaces_doesNothing() {
        when(placeRepository.findEnrichTargets(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.enrich();

        verify(placeRepository).findEnrichTargets(anyCollection(), any(Pageable.class));
        verify(placeRepository, never()).save(any(Place.class));
    }

    @Test
    @DisplayName("buildEnrichPrompt는 장소 정보를 포함한 프롬프트를 생성한다")
    void buildEnrichPrompt_containsPlaceInfo() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String prompt = scheduler.buildEnrichPrompt(place);

        assertThat(prompt).contains("센소지");
        assertThat(prompt).contains("관광");
        assertThat(prompt).contains("아사쿠사");
        assertThat(prompt).contains("일본");
        assertThat(prompt).contains("tags");
        assertThat(prompt).contains("description");
        assertThat(prompt).contains("recommended_time_slots");
    }

    @Test
    @DisplayName("buildEnrichPrompt는 기존 설명이 있으면 포함한다")
    void buildEnrichPrompt_includesExistingDescription() {
        Place place = Place.builder()
                .id(1L)
                .name("메이지신궁")
                .address("도쿄시 시부야구")
                .latitude(BigDecimal.valueOf(35.6764))
                .longitude(BigDecimal.valueOf(139.6993))
                .category("관광")
                .region("하라주쿠")
                .country("일본")
                .description("메이지 천황을 기리는 신사")
                .savedAt(OffsetDateTime.now())
                .active(true)
                .build();

        String prompt = scheduler.buildEnrichPrompt(place);

        assertThat(prompt).contains("메이지 천황을 기리는 신사");
    }

    @Test
    @DisplayName("buildBatchRequests는 장소마다 요청을 생성한다")
    void buildBatchRequests_createsRequestPerPlace() {
        Place place1 = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        Place place2 = createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본");

        List<Map<String, Object>> requests = scheduler.buildBatchRequests(List.of(place1, place2));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).get("custom_id")).isEqualTo("place_1");
        assertThat(requests.get(1).get("custom_id")).isEqualTo("place_2");
    }

    @Test
    @DisplayName("applyEnrichment는 유효한 JSON 응답을 파싱하여 장소를 보강한다")
    void applyEnrichment_validJson_enrichesPlace() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String content = """
                {
                  "tags": ["사찰", "관광명소", "역사", "문화유산", "아사쿠사"],
                  "description": "도쿄에서 가장 오래된 사찰로, 628년에 창건되었습니다. 아사쿠사의 상징적인 관광지입니다.",
                  "recommended_time_slots": ["morning", "afternoon"]
                }
                """;

        boolean result = scheduler.applyEnrichment(place, content);

        assertThat(result).isTrue();
        assertThat(place.getTags()).containsExactly("사찰", "관광명소", "역사", "문화유산", "아사쿠사");
        assertThat(place.getDescription()).contains("도쿄에서 가장 오래된 사찰");
        assertThat(place.getRecommendedTimeSlots()).containsExactly("morning", "afternoon");
        assertThat(place.getEnrichedAt()).isNotNull();
    }

    @Test
    @DisplayName("applyEnrichment는 recommended_duration_minutes를 파싱하고, 없거나 비정수면 null로 둔다")
    void applyEnrichment_parsesDurationMinutes() {
        Place withDuration = createPlace(1L, "사라오름", "관광", "제주", "KR");
        scheduler.applyEnrichment(withDuration, """
                {"tags": ["오름"], "description": "설명", "recommended_time_slots": ["morning"],
                 "recommended_duration_minutes": 180}
                """);
        assertThat(withDuration.getRecommendedDurationMinutes()).isEqualTo(180);

        Place withoutDuration = createPlace(2L, "카페", "카페", "제주", "KR");
        scheduler.applyEnrichment(withoutDuration, """
                {"tags": ["카페"], "description": "설명", "recommended_time_slots": ["afternoon"]}
                """);
        assertThat(withoutDuration.getRecommendedDurationMinutes()).isNull();

        Place invalidDuration = createPlace(3L, "해변", "관광", "제주", "KR");
        scheduler.applyEnrichment(invalidDuration, """
                {"tags": ["해변"], "description": "설명", "recommended_time_slots": ["morning"],
                 "recommended_duration_minutes": "약 두 시간"}
                """);
        assertThat(invalidDuration.getRecommendedDurationMinutes()).isNull();
    }

    @Test
    @DisplayName("applyEnrichment는 코드 블록으로 감싸진 JSON도 처리한다")
    void applyEnrichment_codeBlock_parsesCorrectly() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String content = """
                ```json
                {
                  "tags": ["사찰", "관광명소"],
                  "description": "유명한 사찰입니다.",
                  "recommended_time_slots": ["morning"]
                }
                ```
                """;

        boolean result = scheduler.applyEnrichment(place, content);

        assertThat(result).isTrue();
        assertThat(place.getTags()).containsExactly("사찰", "관광명소");
    }

    @Test
    @DisplayName("applyEnrichment는 잘못된 JSON이면 false를 반환한다")
    void applyEnrichment_invalidJson_returnsFalse() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String content = "이것은 유효하지 않은 응답입니다.";

        boolean result = scheduler.applyEnrichment(place, content);

        assertThat(result).isFalse();
        assertThat(place.getEnrichedAt()).isNull();
    }

    private static final String KOREAN_RESTAURANT =
            "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";

    private Place dining(String name, String category, String description, Integer sitelinks) {
        Place place = Place.builder()
                .id(7L).name(name).address("서울 중구")
                .latitude(BigDecimal.valueOf(37.56)).longitude(BigDecimal.valueOf(126.98))
                .category(category).region("Seoul").country("KR").description(description)
                .savedAt(OffsetDateTime.now()).active(true)
                .build();
        if (sitelinks != null) place.markWikidataMatch("Q1", sitelinks);
        return place;
    }

    @Test
    @DisplayName("식음료 장소는 식당 전용 프롬프트를 쓴다(업종을 알려주고 입장료·체류시간은 묻지 않는다)")
    void buildEnrichPrompt_dining_usesDiningPrompt() {
        String prompt = scheduler.buildEnrichPrompt(dining("한본새", KOREAN_RESTAURANT, null, null));

        assertThat(prompt).contains("업종: 식당");
        assertThat(prompt).doesNotContain("admission_fee", "recommended_duration_minutes", "description");
    }

    @Test
    @DisplayName("무명 식당은 모델이 설명을 보내도 버리고 기존 설명을 지킨다. 업종 태그·기본 체류시간이 들어간다")
    void applyEnrichment_dining_notFamous_keepsExistingDescription() {
        Place place = dining("한본새", KOREAN_RESTAURANT, "TourAPI 개요", null);

        boolean result = scheduler.applyEnrichment(place, """
                {"tags": ["카페", "한식"], "recommended_time_slots": ["afternoon"], "description": "지어낸 설명"}
                """);

        assertThat(result).isTrue();
        assertThat(place.getTags()).containsExactly("식당", "한식");
        assertThat(place.getDescription()).isEqualTo("TourAPI 개요");
        assertThat(place.getRecommendedTimeSlots()).containsExactly("afternoon");
        assertThat(place.getRecommendedDurationMinutes()).isEqualTo(DiningEnrichment.Venue.MEAL.defaultMinutes);
        assertThat(place.getAdmissionFee()).isNull();
        assertThat(place.getEnrichedAt()).isNotNull();
    }

    @Test
    @DisplayName("Wikidata에 잡힌 식당은 설명을 받는다")
    void applyEnrichment_dining_famous_takesDescription() {
        Place place = dining("명동교자", KOREAN_RESTAURANT, null, 5);

        scheduler.applyEnrichment(place, """
                {"tags": ["칼국수", "면요리"], "recommended_time_slots": ["afternoon"],
                 "description": "칼국수와 만두로 알려진 명동의 식당입니다."}
                """);

        assertThat(place.getDescription()).isEqualTo("칼국수와 만두로 알려진 명동의 식당입니다.");
        assertThat(place.getTags()).containsExactly("식당", "칼국수", "면요리");
    }

    @Test
    @DisplayName("총칭 카테고리는 응답의 venue로 업종을 정한다")
    void applyEnrichment_dining_generic_usesModelVenue() {
        Place place = dining("주택 카페", "Dining and Drinking > Restaurant", null, null);

        scheduler.applyEnrichment(place, """
                {"venue": "카페", "tags": ["커피", "한식"], "recommended_time_slots": ["afternoon"]}
                """);

        assertThat(place.getTags()).containsExactly("카페", "커피");
        assertThat(place.getRecommendedDurationMinutes()).isEqualTo(DiningEnrichment.Venue.CAFE.defaultMinutes);
    }

    @Test
    @DisplayName("식당 응답이 JSON 객체가 아니면 실패로 남겨 다음 실행에서 다시 보강한다")
    void applyEnrichment_dining_nonObject_returnsFalse() {
        Place place = dining("한본새", KOREAN_RESTAURANT, null, null);

        assertThat(scheduler.applyEnrichment(place, "[\"한식\"]")).isFalse();
        assertThat(place.getEnrichedAt()).isNull();
    }

    @Test
    @DisplayName("결과 반영: 오류 결과·깨진 행·결과에서 빠진 장소를 모두 실패 목록에 넣는다(다음 청크에서 재제출 방지)")
    void applyResults_everyUnsucceededPlaceIsFailed() {
        Place ok = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        Place errored = createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본");
        Place missing = createPlace(3L, "도쿄타워", "관광", "미나토", "일본");
        String succeeded = """
                {"custom_id":"place_1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{\\"tags\\":[\\"사찰\\"]}"}]}}}""";
        String error = """
                {"custom_id":"place_2","result":{"type":"errored","error":{"type":"overloaded_error"}}}""";
        String foreign = """
                {"custom_id":"place_99","result":{"type":"succeeded","message":{"content":[]}}}""";
        String jsonl = String.join("\n", succeeded, error, "{깨진 행", foreign, "");

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(jsonl, List.of(ok, errored, missing));

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.failedIds()).containsExactly(2L, 3L);
        verify(placeRepository).save(ok);
        verify(placeRepository).resetEmbeddings(List.of(1L));
    }

    @Test
    @DisplayName("결과 반영: 저장이 실패한 장소도 실패 목록에 들어간다")
    void applyResults_saveFailure_isFailed() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        when(placeRepository.save(place)).thenThrow(new RuntimeException("DB down"));
        String line = """
                {"custom_id":"place_1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{\\"tags\\":[\\"사찰\\"]}"}]}}}""";

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(line, List.of(place));

        assertThat(result.success()).isZero();
        assertThat(result.failedIds()).containsExactly(1L);
        verify(placeRepository, never()).resetEmbeddings(anyList());
    }

    @Test
    @DisplayName("extractJson은 순수 JSON을 그대로 반환한다")
    void extractJson_pureJson_returnsAsIs() {
        String json = "{\"tags\": [\"test\"]}";

        String result = scheduler.extractJson(json);

        assertThat(result).isEqualTo(json);
    }

    @Test
    @DisplayName("extractJson은 코드 블록을 제거한다")
    void extractJson_codeBlock_strips() {
        String input = "```json\n{\"tags\": [\"test\"]}\n```";

        String result = scheduler.extractJson(input);

        assertThat(result).isEqualTo("{\"tags\": [\"test\"]}");
    }

    @Test
    @DisplayName("extractJson은 텍스트 전후의 JSON을 추출한다")
    void extractJson_surroundingText_extractsJson() {
        String input = "Here is the result:\n{\"tags\": [\"test\"]}\nDone.";

        String result = scheduler.extractJson(input);

        assertThat(result).isEqualTo("{\"tags\": [\"test\"]}");
    }

    @Test
    @DisplayName("extractTextContent는 메시지에서 텍스트를 추출한다")
    void extractTextContent_validMessage_extractsText() throws Exception {
        String messageJson = """
                {
                  "content": [
                    {"type": "text", "text": "Hello"},
                    {"type": "text", "text": " World"}
                  ]
                }
                """;
        var messageNode = objectMapper.readTree(messageJson);

        String result = scheduler.extractTextContent(messageNode);

        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("extractTextContent는 메시지가 null이면 null을 반환한다")
    void extractTextContent_nullMessage_returnsNull() {
        String result = scheduler.extractTextContent(null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("splitIntoChunks는 1000건 단위로 분할한다")
    void splitIntoChunks_splitsCorrectly() {
        List<Place> places = new java.util.ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            places.add(createPlace((long) i, "Place" + i, "관광", "지역", "국가"));
        }

        List<List<Place>> chunks = scheduler.splitIntoChunks(places);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(1000);
        assertThat(chunks.get(1)).hasSize(1000);
        assertThat(chunks.get(2)).hasSize(500);
    }

    @Test
    @DisplayName("splitIntoChunks는 chunkSize 이하면 하나의 청크만 반환한다")
    void splitIntoChunks_smallList_singleChunk() {
        List<Place> places = List.of(
                createPlace(1L, "센소지", "관광", "아사쿠사", "일본"),
                createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본")
        );

        List<List<Place>> chunks = scheduler.splitIntoChunks(places);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(2);
    }

    @Test
    @DisplayName("processChunk는 배치 제출 실패 시 전체 실패를 반환한다")
    void processChunk_submitFails_returnsAllFailed() {
        // HttpClient를 모킹하지 않으므로 실제 HTTP 호출이 실패할 것
        // (test-api-key가 유효하지 않으므로)
        List<Place> places = List.of(
                createPlace(1L, "센소지", "관광", "아사쿠사", "일본")
        );

        BatchEnrichScheduler.ChunkResult result = scheduler.processChunk(places);

        assertThat(result.success()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("일부 장소 보강이 실패해도 남은 미보강 장소를 같은 실행에서 계속 조회한다")
    void enrich_partialFailureMidRun_doesNotTerminateEarly() {
        // 1번 청크는 실패, 2번 청크는 성공. 실패한 1번은 다음 조회에서 제외돼야 한다.
        Place place1 = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        Place place2 = createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본");
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(new BatchEnrichScheduler.ChunkResult(0, 1, List.of(1L)))
                .when(spied).processChunk(List.of(place1));
        doReturn(new BatchEnrichScheduler.ChunkResult(1, 0, List.of()))
                .when(spied).processChunk(List.of(place2));
        when(placeRepository.findEnrichTargets(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(place1), List.of(place2), List.of());

        spied.enrich();

        verify(placeRepository, times(3)).findEnrichTargets(anyCollection(), any(Pageable.class));
        verify(placeRepository, atLeastOnce()).findEnrichTargets(
                argThat((java.util.Collection<Long> c) -> c.contains(1L)), any(Pageable.class));
    }

    @Test
    @DisplayName("실패분이 한 페이지를 넘어도 멈추지 않는다 — 실패한 장소는 쿼리에서 제외된다")
    void enrich_failuresExceedingOnePage_doNotStopTheRun() {
        // 예전 구현은 첫 페이지를 다시 읽고 메모리에서 실패분을 걸렀다. 실패분이 첫 페이지를
        // 다 채우면 남은 장소가 있어도 끝났다. 지금은 제외 목록을 쿼리로 넘긴다.
        Place failed1 = createPlace(1L, "실패1", "관광", "제주", "한국");
        Place failed2 = createPlace(2L, "실패2", "관광", "제주", "한국");
        Place ok = createPlace(3L, "성공", "관광", "제주", "한국");
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(new BatchEnrichScheduler.ChunkResult(0, 2, List.of(1L, 2L)))
                .when(spied).processChunk(List.of(failed1, failed2));
        doReturn(new BatchEnrichScheduler.ChunkResult(1, 0, List.of()))
                .when(spied).processChunk(List.of(ok));
        // 실패분(1, 2)이 제외 목록으로 넘어와야만 남은 장소(3)를 준다. 성공한 장소는 실제 DB에선
        // enriched_at이 찍혀 다음 조회에서 빠지므로 세 번째 조회는 빈 결과다.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(placeRepository.findEnrichTargets(anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    java.util.Collection<Long> excluded = inv.getArgument(0);
                    return switch (calls.incrementAndGet()) {
                        case 1 -> List.of(failed1, failed2);
                        case 2 -> excluded.containsAll(List.of(1L, 2L)) ? List.of(ok) : List.of(failed1, failed2);
                        default -> List.of();
                    };
                });

        spied.enrich();

        verify(spied).processChunk(List.of(ok));
    }

    @Test
    @DisplayName("연속으로 청크가 전부 실패하면 멈춘다 — API 키 오류로 수만 건을 헛돌지 않는다")
    void enrich_stopsAfterConsecutiveFullyFailedChunks() {
        BatchEnrichScheduler spied = spy(scheduler);
        doAnswer(inv -> {
            List<Place> chunk = inv.getArgument(0);
            return new BatchEnrichScheduler.ChunkResult(0, chunk.size(),
                    chunk.stream().map(Place::getId).toList());
        }).when(spied).processChunk(anyList());
        java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong();
        when(placeRepository.findEnrichTargets(anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> List.of(createPlace(nextId.incrementAndGet(), "장소", "관광", "제주", "한국")));

        spied.enrich();

        verify(spied, times(BatchEnrichScheduler.MAX_CONSECUTIVE_FAILED_CHUNKS)).processChunk(anyList());
    }

    private Place createPlace(Long id, String name, String category, String region, String country) {
        return Place.builder()
                .id(id)
                .name(name)
                .address("도쿄시 타이토구")
                .latitude(BigDecimal.valueOf(35.7148))
                .longitude(BigDecimal.valueOf(139.7967))
                .category(category)
                .region(region)
                .country(country)
                .savedAt(OffsetDateTime.now())
                .active(true)
                .build();
    }
}
