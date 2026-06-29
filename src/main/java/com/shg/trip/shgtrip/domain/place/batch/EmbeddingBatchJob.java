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

/**
 * embedding IS NULL인 장소를 조회하여 배치 임베딩을 생성하고 저장하는 작업.
 * <p>
 * 처리 흐름:
 * 1. embedding IS NULL이고 active=true인 장소를 페이징으로 조회
 * 2. 배치 사이즈(기본 100)씩 EmbeddingTextBuilder로 텍스트 생성
 * 3. EmbeddingService.embedBatch로 벡터 생성
 * 4. PlaceVectorSearchService.storeBatch로 DB에 저장
 * 5. 실패한 장소는 건너뛰고 계속 처리
 */
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
     * processBatch 결과. failedIds는 이번 실행에서 재시도하지 않도록 제외할 place ID 목록
     * (embedding이 여전히 NULL이라 다음 조회에도 다시 잡히기 때문).
     */
    record BatchResult(int success, int failed, List<Long> failedIds) {
    }

    /**
     * 임베딩 배치 생성을 실행한다.
     * <p>
     * page offset을 증가시키는 방식 대신, 이번 실행에서 실패한 place ID를 메모리에 누적해
     * 매번 첫 페이지를 다시 조회하면서 그 ID들을 걸러낸다. embedding IS NULL 조건의 총
     * 건수는 같은 실행 중에도 성공한 만큼 계속 줄어들기 때문에, 고정 offset 증가는 실제
     * 남은 건수보다 앞서나가 Page.isEmpty()로 조용히 조기 종료될 수 있다(시도조차 안 된
     * 레코드가 남아있는데도 "완료"로 로그가 찍히는 버그) — 그 경로를 원천적으로 없앤다.
     */
    public void execute() {
        log.info("EmbeddingBatchJob 시작 - batchSize={}", batchSize);

        int totalProcessed = 0;
        int totalFailed = 0;
        Set<Long> excludedIds = new HashSet<>();

        while (true) {
            Page<Place> page = placeRepository.findByEmbeddingIsNullAndActiveTrue(
                    PageRequest.of(0, batchSize));

            if (page.isEmpty()) {
                break;
            }

            List<Place> places = page.getContent().stream()
                    .filter(p -> !excludedIds.contains(p.getId()))
                    .toList();

            if (places.isEmpty()) {
                // 남은 건 이번 실행에서 이미 실패 처리된 장소뿐 — 다음 실행에서 재시도
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

    /**
     * 장소 배치를 처리하여 임베딩을 생성하고 저장한다.
     *
     * @param places 임베딩을 생성할 장소 목록
     * @return 성공/실패 건수와 실패한 place ID 목록
     */
    BatchResult processBatch(List<Place> places) {
        List<String> texts = new ArrayList<>();
        List<Place> validPlaces = new ArrayList<>();
        List<Long> textBuildFailedIds = new ArrayList<>();

        // 1. 텍스트 생성 (실패 시 해당 장소 건너뛰기)
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

        // 2. 배치 임베딩 생성
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

        // 3. 일괄 저장
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
