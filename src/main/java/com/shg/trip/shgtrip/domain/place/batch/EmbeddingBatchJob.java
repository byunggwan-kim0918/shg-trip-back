package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingTextBuilder;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class EmbeddingBatchJob {

    private final PlaceRepository placeRepository;
    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    @Value("${batch.embedding.batch-size:100}")
    private int batchSize;

    /**
     * 임베딩 대상 지역(쉼표 구분, 예: Jeju,Seoul,Busan). 비우면 전 지역.
     * 대량 적재 뒤 확인할 지역만 임베딩해 OpenAI 호출을 줄인다.
     */
    @Value("${batch.embedding.regions:}")
    private List<String> regions;

    /** failedIds는 embedding이 여전히 NULL이라 다음 조회에 다시 잡히므로 이번 실행에서 제외할 ID다. */
    record BatchResult(int success, int failed, List<Long> failedIds) {
    }

    /**
     * 매번 첫 페이지를 다시 조회하며 이번 실행의 실패 ID를 거른다. 성공한 만큼 남은 건수가 줄어
     * 고정 offset을 늘리면 앞서 나가 처리 안 된 레코드를 남긴 채 조기 종료된다.
     */
    public void execute() {
        log.info("EmbeddingBatchJob 시작 - batchSize={}", batchSize);

        int totalProcessed = 0;
        int totalFailed = 0;
        Set<Long> excludedIds = new HashSet<>();

        while (true) {
            List<String> target = regions == null ? List.of()
                    : regions.stream().map(String::trim).filter(r -> !r.isEmpty()).toList();
            Page<Place> page = target.isEmpty()
                    ? placeRepository.findByEmbeddingIsNullAndActiveTrue(PageRequest.of(0, batchSize))
                    : placeRepository.findByEmbeddingIsNullAndActiveTrueAndRegionIn(target, PageRequest.of(0, batchSize));

            if (page.isEmpty()) {
                break;
            }

            List<Place> places = page.getContent().stream()
                    .filter(p -> !excludedIds.contains(p.getId()))
                    .toList();

            if (places.isEmpty()) {
                // 남은 건 이번 실행에서 실패한 장소뿐이며 다음 실행에서 재시도한다
                break;
            }

            log.info("배치 처리 중 - {}건 (전체 미임베딩: {}건)", places.size(), page.getTotalElements());

            BatchResult result = processBatch(places);
            totalProcessed += result.success();
            totalFailed += result.failed();
            excludedIds.addAll(result.failedIds());
        }

        log.info("EmbeddingBatchJob 완료 - 처리: {}건, 실패: {}건", totalProcessed, totalFailed);
    }

    BatchResult processBatch(List<Place> places) {
        List<String> texts = new ArrayList<>();
        List<Place> validPlaces = new ArrayList<>();
        List<Long> textBuildFailedIds = new ArrayList<>();

        for (Place place : places) {
            try {
                String text = EmbeddingTextBuilder.buildText(place);
                texts.add(text);
                validPlaces.add(place);
            } catch (Exception e) {
                log.warn("텍스트 생성 실패 - placeId={}, name={}: {}",
                        place.getId(), place.getName(), e.getMessage());
                textBuildFailedIds.add(place.getId());
            }
        }

        if (validPlaces.isEmpty()) {
            return new BatchResult(0, places.size(), allIds(places));
        }

        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedBatch(texts);
        } catch (Exception e) {
            log.error("배치 임베딩 생성 API 호출 실패: {}", e.getMessage(), e);
            return new BatchResult(0, places.size(), allIds(places));
        }

        if (embeddings.size() != validPlaces.size()) {
            log.error("임베딩 결과 수 불일치 - 요청: {}건, 응답: {}건",
                    validPlaces.size(), embeddings.size());
            return new BatchResult(0, places.size(), allIds(places));
        }

        Map<Long, float[]> embeddingMap = new HashMap<>();
        for (int i = 0; i < validPlaces.size(); i++) {
            embeddingMap.put(validPlaces.get(i).getId(), embeddings.get(i));
        }

        try {
            placeVectorSearchService.storeBatch(embeddingMap);
        } catch (Exception e) {
            log.error("임베딩 일괄 저장 실패: {}", e.getMessage(), e);
            return new BatchResult(0, places.size(), allIds(places));
        }

        int failed = places.size() - validPlaces.size();
        return new BatchResult(validPlaces.size(), failed, textBuildFailedIds);
    }

    private List<Long> allIds(List<Place> places) {
        return places.stream().map(Place::getId).toList();
    }
}
