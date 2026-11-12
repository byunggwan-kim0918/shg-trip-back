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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchEnrichSchedulerTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private EmbeddingBatchJob embeddingBatchJob;

    private BatchEnrichScheduler scheduler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scheduler = new BatchEnrichScheduler(placeRepository, objectMapper, embeddingBatchJob);
        ReflectionTestUtils.setField(scheduler, "maxDuration", java.time.Duration.ofHours(10));
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
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.enrich();

        verify(placeRepository).findEnrichTargets(anyString(), anyCollection(), any(Pageable.class));
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
                {"custom_id":"place_2","result":{"type":"errored","error":{"type":"error","error":{"type":"invalid_request_error"}}}}""";
        String foreign = """
                {"custom_id":"place_99","result":{"type":"succeeded","message":{"content":[]}}}""";
        String jsonl = String.join("\n", succeeded, error, "{깨진 행", foreign, "");

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(jsonl, List.of(ok, errored, missing));

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.failedIds()).containsExactly(2L, 3L);
        verify(placeRepository).writeEnrichment(ok);
        verify(placeRepository, never()).save(any(Place.class));
        // 성공이 있는 묶음의 요청 오류만 장소 탓이다. 결과에서 빠진 장소는 세지 않는다.
        verify(placeRepository).incrementEnrichAttempts(java.util.Set.of(2L));
        verify(embeddingBatchJob).reembed(List.of(ok));
        verify(placeRepository, never()).resetEmbeddings(anyList());
    }

    @Test
    @DisplayName("결과 반영: 만료·취소는 실패 횟수에 세지 않고, 전부 만료면 연속 실패 중단에도 세지 않는다")
    void applyResults_expiredNotCounted() {
        Place a = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        Place b = createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본");
        String jsonl = String.join("\n",
                "{\"custom_id\":\"place_1\",\"result\":{\"type\":\"expired\"}}",
                "{\"custom_id\":\"place_2\",\"result\":{\"type\":\"canceled\"}}");

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(jsonl, List.of(a, b));

        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.countsTowardAbort()).isFalse();
        verify(placeRepository, never()).incrementEnrichAttempts(anyCollection());
    }

    @Test
    @DisplayName("결과 반영: 성공 없이 전부 오류인 묶음은 묶음 전체 장애라 실패 횟수에 세지 않고 중단 판정에는 센다")
    void applyResults_allErroredIsBatchFault() {
        Place a = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        String jsonl = "{\"custom_id\":\"place_1\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"invalid_request_error\"}}}";

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(jsonl, List.of(a));

        assertThat(result.countsTowardAbort()).isTrue();
        verify(placeRepository, never()).incrementEnrichAttempts(anyCollection());
    }

    @Test
    @DisplayName("결과 반영: 응답을 해석하지 못한 장소는 실패 횟수에 센다")
    void applyResults_unreadableCounted() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        String line = """
                {"custom_id":"place_1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"이건 JSON이 아님"}]}}}""";

        scheduler.applyResults(line, List.of(place));

        verify(placeRepository).incrementEnrichAttempts(java.util.Set.of(1L));
    }

    @Test
    @DisplayName("결과 반영: 재임베딩에 실패한 장소만 임베딩을 비워 임베딩 단계가 다시 잡게 한다")
    void applyResults_reembedFailure_resetsOnlyFailed() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        when(embeddingBatchJob.reembed(List.of(place))).thenReturn(List.of(1L));
        String line = """
                {"custom_id":"place_1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{\\"tags\\":[\\"사찰\\"]}"}]}}}""";

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(line, List.of(place));

        assertThat(result.success()).isEqualTo(1);
        verify(placeRepository).resetEmbeddings(List.of(1L));
    }

    @Test
    @DisplayName("결과 반영: 저장이 실패한 장소도 실패 목록에 들어간다")
    void applyResults_saveFailure_isFailed() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        when(placeRepository.writeEnrichment(place)).thenThrow(new RuntimeException("DB down"));
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
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(place1), List.of(place2), List.of());

        assertThatThrownBy(() -> spied.enrich()).isInstanceOf(BatchStageFailed.class);

        verify(placeRepository, times(3)).findEnrichTargets(anyString(), anyCollection(), any(Pageable.class));
        verify(placeRepository, atLeastOnce()).findEnrichTargets(anyString(),
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
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    java.util.Collection<Long> excluded = inv.getArgument(1);
                    return switch (calls.incrementAndGet()) {
                        case 1 -> List.of(failed1, failed2);
                        case 2 -> excluded.containsAll(List.of(1L, 2L)) ? List.of(ok) : List.of(failed1, failed2);
                        default -> List.of();
                    };
                });

        assertThatThrownBy(() -> spied.enrich()).isInstanceOf(BatchStageFailed.class);

        verify(spied).processChunk(List.of(ok));
    }

    @Test
    @DisplayName("병렬: 묶음 수만큼 한 번에 조회해 나눠 동시에 처리하고 결과를 합친다")
    void enrich_parallelChunks_fetchesAndProcessesTogether() {
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1);
        ReflectionTestUtils.setField(scheduler, "parallelChunks", 2);
        Place place1 = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        Place place2 = createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본");
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(new BatchEnrichScheduler.ChunkResult(1, 0, List.of())).when(spied).processChunk(List.of(place1));
        doReturn(new BatchEnrichScheduler.ChunkResult(1, 0, List.of())).when(spied).processChunk(List.of(place2));
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(place1, place2), List.of());

        spied.enrich();

        verify(placeRepository, times(2)).findEnrichTargets(anyString(), anyCollection(), eq(PageRequest.of(0, 2)));
        verify(spied).processChunk(List.of(place1));
        verify(spied).processChunk(List.of(place2));
    }

    @Test
    @DisplayName("카테고리 접두어를 정하면 그 카테고리만 지역별 상한까지 보강 대상으로 조회한다")
    void enrich_categoryPrefixScopesTargets() {
        ReflectionTestUtils.setField(scheduler, "categoryPrefix", "Dining and Drinking");
        ReflectionTestUtils.setField(scheduler, "enrichRegions", "Taipei,Kyoto");
        ReflectionTestUtils.setField(scheduler, "maxPerRegion", 30);
        when(placeRepository.findEnrichTargetsInRegions(anyList(), anyString(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.enrich();

        verify(placeRepository).findEnrichTargetsInRegions(eq(List.of("Taipei")), eq("Dining and Drinking"),
                anyCollection(), eq(PageRequest.of(0, 30)));
        verify(placeRepository).findEnrichTargetsInRegions(eq(List.of("Kyoto")), eq("Dining and Drinking"),
                anyCollection(), eq(PageRequest.of(0, 30)));
    }

    @Test
    @DisplayName("병렬: 한 묶음에서 예외가 나도 그 묶음만 실패로 세고 나머지 결과는 살린다")
    void processChunks_exceptionInOneChunk_failsOnlyThatChunk() {
        Place place1 = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        Place place2 = createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본");
        BatchEnrichScheduler spied = spy(scheduler);
        doThrow(new IllegalStateException("boom")).when(spied).processChunk(List.of(place1));
        doReturn(new BatchEnrichScheduler.ChunkResult(1, 0, List.of())).when(spied).processChunk(List.of(place2));

        List<BatchEnrichScheduler.ChunkResult> results = spied.processChunks(List.of(List.of(place1), List.of(place2)));

        assertThat(results).containsExactly(
                new BatchEnrichScheduler.ChunkResult(0, 1, List.of(1L)),
                new BatchEnrichScheduler.ChunkResult(1, 0, List.of()));
    }

    @Test
    @DisplayName("재반영: 끝난 배치들의 결과만 custom_id로 장소를 읽어 반영하고, 새 보강은 하지 않는다")
    void enrich_resumeBatchIds_appliesResultsByCustomId() {
        ReflectionTestUtils.setField(scheduler, "resumeBatchId", "msgbatch_a, msgbatch_b");
        Place place = createPlace(7L, "센소지", "관광", "아사쿠사", "일본");
        BatchEnrichScheduler spied = spy(scheduler);
        String jsonl = """
                {"custom_id":"place_7","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{\\"tags\\":[\\"사찰\\"]}"}]}}}""";
        doReturn(jsonl).when(spied).fetchResults("msgbatch_a");
        doReturn(null).when(spied).fetchResults("msgbatch_b");
        when(placeRepository.findAllById(List.of(7L))).thenReturn(List.of(place));

        assertThatThrownBy(spied::enrich).isInstanceOf(BatchStageFailed.class).hasMessageContaining("배치 1개");

        verify(placeRepository).writeEnrichment(place);
        verify(placeRepository, never()).findEnrichTargets(anyString(), anyCollection(), any(Pageable.class));
        verify(spied, never()).processChunk(anyList());
    }

    @Test
    @DisplayName("재반영: 그 사이 보강된 행은 옛 결과로 덮지 않고, 실패 횟수도 올리지 않는다")
    void resume_skipsAlreadyEnrichedAndDoesNotCountAttempts() {
        Place done = createPlace(7L, "센소지", "관광", "아사쿠사", "일본");
        done.enrichWith(List.of("사찰"), "이미 보강됨", List.of("morning"));
        Place pending = createPlace(8L, "메이지신궁", "관광", "하라주쿠", "일본");
        BatchEnrichScheduler spied = spy(scheduler);
        String jsonl = String.join("\n",
                "{\"custom_id\":\"place_7\",\"result\":{\"type\":\"succeeded\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}}}",
                "{\"custom_id\":\"place_8\",\"result\":{\"type\":\"succeeded\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"깨짐\"}]}}}");
        doReturn(jsonl).when(spied).fetchResults("b1");
        when(placeRepository.findAllById(List.of(7L, 8L))).thenReturn(List.of(done, pending));

        assertThatThrownBy(() -> spied.resume(List.of("b1"))).isInstanceOf(BatchStageFailed.class);

        verify(placeRepository, never()).writeEnrichment(done);
        verify(placeRepository, never()).incrementEnrichAttempts(anyCollection());
    }

    @Test
    @DisplayName("보강 시간 상한이 지나면 새 묶음을 내지 않는다")
    void enrich_stopsAtDeadline() {
        ReflectionTestUtils.setField(scheduler, "maxDuration", java.time.Duration.ZERO);

        scheduler.enrich();

        verify(placeRepository, never()).findEnrichTargets(anyString(), anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("결과 대기 시간 초과는 연속 실패 중단에 세지 않는다(API 키 오류가 아니다)")
    void enrich_pollTimeouts_doNotAbort() {
        BatchEnrichScheduler spied = spy(scheduler);
        doAnswer(inv -> {
            List<Place> chunk = inv.getArgument(0);
            return new BatchEnrichScheduler.ChunkResult(0, chunk.size(), chunk.stream().map(Place::getId).toList(), false);
        }).when(spied).processChunk(anyList());
        java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong();
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> nextId.get() < 5
                        ? List.of(createPlace(nextId.incrementAndGet(), "장소", "관광", "제주", "한국"))
                        : List.of());

        assertThatThrownBy(spied::enrich).isInstanceOf(BatchStageFailed.class);

        verify(spied, times(5)).processChunk(anyList());
    }

    @Test
    @DisplayName("429는 Retry-After(초)만큼 기다리되 2분을 넘기지 않는다. 다른 상태는 0이다")
    @SuppressWarnings("unchecked")
    void retryAfter() {
        java.net.http.HttpResponse<String> limited = mock(java.net.http.HttpResponse.class);
        when(limited.statusCode()).thenReturn(429);
        when(limited.headers()).thenReturn(
                java.net.http.HttpHeaders.of(java.util.Map.of("retry-after", List.of("30")), (a, b) -> true),
                java.net.http.HttpHeaders.of(java.util.Map.of("retry-after", List.of("900")), (a, b) -> true));
        java.net.http.HttpResponse<String> server = mock(java.net.http.HttpResponse.class);
        when(server.statusCode()).thenReturn(503);

        assertThat(BatchEnrichScheduler.retryAfterMs(limited)).isEqualTo(30_000);
        assertThat(BatchEnrichScheduler.retryAfterMs(limited)).isEqualTo(120_000);
        assertThat(BatchEnrichScheduler.retryAfterMs(server)).isZero();
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
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> List.of(createPlace(nextId.incrementAndGet(), "장소", "관광", "제주", "한국")));

        assertThatThrownBy(() -> spied.enrich()).isInstanceOf(BatchStageFailed.class);

        verify(spied, times(BatchEnrichScheduler.MAX_CONSECUTIVE_FAILED_CHUNKS)).processChunk(anyList());
    }

    private Place overseas(String name, String category, String nameKo) {
        return Place.builder()
                .id(11L).name(name).nameKo(nameKo).address("京都府京都市東山区清水1丁目294")
                .latitude(BigDecimal.valueOf(34.9949)).longitude(BigDecimal.valueOf(135.7850))
                .category(category).region("Kyoto").country("JP")
                .savedAt(OffsetDateTime.now()).active(true)
                .build();
    }

    private static final String TEMPLE = "Community and Government > Spiritual Center > Buddhist Temple";
    private static final String RAMEN = "Dining and Drinking > Restaurant > Asian Restaurant > Japanese Restaurant > Ramen Restaurant";

    @Test
    @DisplayName("해외 관광지 프롬프트: 국가·여행지·주소, 현지 통화 입장료, 비어 있으면 name_ko를 묻는다")
    void buildEnrichPrompt_overseasAttraction() {
        String prompt = scheduler.buildEnrichPrompt(overseas("清水寺", TEMPLE, null));

        assertThat(prompt).contains("- 국가: 일본", "- 여행지: 교토 (Kyoto)", "- 주소: 京都府",
                "\"admission_fee_local\"", "입장료(엔", "\"name_ko\"", "외래어 표기법");
        assertThat(prompt).doesNotContain("\"admission_fee\"", "성산일출봉");
        assertThat(scheduler.buildEnrichPrompt(overseas("清水寺", TEMPLE, "기요미즈데라")))
                .doesNotContain("\"name_ko\"", "외래어 표기법");
    }

    @Test
    @DisplayName("한국 장소 프롬프트는 그대로다(원화 입장료, name_ko 없음)")
    void buildEnrichPrompt_koreaUnchanged() {
        Place place = createPlace(1L, "성산일출봉", "Landmarks and Outdoors > Mountain", "Jeju", "KR");

        assertThat(scheduler.buildEnrichPrompt(place))
                .contains("\"admission_fee\"", "성산일출봉 5000")
                .doesNotContain("name_ko", "admission_fee_local", "- 주소:");
    }

    @Test
    @DisplayName("해외 관광지 결과: 현지 통화 입장료·통화·name_ko를 기록하고 원화 입장료는 쓰지 않는다")
    void applyEnrichment_overseasAttraction() {
        Place place = overseas("清水寺", TEMPLE, null);
        String content = """
                {"name_ko":"기요미즈데라","tags":["사찰"],"description":"교토의 절","recommended_time_slots":["morning"],
                 "recommended_duration_minutes":60,"admission_fee":3000,"admission_fee_local":400,"place_role":"SIGHT"}""";

        assertThat(scheduler.applyEnrichment(place, content)).isTrue();
        assertThat(place.getAdmissionFeeLocal()).isEqualTo(400);
        assertThat(place.getAdmissionCurrency()).isEqualTo("JPY");
        assertThat(place.getAdmissionFee()).isNull();
        assertThat(place.getNameKo()).isEqualTo("기요미즈데라");
        assertThat(place.getPlaceRole()).isEqualTo("SIGHT");
    }

    @Test
    @DisplayName("해외 결과의 name_ko는 이미 있는 이름(Wikidata·보정표)을 덮지 않는다")
    void applyEnrichment_overseasKeepsExistingNameKo() {
        Place place = overseas("金閣寺", TEMPLE, "금각사");

        scheduler.applyEnrichment(place, "{\"name_ko\":\"로쿠온지\",\"tags\":[\"사찰\"]}");

        assertThat(place.getNameKo()).isEqualTo("금각사");
    }

    @Test
    @DisplayName("해외 식당 결과: 요리 이름으로 음식 종류를 정하고 name_ko를 채운다")
    void applyEnrichment_overseasDining() {
        Place place = overseas("一蘭 祇園店", RAMEN, null);
        String content = "{\"name_ko\":\"이치란 기온점\",\"tags\":[\"돈코츠라멘\",\"일식\"],\"recommended_time_slots\":[\"evening\"]}";

        assertThat(scheduler.applyEnrichment(place, content)).isTrue();
        assertThat(place.getCuisine()).isEqualTo("NOODLE");
        assertThat(place.getNameKo()).isEqualTo("이치란 기온점");
        assertThat(place.getPlaceRole()).isEqualTo("MEAL");
    }

    @Test
    @DisplayName("해외 식당 프롬프트: 국가·여행지·주소와 해외 요리 예시, 비어 있으면 name_ko를 묻는다")
    void buildEnrichPrompt_overseasDining() {
        String prompt = scheduler.buildEnrichPrompt(overseas("一蘭 祇園店", RAMEN, null));

        assertThat(prompt).contains("- 국가: 일본", "- 여행지: 교토 (Kyoto)", "훠궈", "\"name_ko\"", "도쿄·상하이 같은 지역명");
        assertThat(prompt).doesNotContain("서울·부산 같은 지역명");
    }

    @Test
    @DisplayName("해외 프롬프트는 알려진 정도를 묻고, 식당은 유명하지 않아도 설명을 조건부로 묻는다")
    void buildEnrichPrompt_overseasAsksFame() {
        String attraction = scheduler.buildEnrichPrompt(overseas("清水寺", TEMPLE, null));
        String dining = scheduler.buildEnrichPrompt(overseas("一蘭 祇園店", RAMEN, null));

        assertThat(attraction).contains("\"traveler_fame\"", "가이드북·미쉐린");
        assertThat(dining).contains("\"traveler_fame\": 0", "traveler_fame이 1 이상일 때만", "\"description\": null");
        assertThat(scheduler.buildEnrichPrompt(createPlace(1L, "성산일출봉", "Landmarks and Outdoors > Mountain", "Jeju", "KR")))
                .doesNotContain("traveler_fame");
    }

    @Test
    @DisplayName("해외 식당은 알려진 정도를 기록하고, 1 이상일 때만 설명을 받는다")
    void applyEnrichment_overseasDiningFame() {
        Place famous = overseas("一蘭 本社総本店", RAMEN, null);
        Place plain = overseas("ラーメン太郎", RAMEN, null);
        String famousJson = "{\"tags\":[\"라멘\"],\"traveler_fame\":2,\"description\":\"1960년 후쿠오카에서 시작한 돈코츠 라멘집.\"}";
        String plainJson = "{\"tags\":[\"라멘\"],\"traveler_fame\":0,\"description\":\"지어낸 설명\"}";

        scheduler.applyEnrichment(famous, famousJson);
        scheduler.applyEnrichment(plain, plainJson);

        assertThat(famous.getTravelerFame()).isEqualTo((short) 2);
        assertThat(famous.getDescription()).startsWith("1960년");
        assertThat(plain.getTravelerFame()).isEqualTo((short) 0);
        assertThat(plain.getDescription()).isNull();
    }

    @Test
    @DisplayName("야시장은 식당이 아니라 관광지 프롬프트로 보강해 시장 역할을 받는다")
    void nightMarketUsesAttractionPrompt() {
        Place market = overseas("士林夜市", "Dining and Drinking > Night Market", null);

        assertThat(scheduler.buildEnrichPrompt(market)).contains("\"place_role\"", "MARKET(전통시장·야시장)")
                .doesNotContain("venue");
        scheduler.applyEnrichment(market,
                "{\"tags\":[\"야시장\"],\"place_role\":\"MARKET\",\"traveler_fame\":2,\"recommended_time_slots\":[\"evening\",\"night\"]}");
        assertThat(market.getPlaceRole()).isEqualTo("MARKET");
        assertThat(market.getTravelerFame()).isEqualTo((short) 2);
        assertThat(market.getCuisine()).isNull();
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
